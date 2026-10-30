package dev.droidtop.runtime.tasks

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import dev.droidtop.runtime.R
import dev.droidtop.runtime.keyboard.AccessibilityKeyboard

/** Where a [DeviceSetupItem] stands on this device. */
enum class SetupState {
    /** This Android has nothing to change (too old for the limit, say). */
    NOT_NEEDED,

    /** Not done yet: droidtop can do it, after asking. */
    TO_DO,

    /** Done, and by droidtop: the row offers to undo it. */
    DONE_BY_DROIDTOP,

    /** Done some other way (by the person, or Android's default): nothing for droidtop to undo. */
    DONE,
}

/**
 * One change to Android's settings that droidtop can make itself through the one privileged shell (docs/SPEC.md
 * "Device setup", Droidtop/tracker#816, owner 2026-10-30: "the things droidtop can do for setup, when shizuku is
 * running, or through wireless debugging's autosetup"). Every item is asked first, in plain words ([ask]), and can be
 * undone from the same row ([undoAsk]) when droidtop made it. The words are string resources (Droidtop/tracker#177),
 * read with `words()` or `getString`. [state], [apply] and [undo] run shell commands: IO only.
 */
class DeviceSetupItem(
    val id: String,
    /** The row's title, at most 40 characters. */
    @StringRes val title: Int,
    /** The confirm card's question before [apply]: what changes, for whom, and that it can be undone. */
    @StringRes val ask: Int,
    /** The confirm card's question before [undo]: what comes back. */
    @StringRes val undoAsk: Int,
    /** The row's line for each state, at most 90 characters. */
    val line: (SetupState) -> Int,
    val state: (Context, PrivilegedShell) -> SetupState,
    val apply: (Context, PrivilegedShell) -> Boolean,
    val undo: (Context, PrivilegedShell) -> Boolean,
)

/**
 * The device-setup items, in the order onboarding and Settings show them (docs/SPEC.md "Device setup"). They all run
 * on [TaskManager.shell], so the Shizuku app, the Shizuku plugin and wireless debugging serve every one of them alike:
 * one mechanism, whichever is there. What each item changed is kept in droidtop's preferences, so undo puts back what
 * was there before rather than a guess.
 */
object DeviceSetup {
    private const val PREFS = "device_setup"

    /** The line for an item that needs a shell when none can run commands. */
    @StringRes val NO_SHELL: Int = R.string.device_setup_no_shell

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun doneByDroidtop(context: Context, id: String) = prefs(context).getBoolean("$id.done", false)

    /** Whether [shell] can run a command now. */
    fun canRun(shell: PrivilegedShell): Boolean = runCatching { shell.capabilities().shellCommand }.getOrDefault(false)

    // ---- Android's child-process limit (Droidtop/tracker#816) ----

    val childProcesses = DeviceSetupItem(
        id = "child_processes",
        title = R.string.device_setup_child_title,
        ask = R.string.device_setup_child_ask,
        undoAsk = R.string.device_setup_child_undo_ask,
        line = { state ->
            when (state) {
                SetupState.NOT_NEEDED -> R.string.device_setup_child_not_needed
                SetupState.TO_DO -> R.string.device_setup_child_to_do
                SetupState.DONE_BY_DROIDTOP -> R.string.device_setup_child_by_droidtop
                SetupState.DONE -> R.string.device_setup_child_done
            }
        },
        state = { context, shell ->
            val sdk = Build.VERSION.SDK_INT
            when {
                sdk < PhantomProcesses.FIRST_SDK -> SetupState.NOT_NEEDED
                PhantomProcesses.read(shell, sdk, android.os.Process.myUid(), context.packageName).applies -> SetupState.TO_DO
                doneByDroidtop(context, "child_processes") -> SetupState.DONE_BY_DROIDTOP
                else -> SetupState.DONE
            }
        },
        apply = { context, shell ->
            PhantomProcesses.run(shell, PhantomProcesses.liftCommands(Build.VERSION.SDK_INT))
                .also { if (it) prefs(context).edit().putBoolean("child_processes.done", true).apply() }
        },
        undo = { context, shell ->
            PhantomProcesses.run(shell, PhantomProcesses.restoreCommands(Build.VERSION.SDK_INT))
                .also { if (it) prefs(context).edit().remove("child_processes.done").apply() }
        },
    )

    // ---- Button presses over other apps: #740's accessibility unlock ----

    private const val RESTRICTED_OP = "ACCESS_RESTRICTED_SETTINGS"

    val buttonPresses = DeviceSetupItem(
        id = "button_presses",
        title = R.string.device_setup_buttons_title,
        ask = R.string.device_setup_buttons_ask,
        undoAsk = R.string.device_setup_buttons_undo_ask,
        line = { state ->
            when (state) {
                SetupState.NOT_NEEDED, SetupState.DONE -> R.string.device_setup_buttons_on
                SetupState.TO_DO -> R.string.device_setup_buttons_to_do
                SetupState.DONE_BY_DROIDTOP -> R.string.device_setup_buttons_by_droidtop
            }
        },
        state = { context, _ ->
            when {
                !AccessibilityKeyboard.isActive(context) -> SetupState.TO_DO
                doneByDroidtop(context, "button_presses") -> SetupState.DONE_BY_DROIDTOP
                else -> SetupState.DONE
            }
        },
        apply = { context, shell -> turnOnButtonPresses(context, shell) },
        undo = { context, shell -> turnOffButtonPresses(context, shell) },
    )

