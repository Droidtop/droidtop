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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Scans installed, launchable Android apps and surfaces each as a
 * [LibraryEntry]. Real integration with :shell-default's own Launcher3
 * fork, not a second app-list/icon-cache implementation -- Handheld's Apps
 * tab shows the exact same apps, titles, and themed icons Standard's own
 * app drawer shows, sourced from the same [LauncherAppState.iconCache].
 * The pattern (LauncherApps -> AppInfo -> iconCache.getTitleAndIcon) mirrors
 * shell-default's own SettingsHiddenAppsFragment.loadApps(), a real,
 * already-working use of this exact API inside this same codebase.
 */
class NativeAppProvider(private val context: Context) : LibraryProvider {
    override val kinds = setOf(LibraryEntryKind.NATIVE_ANDROID_APP)

    override suspend fun scan(): List<LibraryEntry> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val iconCache = LauncherAppState.getInstance(context).iconCache
        val iconDir = File(context.cacheDir, "app_icons").apply { mkdirs() }

        val activities = launcherApps.getActivityList(null, Process.myUserHandle())

        // IconCache asserts it's only ever touched from Launcher3's own
        // worker thread -- a real, confirmed crash caught via logcat
        // ("Cache accessed on wrong thread"), not a guess: Dispatchers.IO
        // runs on kotlinx.coroutines' own thread pool, a different thread
        // than Launcher3's MODEL_EXECUTOR. SettingsHiddenAppsFragment (the
        // real, working precedent this class mirrors) avoids this the same
        // way, via Executors.MODEL_EXECUTOR.execute { ... }.
        // Every appInfo.bitmap/iconCache touch (including .newIcon(), which
        // reads the cache-owned BitmapInfo) stays inside this block, same
        // as SettingsHiddenAppsFragment's real working precedent -- only
        // the resulting title string and a plain Bitmap (safe to touch from
        // any thread) leave this dispatcher.
        val entries = withContext(Executors.MODEL_EXECUTOR.asCoroutineDispatcher()) {
            activities.map { activityInfo ->
                val appInfo = AppInfo(context, activityInfo, activityInfo.user)
                iconCache.getTitleAndIcon(appInfo, activityInfo, CacheLookupFlag.DEFAULT_LOOKUP_FLAG)
                val bitmap = drawableToBitmap(appInfo.bitmap.newIcon(context))
                Triple(activityInfo.componentName.packageName, (appInfo.title ?: activityInfo.label).toString(), bitmap)
            }
        }

        return entries
            .map { (packageName, title, bitmap) ->
                LibraryEntry(
                    id = packageName,
                    title = title,
                    kind = LibraryEntryKind.NATIVE_ANDROID_APP,
                    artworkUri = writeIconFile(iconDir, packageName, bitmap),
                )
            }
            .distinctBy { it.id }
    }

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): Bitmap {
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
    private fun writeIconFile(iconDir: File, packageName: String, bitmap: Bitmap): String? {
        val file = File(iconDir, "$packageName.png")
        return try {
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            file.absolutePath
        } catch (t: Throwable) {
            Log.e("droidtop.NativeAppProvider", "Failed to cache icon for $packageName", t)
            null
        }
    }

    override suspend fun launch(entry: LibraryEntry) {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(entry.id) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
