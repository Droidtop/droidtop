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

    /**
     * Whether the HOME button combos work over other apps (docs/SPEC.md "In-game overlays: HOME chords",
     * Droidtop/tracker#740), and the line that says so in plain words. They need this service on: Android delivers a
     * game's button presses to no other app, so only the service's key filter sees HOME held with R2, L2 or Start.
     * Reads a system setting.
     */
    fun homeChords(context: Context): Pair<Boolean, String> =
        if (isEnabled(context)) {
            true to "On: HOME + R2, L2 or Start open droidtop's menus over any app or game"
        } else {
            false to "Off: Android needs you to allow droidtop to see button presses over other apps. Press to allow it"
        }

    /**
     * Turns the service on for the person who pressed to (Droidtop/tracker#740). With elevated access (Shizuku or
     * Sui) droidtop adds itself to Android's enabled list, keeping every service already on it (a vendor's, such as
     * Retroid's Game Assistant, stays); otherwise Android's accessibility page opens, where the person turns it on.
     * Either way it can be turned off there again. Returns the line to say. Off the main thread: it may run a shell
     * command.
     */
    fun turnOn(context: Context): String {
        if (isEnabled(context)) return "On"
        val shell = dev.droidtop.runtime.tasks.TaskManager.shell
        if (shell.capabilities().shellCommand) {
            val now = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            }.getOrNull().orEmpty()
            val own = ComponentName(context.packageName, SERVICE_CLASS).flattenToString()
            val next = (now.split(':').filter { it.isNotBlank() } + own).distinct().joinToString(":")
            val listed = shell.exec(listOf("settings", "put", "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next))?.exit == 0
            val enabled = listed && shell.exec(listOf("settings", "put", "secure", Settings.Secure.ACCESSIBILITY_ENABLED, "1"))?.exit == 0
            if (enabled) return "On"
        }
        openSettings(context)
        return "Turn on droidtop in the list Android opened"
    }

    /** Android's accessibility settings, where the user turns the service on (no app can do that for them). */
    fun openSettings(context: Context) {
        RestrictedSettings.noteAttempt(context, RestrictedGrant.ACCESSIBILITY)
        SettingsLaunch.start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
