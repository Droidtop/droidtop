package dev.droidtop.runtime.keyboard

import dev.droidtop.runtime.systemstatus.SettingsLaunch
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.droidtop.runtime.systemstatus.RestrictedGrant
import dev.droidtop.runtime.systemstatus.RestrictedSettings

/**
 * droidtop's accessibility service for typing into other apps (docs/SPEC.md 4c, "Typing on the add-on display",
 * Droidtop/tracker#314): the route that works whichever keyboard is selected and with no elevated access. The
 * service itself lives in `:app`; this is its state and its settings step, shared with the settings catalog and
 * the shells, which cannot see `:app`.
 */
object AccessibilityKeyboard {
    const val SERVICE_CLASS = "dev.droidtop.app.TypingAccessibilityService"

    /** True while Android has the service bound (set by the service). */
    @Volatile
    var connected: Boolean = false

    /** Installed by `:app`: whether the service holds a focused text field of another app right now. */
    @Volatile
    var hasEditor: () -> Boolean = { false }

    /** Whether the user has turned the service on in Android's accessibility settings. Reads a system setting. */
    fun isEnabled(context: Context): Boolean {
        val flat = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull() ?: return false
        val own = ComponentName(context.packageName, SERVICE_CLASS)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == own }
    }

    /** Android's accessibility settings, where the user turns the service on (no app can do that for them). */
    fun openSettings(context: Context) {
        RestrictedSettings.noteAttempt(context, RestrictedGrant.ACCESSIBILITY)
        SettingsLaunch.start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
