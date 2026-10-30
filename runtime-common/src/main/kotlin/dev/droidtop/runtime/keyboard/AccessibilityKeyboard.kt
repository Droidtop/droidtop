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
     * Whether Android actually runs the service: bound, or among the services Android reports enabled. A switch the
     * person turned on that Android then refused (Android 13's restricted settings for an app installed from a file:
     * the console showed it "enabled" while ACCESS_RESTRICTED_SETTINGS was deny, Droidtop/tracker#740) is not.
     */
    fun isActive(context: Context): Boolean {
        if (connected) return true
        val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java) ?: return false
        return runCatching {
            manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info -> info.resolveInfo?.serviceInfo?.let { it.packageName == context.packageName && it.name == SERVICE_CLASS } == true }
        }.getOrDefault(false)
    }

    /**
     * Whether the HOME button combos work over other apps (docs/SPEC.md "In-game overlays: HOME chords",
     * Droidtop/tracker#740), and the line that says so in plain words: on, off, or blocked by Android's restricted
     * settings. They need this service running: Android delivers a game's button presses to no other app, so only the
     * service's key filter sees HOME held with R2, L2 or Start. Reads system state: off the main thread.
     */
    fun homeChords(context: Context): Pair<Boolean, String> = when {
        isActive(context) -> true to "On: HOME + R2, L2 or Start open droidtop's menus over any app or game"
        RestrictedSettings.stepNeeded(context, RestrictedGrant.ACCESSIBILITY, granted = false) ->
            false to "Blocked: Android stops apps installed from a file from seeing button presses until you allow it. " +
                "Press for droidtop's App info, open its menu and choose Allow restricted settings. Where there is no " +
                "such item (Retroid's Android), run this from a computer once: ${restrictedSettingsCommand(context)}. " +
                "Installing droidtop through an app store or a session installer (SAI) avoids the block"
        else -> false to "Off: Android needs you to allow droidtop to see button presses over other apps. Press to allow it"
    }

    /**
     * Turns the service on for the person who pressed to (Droidtop/tracker#740). With the privileged shell (Shizuku,
     * Sui or wireless debugging; docs/SPEC.md "Device setup") droidtop allows its restricted settings and adds itself
     * to Android's enabled list, keeping every service already on it (a vendor's, such as Retroid's Game Assistant,
     * stays). Without it, App info opens when Android's restricted settings block the service, else Android's
     * accessibility page. Either way it can be turned off in Android's settings again, and what the shell changed is
     * undone from Settings > Accounts and sources > Device setup. Returns the line to say. Off the main thread: it
     * may run a shell command.
     */
    fun turnOn(context: Context): String {
        if (isActive(context)) return "On"
        // The device-setup item's own way, so what it changed is kept and Device setup can undo it.
        if (dev.droidtop.runtime.tasks.DeviceSetup.turnOnButtonPresses(context, dev.droidtop.runtime.tasks.TaskManager.shell)) return "On"
        if (RestrictedSettings.stepNeeded(context, RestrictedGrant.ACCESSIBILITY, granted = false)) {
            // Some Androids have no "Allow restricted settings" item at all (the Retroid Pocket 5, Droidtop/tracker#740),
            // so the way that always works is said too, and copied, ready to paste on the computer.
            copyCommand(context)
            RestrictedSettings.openAppInfo(context)
            return "In droidtop's App info, open the menu and choose Allow restricted settings, then press this row again. " +
                "No such item? Run from a computer (copied): ${restrictedSettingsCommand(context)}"
        }
        openSettings(context)
        return "Turn on droidtop in the list Android opened"
    }

    /** The one command that lifts Android's restricted-settings block from a computer with adb. */
    fun restrictedSettingsCommand(context: Context): String =
        "adb shell appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow"

    private fun copyCommand(context: Context) {
        runCatching {
            context.getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("adb command", restrictedSettingsCommand(context)))
        }
    }

    /** Android's accessibility settings, where the user turns the service on (no app can do that for them). */
    fun openSettings(context: Context) {
        RestrictedSettings.noteAttempt(context, RestrictedGrant.ACCESSIBILITY)
        SettingsLaunch.start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