    // ---- Auto power (Droidtop/tracker#841, owner Q3: off until chosen, offered in onboarding, recommended on) ----

    val autoPower = DeviceSetupItem(
        id = "auto_power",
        title = R.string.device_setup_power_title,
        ask = R.string.device_setup_power_ask,
        undoAsk = R.string.device_setup_power_undo_ask,
        line = { state ->
            when (state) {
                SetupState.NOT_NEEDED -> R.string.device_setup_power_not_needed
                SetupState.TO_DO -> R.string.device_setup_power_to_do
                SetupState.DONE, SetupState.DONE_BY_DROIDTOP -> R.string.device_setup_power_on
            }
        },
        state = { context, _ ->
            when {
                Build.VERSION.SDK_INT < PhantomProcesses.FIRST_SDK -> SetupState.NOT_NEEDED
                dev.droidtop.runtime.power.PowerPrefs.auto(context) -> SetupState.DONE_BY_DROIDTOP
                else -> SetupState.TO_DO
            }
        },
        apply = { context, _ ->
            dev.droidtop.runtime.power.PowerPrefs.setAuto(context, true)
            true
        },
        undo = { context, shell ->
            dev.droidtop.runtime.power.PowerPrefs.setAuto(context, false)
            // Every game droidtop changed goes back; one that cannot yet stays journaled for droidtop's next start.
            dev.droidtop.runtime.power.StandardPowerLayer.restoreAll(context, shell)
            true
        },
    )

    /** The items, in the order they are shown. */
    val items: List<DeviceSetupItem> get() = listOf(childProcesses, buttonPresses, autoPower)

    private fun ownService(context: Context) = ComponentName(context.packageName, AccessibilityKeyboard.SERVICE_CLASS).flattenToString()

    private fun secure(context: Context, key: String): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, key) }.getOrNull()

    /**
     * Allows restricted settings for droidtop (Android 13+) and adds its service to Android's enabled list, keeping
     * every service already there. Records what it found, for [turnOffButtonPresses]. Blocking: IO only.
     */
    internal fun turnOnButtonPresses(context: Context, shell: PrivilegedShell): Boolean {
        if (!canRun(shell)) return false
        val edit = prefs(context).edit()
        if (Build.VERSION.SDK_INT >= 33) {
            val before = shell.exec(listOf("appops", "get", context.packageName, RESTRICTED_OP))?.stdout
            edit.putString("button_presses.op_before", opModeName(before))
            shell.exec(listOf("appops", "set", context.packageName, RESTRICTED_OP, "allow"))
        }
        val list = secure(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val wasEnabled = secure(context, Settings.Secure.ACCESSIBILITY_ENABLED) == "1"
        val listed = shell.exec(
            listOf("settings", "put", "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, withService(list, ownService(context))),
        )?.exit == 0
        val enabled = listed && shell.exec(listOf("settings", "put", "secure", Settings.Secure.ACCESSIBILITY_ENABLED, "1"))?.exit == 0
        if (enabled) edit.putBoolean("button_presses.done", true).putBoolean("button_presses.was_enabled", wasEnabled)
        edit.apply()
        return enabled
    }

    /** Takes droidtop's service off Android's list and puts back what [turnOnButtonPresses] found. Blocking: IO only. */
    internal fun turnOffButtonPresses(context: Context, shell: PrivilegedShell): Boolean {
        if (!canRun(shell)) return false
        val p = prefs(context)
        val rest = withoutService(secure(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), ownService(context))
        var ok = shell.exec(listOf("settings", "put", "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, rest))?.exit == 0
        // Android's master switch goes back off only when droidtop turned it on and no other service is left.
        if (ok && rest.isEmpty() && !p.getBoolean("button_presses.was_enabled", true)) {
            ok = shell.exec(listOf("settings", "put", "secure", Settings.Secure.ACCESSIBILITY_ENABLED, "0"))?.exit == 0
        }
        p.getString("button_presses.op_before", null)?.takeIf { Build.VERSION.SDK_INT >= 33 }?.let { mode ->
            ok = ok && shell.exec(listOf("appops", "set", context.packageName, RESTRICTED_OP, mode))?.exit == 0
        }
        if (ok) p.edit().remove("button_presses.done").remove("button_presses.was_enabled").remove("button_presses.op_before").apply()
        return ok
    }

    /** [list] (Android's colon-separated enabled services) with [service] added once. Pure. */
    internal fun withService(list: String?, service: String): String =
        (entries(list) + service).distinct().joinToString(":")

    /** [list] without [service]. Pure. */
    internal fun withoutService(list: String?, service: String): String =
        entries(list).filterNot { it == service }.joinToString(":")

    private fun entries(list: String?) = list.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The mode `appops get <package> <op>` printed ("ACCESS_RESTRICTED_SETTINGS: deny; time=..."), as a name `appops
     * set` takes; "default" when it printed nothing usable (Android leaves an untouched op at its default). Pure.
     */
    internal fun opModeName(output: String?): String {
        val mode = output?.lineSequence()?.firstOrNull { it.trim().startsWith("$RESTRICTED_OP:") }
            ?.substringAfter(':')?.substringBefore(';')?.trim()
        return mode?.takeIf { it in OP_MODES } ?: "default"
    }

    private val OP_MODES = setOf("allow", "ignore", "deny", "default", "foreground")
}
