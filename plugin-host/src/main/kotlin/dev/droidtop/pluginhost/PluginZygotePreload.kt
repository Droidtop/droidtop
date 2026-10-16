package dev.droidtop.pluginhost

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Loads droidtop's plugin library once in droidtop's app zygote (Android 10, API 29, and later), so every contained
 * plugin's isolated process forked from it starts with the library already in place (docs/plugin-api.md 5.3, "The
 * rigs"). On the BlueStacks rig the first app library an isolated process loads waits a constant 10 s inside the
 * device's native bridge, which asks the permission service an isolated UID may not look up: each plugin process paid
 * it, and the first search after a start ran out of time. The app zygote pays it once; [PluginSyscallFilter]'s own load
 * in each child then finds the library loaded. A failure here is only a slower start: the child loads it itself.
 */
@RequiresApi(29)
class PluginZygotePreload : android.app.ZygotePreload {
    override fun doPreload(appInfo: ApplicationInfo) {
        val started = android.os.SystemClock.uptimeMillis()
        runCatching { System.loadLibrary("droidtoppy") }
            .onSuccess { Log.i("droidtop.plugin", "app zygote: plugin library loaded in ${android.os.SystemClock.uptimeMillis() - started} ms") }
            .onFailure { Log.w("droidtop.plugin", "app zygote: plugin library not preloaded; each plugin process loads it itself", it) }
    }
}
