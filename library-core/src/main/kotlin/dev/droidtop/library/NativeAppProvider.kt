package dev.droidtop.library

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Scans installed, launchable Android apps via [PackageManager] and surfaces
 * each as a [LibraryEntry]. The first [LibraryProvider] worth building
 * (per this module's README) since it needs no container/Wine/remote-stream
 * backend to be real — everything else in the library model was designed
 * around this being an equally first-class entry type, not a special case.
 */
class NativeAppProvider(private val context: Context) : LibraryProvider {
    override val kinds = setOf(LibraryEntryKind.NATIVE_ANDROID_APP)

    override suspend fun scan(): List<LibraryEntry> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION") // queryIntentActivities(Intent, Int) — the ResolveInfoFlags overload needs API 33
        val resolved = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY)

        return resolved.map { info ->
            val packageName = info.activityInfo.packageName
            LibraryEntry(
                id = packageName,
                title = info.loadLabel(pm).toString(),
                kind = LibraryEntryKind.NATIVE_ANDROID_APP,
            )
        }.distinctBy { it.id }
    }

    override suspend fun launch(entry: LibraryEntry) {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(entry.id) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
