package dev.droidtop.library

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.util.Log
import com.android.launcher3.LauncherAppState
import com.android.launcher3.icons.cache.CacheLookupFlag
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.util.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Scans installed, launchable Android apps and surfaces each as a
 * [LibraryEntry]. Real integration with :shell-default's own Launcher3
 * fork, not a second app-list/icon-cache implementation -- Gaming's Apps
 * tab shows the exact same apps, titles, and themed icons Standard's own
 * app drawer shows, sourced from the same [LauncherAppState.iconCache].
 * The pattern (LauncherApps -> AppInfo -> iconCache.getTitleAndIcon) mirrors
 * shell-default's own SettingsHiddenAppsFragment.loadApps(), a real,
 * already-working use of this exact API inside this same codebase.
 *
 * One app never fails the scan: title and icon are resolved per activity
 * inside their own guard, so a broken icon costs that one entry its
 * artwork and nothing else.
 */
class NativeAppProvider(private val context: Context) : LibraryProvider {
    override val kinds = setOf(LibraryEntryKind.NATIVE_ANDROID_APP)

    // The package manager answers in milliseconds and apps are installed
    // and removed outside droidtop; an index of them would only ever be
    // stale. See LibraryProvider.indexed.
    override val indexed: Boolean get() = false

    override suspend fun scan(): List<LibraryEntry> = scanLock.withLock { scanLocked() }

    private suspend fun scanLocked(): List<LibraryEntry> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val appState = LauncherAppState.getInstance(context)
        val iconCache = appState.iconCache
        val iconProvider = appState.iconProvider

        // Everything that touches the disk or asks the package manager
        // happens here, before the model thread is borrowed: the folder is
        // listed once (not a `stat` per app) and each package's update
        // time read once. Two launcher activities of one package are one
        // entry (the id is the package), so the second is never resolved.
        val iconDir = File(context.cacheDir, "app_icons")
        val (activities, updateTimes, cachedNames) = withContext(Dispatchers.IO) {
            iconDir.mkdirs()
            val activities = launcherApps.getActivityList(null, Process.myUserHandle())
                .distinctBy { it.componentName.packageName }
            val updateTimes = activities.associate { activity ->
                val pkg = activity.componentName.packageName
                pkg to runCatching { context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
            }
            Triple(activities, updateTimes, iconDir.list()?.toHashSet() ?: hashSetOf())
        }

        // IconCache asserts it's only ever touched from Launcher3's own
        // worker thread -- a real, confirmed crash caught via logcat
        // ("Cache accessed on wrong thread"), not a guess: Dispatchers.IO
        // runs on kotlinx.coroutines' own thread pool, a different thread
        // than Launcher3's MODEL_EXECUTOR. SettingsHiddenAppsFragment (the
        // real, working precedent this class mirrors) avoids this the same
        // way, via Executors.MODEL_EXECUTOR.execute { ... }.
        // Only the cache-owned calls stay inside this block: the title and
        // icon lookup, the icon state, and .newIcon() (which reads the
        // cache-owned BitmapInfo) -- and newIcon only for an app whose file
        // for this exact state is not already on disk. Drawing and PNG
        // encoding happen after, on Dispatchers.IO: the model thread also
        // loads Standard's workspace and must not wait on them.
        val scanned = withContext(Executors.MODEL_EXECUTOR.asCoroutineDispatcher()) {
            activities.map { activityInfo ->
                // PER APP, not per scan: one app's icon must never cost
                // every other app its entry. Under software rendering the
                // clock's adaptive icon threw "Software rendering doesn't
                // support hardware bitmaps" out of drawableToBitmap and
                // the whole Apps list came back empty (emulator rig,
                // 2026-09-10) -- the same shape of loss an app with a
                // broken resource would cause on real hardware. An app
                // whose icon fails is still an app; it lists without one
                // (writeIconFile's own null-artwork path already renders).
                val packageName = activityInfo.componentName.packageName
                // What this ONE app is, said by the app itself: Android's
                // own application category (`android:appCategory`, API
                // 26+), which is what a store shows it under. Null when
                // the manifest declares none (CATEGORY_UNDEFINED), and
                // the entry then falls back to what its kind is called.
                // Never invented: an app that says nothing gets nothing.
                val category = runCatching {
                    android.content.pm.ApplicationInfo
                        .getCategoryTitle(context, activityInfo.applicationInfo.category)
                        ?.toString()
                        ?.takeIf { it.isNotBlank() }
                }.getOrNull()
                runCatching {
                    val appInfo = AppInfo(context, activityInfo, activityInfo.user)
                    iconCache.getTitleAndIcon(appInfo, activityInfo, CacheLookupFlag.DEFAULT_LOOKUP_FLAG)
                    val iconName = AppIconFiles.fileName(
                        packageName,
                        updateTimes[packageName] ?: 0L,
                        iconProvider.getStateForApp(activityInfo.applicationInfo),
                    )
                    // An app that declares no label on its launcher
                    // activity gives its own class name back
                    // (`com.bluestacks.bsxlauncher.Main` on the rig);
                    // [AppLabels] is the one rule for what it is called
                    // instead.
                    ScannedApp(
                        packageName = packageName,
                        title = AppLabels.labelFor(appInfo.title ?: activityInfo.label, packageName),
                        iconName = iconName,
                        icon = if (iconName in cachedNames) null else appInfo.bitmap.newIcon(context),
                        category = category,
                    )
                }.getOrElse { t ->
                    Log.w("droidtop.NativeAppProvider", "Icon/title failed for $packageName; listing it without an icon", t)
                    ScannedApp(packageName, AppLabels.labelFor(activityInfo.label, packageName), null, null, category)
                }
            }
        }

        return withContext(Dispatchers.IO) {
            val entries = scanned.map { app ->
                LibraryEntry(
                    id = app.packageName,
                    title = app.title,
                    kind = LibraryEntryKind.NATIVE_ANDROID_APP,
                    artworkUri = iconPath(iconDir, app, cachedNames),
                    // The same field a scraped game's genre lands in: on
                    // an installed app the platform is the source, and
                    // the shell reads one field either way.
                    genre = app.category,
                )
            }
            // Only a finished scan prunes: a cancelled one throws before
            // this, and its partial view of the apps deletes nothing.
            val current = scanned.mapNotNullTo(HashSet()) { it.iconName }
            AppIconFiles.stale(iconDir.list()?.toList().orEmpty(), current).forEach { File(iconDir, it).delete() }
            entries
        }
    }

