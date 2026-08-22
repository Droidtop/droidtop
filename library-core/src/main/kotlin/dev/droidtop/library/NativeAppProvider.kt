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

        return launcherApps.getActivityList(null, Process.myUserHandle())
            .map { activityInfo ->
                val appInfo = AppInfo(context, activityInfo, activityInfo.user)
                iconCache.getTitleAndIcon(appInfo, activityInfo, CacheLookupFlag.DEFAULT_LOOKUP_FLAG)

                val packageName = activityInfo.componentName.packageName
                val artworkUri = writeIconFile(iconDir, packageName) {
                    appInfo.bitmap.newIcon(context)
                }

                LibraryEntry(
                    id = packageName,
                    title = (appInfo.title ?: activityInfo.label).toString(),
                    kind = LibraryEntryKind.NATIVE_ANDROID_APP,
                    artworkUri = artworkUri,
                )
            }
            .distinctBy { it.id }
    }

    // Real icons only, never a placeholder file: on any failure this
    // returns null and the entry just renders with no artwork (Coil's
    // existing AsyncImage null-model handling), rather than caching a
    // broken/empty file that would then stick around across scans.
    private fun writeIconFile(iconDir: File, packageName: String, loadDrawable: () -> android.graphics.drawable.Drawable): String? {
        val file = File(iconDir, "$packageName.png")
        return try {
            val drawable = loadDrawable()
            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
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
