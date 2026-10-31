package dev.droidtop.runtime.tasks

import android.content.Context

/** Whether one source of elevated access can be used right now. */
enum class BackendState {
    /** Not there: no Shizuku binder, or no running provider plugin. */
    ABSENT,

    /**
     * There, but not usable until the person acts: droidtop has not been allowed in the Shizuku app yet, or the Shizuku
     * provider plugin is installed and not yet approved and turned on.
     */
    NEEDS_PERMISSION,

    /**
     * Running, but nothing privileged answers behind it (Droidtop/tracker#851): the Shizuku provider plugin with no
     * Shizuku app running, so every command it is asked for fails. Never offered as the active backend.
     */
    UNBACKED,

    READY,
}

/** The ways droidtop reaches elevated actions (docs/SPEC.md "The task manager"). */
enum class ElevatedBackendId(val id: String, val label: String) {
    /** The official Shizuku app, or Sui, through the Shizuku API in droidtop's own process. */
    SHIZUKU_APP("shizuku_app", "Shizuku app"),

    /** The official Shizuku provider plugin, through the plugin broker. */
    SHIZUKU_PLUGIN("shizuku_plugin", "Shizuku plugin"),

    /**
     * Android's own wireless debugging, which droidtop pairs with once and then talks to as `adb` does, as the shell user
     * (docs/SPEC.md "Wireless debugging", Droidtop/tracker#816). Android 11 and later.
     */
    WIRELESS_DEBUGGING("wireless_debugging", "Wireless debugging"),
}

/** What the user picked: a backend, Auto, or no elevated access at all. */
enum class ElevatedChoice(val id: String, val label: String) {
    AUTO("auto", "Auto"),
    SHIZUKU_APP("shizuku_app", "Shizuku app"),
    SHIZUKU_PLUGIN("shizuku_plugin", "Shizuku plugin"),
    WIRELESS_DEBUGGING("wireless_debugging", "Wireless debugging"),
    OFF("off", "Off"),
    ;

