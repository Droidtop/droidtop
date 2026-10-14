package dev.droidtop.library

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import app.murinelauncher.settings.hiddenapps.HiddenAppsRepository

/**
 * Hide an installed app from droidtop, or show it again (Droidtop/tracker#499). The one rule is the
 * launcher's own hidden list ([HiddenAppsRepository], keyed by launcher activity), the list
 * [NativeAppProvider] reads on every scan and Standard's app drawer honours, so an app hidden here
 * is hidden everywhere and the Apps Filter's Hidden view brings it back. Reads the package manager
 * and writes a preference file: call it off the main thread.
 */
object AppHiding {
    fun set(context: Context, packageName: String, hidden: Boolean) {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
        launcherApps.getActivityList(packageName, Process.myUserHandle()).forEach { activity ->
            HiddenAppsRepository.setHidden(context, activity.componentName, hidden)
        }
    }
}