    /**
     * One app as the scan read it, before it becomes a [LibraryEntry].
     * [iconName] is its file under the current state (null when the
     * lookup failed); [icon] is set only when that file must be drawn.
     */
    private class ScannedApp(
        val packageName: String,
        val title: String,
        val iconName: String?,
        val icon: android.graphics.drawable.Drawable?,
        val category: String?,
    )

    private fun iconPath(iconDir: File, app: ScannedApp, cachedNames: Set<String>): String? {
        val name = app.iconName ?: return null
        if (name in cachedNames) return File(iconDir, name).absolutePath
        val drawable = app.icon ?: return null
        val bitmap = runCatching { drawableToBitmap(drawable) }.getOrElse { t ->
            Log.w("droidtop.NativeAppProvider", "Icon draw failed for ${app.packageName}; listing it without an icon", t)
            return null
        }
        return writeIconFile(iconDir, name, app.packageName, bitmap)
    }

    /**
     * A drawable as a software bitmap.
     *
     * A HARDWARE-config bitmap is copied rather than drawn: a software
     * Canvas refuses to draw one ("Software rendering doesn't support
     * hardware bitmaps"), which is the real failure the per-app guard
     * above was catching. Copying gives the icon back instead of only
     * surviving its loss.
     */
    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): Bitmap {
        val source = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        if (source != null && source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false)?.let { return it }
        }
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    // Real icons only, never a placeholder file: on any failure this
    // returns null and the entry just renders with no artwork (Coil's
    // existing AsyncImage null-model handling), rather than caching a
    // broken/empty file that would then stick around across scans.
    // Written to a temporary name and renamed, so a reader (or the next
    // scan's "already cached" check) never sees half a PNG.
    private fun writeIconFile(iconDir: File, name: String, packageName: String, bitmap: Bitmap): String? {
        val file = File(iconDir, name)
        val temp = File(iconDir, ".$name.tmp")
        return try {
            FileOutputStream(temp).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            if (!temp.renameTo(file)) error("rename to $name failed")
            file.absolutePath
        } catch (t: Throwable) {
            Log.e("droidtop.NativeAppProvider", "Failed to cache icon for $packageName", t)
            temp.delete()
            null
        }
    }

    override suspend fun launch(entry: LibraryEntry) {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(entry.id) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        LaunchDisplay.start(context, intent)
    }

    private companion object {
        // One scan at a time owns the icon folder: a second scan's prune
        // must not delete the file (or temporary file) the first is writing.
        val scanLock = Mutex()
    }
}