    companion object {
        fun fromId(id: String?): ElevatedChoice = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** A source of elevated actions that can say whether it is usable, so [ElevatedShell] can pick between them. */
interface ElevatedBackend : PrivilegedShell {
    /** Cheap: a binder ping or a registry lookup, never a command. */
    fun state(): BackendState

    /** Whether the source is installed even though it is not running, so a reason can say "start it" instead of "get it". */
    fun installed(): Boolean = state() != BackendState.ABSENT
}

/** The pure choice between the two backends; everything that decides lives here so it can be tested. */
object ElevatedAccess {
    /**
     * The backend [choice] means right now, or null for none. Auto takes the first one that is [BackendState.READY],
     * the Shizuku app before the plugin (it is updated by its own developers and works as it normally would). A
     * named backend is used while it is there at all, even before droidtop is allowed in it, and is never swapped
     * for the other one behind the user's back. Off is always none.
     */
    fun resolve(
        choice: ElevatedChoice,
        app: BackendState,
        plugin: BackendState,
        wireless: BackendState = BackendState.ABSENT,
    ): ElevatedBackendId? = when (choice) {
        ElevatedChoice.OFF -> null
        ElevatedChoice.SHIZUKU_APP -> ElevatedBackendId.SHIZUKU_APP.takeIf { app != BackendState.ABSENT }
        // A plugin with nothing behind it is never claimed: its reason says so instead (Droidtop/tracker#851).
        ElevatedChoice.SHIZUKU_PLUGIN -> ElevatedBackendId.SHIZUKU_PLUGIN.takeIf { plugin != BackendState.ABSENT && plugin != BackendState.UNBACKED }
        ElevatedChoice.WIRELESS_DEBUGGING -> ElevatedBackendId.WIRELESS_DEBUGGING.takeIf { wireless != BackendState.ABSENT }
        // Wireless debugging last: it is there only while its switch is on, and Shizuku, when running, needs nothing.
        ElevatedChoice.AUTO -> when {
            app == BackendState.READY -> ElevatedBackendId.SHIZUKU_APP
            plugin == BackendState.READY -> ElevatedBackendId.SHIZUKU_PLUGIN
            wireless == BackendState.READY -> ElevatedBackendId.WIRELESS_DEBUGGING
            else -> null
        }
    }

    /** Where the person changes the source of elevated access; every reason below names it. */
    const val SETTINGS_PLACE = "Settings > Accounts and sources > Plugins and integrations"

    private const val NOT_ALLOWED = "droidtop is not allowed in Shizuku yet: $SETTINGS_PLACE > Allow droidtop in Shizuku"

    /** Paired, but Android's switch is off: it turns itself off on another network and after a restart. */
    const val WIRELESS_OFF = "Wireless debugging is off: turn it on in Developer options, then try again"

    private const val PLUGIN_NOT_APPROVED = "The Shizuku plugin is installed but not approved and turned on yet: open it in $SETTINGS_PLACE"

    /** The plugin runs, but the Shizuku app it bridges to does not (Droidtop/tracker#851): the person's one step. */
    const val PLUGIN_UNBACKED =
        "The Shizuku plugin is on, but nothing is behind it: the Shizuku app isn't running. Install and start Shizuku, or pair wireless debugging in $SETTINGS_PLACE"

    /**
     * Why [choice] gives no elevated access with the backends in these states, or null when it does
     * ([ElevatedShell.unavailableReason]). Each sentence is the real state and the one step that changes it: Shizuku
     * missing, installed but not running, running but not allowing droidtop (the console case behind build 1649), or the
     * plugin installed and waiting for approval (console, build 1773, where Kill said to add a plugin that was added).
     * [appInstalled] is whether the Shizuku app is on the device even though it is not running.
     */
    fun unavailableReason(
        choice: ElevatedChoice,
        app: BackendState,
        plugin: BackendState,
        appInstalled: Boolean = false,
        wireless: BackendState = BackendState.ABSENT,
    ): String? = when (choice) {
        ElevatedChoice.OFF -> "Elevated access is Off: $SETTINGS_PLACE > Elevated access"
        ElevatedChoice.SHIZUKU_APP -> when (app) {
            BackendState.READY -> null
            BackendState.NEEDS_PERMISSION -> NOT_ALLOWED
            BackendState.ABSENT, BackendState.UNBACKED -> "The Shizuku app is not running: start it, or pick another source in $SETTINGS_PLACE > Elevated access"
        }
        ElevatedChoice.SHIZUKU_PLUGIN -> when (plugin) {
            BackendState.READY -> null
            BackendState.NEEDS_PERMISSION -> PLUGIN_NOT_APPROVED
            BackendState.UNBACKED -> PLUGIN_UNBACKED
            BackendState.ABSENT -> "The Shizuku plugin picked in $SETTINGS_PLACE > Elevated access is not installed or not running"
        }
        ElevatedChoice.WIRELESS_DEBUGGING -> when (wireless) {
            BackendState.READY -> null
            BackendState.NEEDS_PERMISSION -> WIRELESS_OFF
            BackendState.ABSENT, BackendState.UNBACKED -> "droidtop isn't paired with wireless debugging yet: $SETTINGS_PLACE > Wireless debugging"
        }
        ElevatedChoice.AUTO -> when {
            app == BackendState.READY || plugin == BackendState.READY || wireless == BackendState.READY -> null
            app == BackendState.NEEDS_PERMISSION -> NOT_ALLOWED
            plugin == BackendState.NEEDS_PERMISSION -> PLUGIN_NOT_APPROVED
            wireless == BackendState.NEEDS_PERMISSION -> WIRELESS_OFF
            plugin == BackendState.UNBACKED -> PLUGIN_UNBACKED
            appInstalled -> "Shizuku is installed but not running: open the Shizuku app and start it"
            else -> "droidtop can only ask Android to close apps. Install Shizuku, or add the Shizuku plugin in $SETTINGS_PLACE"
        }
    }

    /**
     * The choices the row offers: only what is actually there. With neither backend present the list is empty and
     * the row is not drawn. Off and Auto appear as soon as there is anything to choose between.
     */
    fun options(app: BackendState, plugin: BackendState, wireless: BackendState = BackendState.ABSENT): List<ElevatedChoice> {
        val present = buildList {
            if (app != BackendState.ABSENT) add(ElevatedChoice.SHIZUKU_APP)
            if (plugin != BackendState.ABSENT) add(ElevatedChoice.SHIZUKU_PLUGIN)
            if (wireless != BackendState.ABSENT) add(ElevatedChoice.WIRELESS_DEBUGGING)
        }
        return if (present.isEmpty()) emptyList() else listOf(ElevatedChoice.AUTO) + present + ElevatedChoice.OFF
    }
}

/**
 * The one [PrivilegedShell] the rest of the app asks. It forwards to whichever backend [ElevatedAccess.resolve]
 * picks, and to nothing when none is picked, so a caller never learns which backend served it and a privileged
 * control hides itself exactly when [capabilities] is [TaskPrivileges.NONE].
 *
 * It is also the one gate for risky actions (docs/SPEC.md "Risky actions"): granting a permission or an appop, and
 * writing a file, are refused here unless [risk] allows the class, whichever surface asks.
 */
class ElevatedShell(
    private val app: ElevatedBackend,
    private val plugin: ElevatedBackend,
    private val choice: () -> ElevatedChoice,
    private val wireless: ElevatedBackend = NoElevatedBackend,
) : PrivilegedShell {
    /** Who says a class of risky action is allowed: the person's switches. Replaceable only so a test can decide. */
    var risk: RiskyGate = RiskyActions

    fun active(): ElevatedBackendId? = ElevatedAccess.resolve(choice(), app.state(), plugin.state(), wireless.state())

    /** The choices to show, from the live state of both backends. Not for the main thread: it pings binders. */
    fun options(): List<ElevatedChoice> = ElevatedAccess.options(app.state(), plugin.state(), wireless.state())

    private fun target(): PrivilegedShell = when (active()) {
        ElevatedBackendId.SHIZUKU_APP -> app
        ElevatedBackendId.SHIZUKU_PLUGIN -> plugin
        ElevatedBackendId.WIRELESS_DEBUGGING -> wireless
        null -> NoPrivilegedOps
    }

    override fun capabilities(): TaskPrivileges = target().capabilities()

    override fun available(): TaskPrivileges = capabilities()

    /** Only the Shizuku app's binder arrives late; the plugin backend is a registry lookup with nothing to wait for. */
    override fun connect(timeoutMs: Long) {
        when (choice()) {
            ElevatedChoice.OFF, ElevatedChoice.SHIZUKU_PLUGIN, ElevatedChoice.WIRELESS_DEBUGGING -> Unit
            ElevatedChoice.AUTO, ElevatedChoice.SHIZUKU_APP -> app.connect(timeoutMs)
        }
    }

    override fun unavailableReason(): String? =
        ElevatedAccess.unavailableReason(choice(), app.state(), plugin.state(), app.installed(), wireless.state())

    override fun forceStop(packageName: String): ForceStopResult = target().forceStop(packageName)

    override fun exec(argv: List<String>): ShellOutput? = target().exec(argv)

    override fun grantPermission(packageName: String, permission: String): Boolean =
        risk.allows(RiskyClass.GRANT_ACCESS) && target().grantPermission(packageName, permission)

    override fun setAppOp(packageName: String, op: String, mode: String): Boolean =
        risk.allows(RiskyClass.GRANT_ACCESS) && op.matches(APPOP_NAME) && mode in APPOP_MODES &&
            target().setAppOp(packageName, op, mode)

    /**
     * A long-lived process for the rooted desktop stack. A named backend is used alone, Off is none. Auto asks the
     * provider plugin first, because it answers only while a provider holds root ([ElevatedBackend.spawn] of the
     * plugin backend asks for root level), and falls back to the Shizuku app, which runs whatever it is: root
     * when Shizuku was started with root or is Sui, the shell user otherwise (RootProcess.access tells them apart).
     * So a root provider plugin carries the desktop wherever there is one, and the app's binder everywhere else.
     */
    override fun spawn(argv: List<String>): Process? = when (choice()) {
        ElevatedChoice.OFF -> null
        ElevatedChoice.SHIZUKU_APP -> app.spawn(argv)
        ElevatedChoice.SHIZUKU_PLUGIN -> plugin.spawn(argv)
        // The shell user over wireless debugging is never root, which is what a long-lived spawn is for.
        ElevatedChoice.WIRELESS_DEBUGGING -> null
        ElevatedChoice.AUTO -> plugin.spawn(argv) ?: app.spawn(argv)
    }

    override fun readFile(path: String): ByteArray? = if (ElevatedFiles.allowed(path)) target().readFile(path) else null

    override fun writeFile(path: String, data: ByteArray): Boolean =
        risk.allows(RiskyClass.OTHER_APP_FILES) && ElevatedFiles.allowed(path) && data.size <= ElevatedFiles.MAX_WRITE_BYTES &&
            target().writeFile(path, data)
}

/** The user's pick, kept in droidtop's own preferences and read once. */
object ElevatedChoicePrefs {
    private const val PREFS = "task_manager"
    private const val KEY = "elevated_access"

    @Volatile
    private var cached: ElevatedChoice? = null

    /** Reads the preferences file on first use only: call off the main thread. */
    fun get(context: Context): ElevatedChoice =
        cached ?: ElevatedChoice.fromId(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null),
        ).also { cached = it }

    fun set(context: Context, choice: ElevatedChoice) {
        cached = choice
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, choice.id).apply()
    }
}

/** A backend that is never there: [ElevatedShell]'s third backend until one is installed, and in tests. */
object NoElevatedBackend : ElevatedBackend, PrivilegedShell by NoPrivilegedOps {
    override fun state(): BackendState = BackendState.ABSENT
}
