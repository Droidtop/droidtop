package dev.droidtop.pluginhost

import android.os.Bundle
import android.util.Log
import rikka.shizuku.ShizukuProvider

/**
 * Shizuku's provider, in droidtop's main process (Droidtop/tracker#775), which hands the Shizuku binder to droidtop's
 * processes (docs/plugin-api.md 5.6, the OS rule): a
 * full-access plugin may use the OS directly, so `:pluginhost*` and droidtop itself get it as before, and the Shizuku
 * server (another UID: the shell or root) still delivers it. An open plugin's process runs as droidtop's UID too, and
 * without full access the sandbox must keep it from the OS, so it and a contained plugin are refused.
 */
class DroidtopShizukuProvider : ShizukuProvider() {
    companion object {
        init {
            // ShizukuProvider.onCreate starts Sui by itself, on the main thread at process start; droidtop's start touches
            // no elevated backend (Droidtop/tracker#775), so Sui starts off the main thread on first use
            // (ShizukuTransport.ensureStarted). Set before onCreate: the class is loaded before its instance is made.
            ShizukuProvider.disableAutomaticSuiInitialization()
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        if (!DroidtopCallers.callerMayUseShizuku(context)) {
            Log.w("droidtop.plugin", "refused Shizuku's binder to a plugin without full access")
            return null
        }
        return super.call(method, arg, extras)
    }
}
