package dev.droidtop.runtime.tasks

import android.content.Context

/** Whether one source of elevated access can be used right now. */
enum class BackendState {
    /** Not there: no Shizuku binder, or no running provider plugin. */
    ABSENT,

    /** There, but droidtop has not been allowed in it yet. */
    NEEDS_PERMISSION,

    READY,
}

/** The two ways droidtop reaches elevated actions (docs/SPEC.md "The task manager"). */
enum class ElevatedBackendId(val id: String, val label: String) {
    /** The official Shizuku app, or Sui, through the Shizuku API in droidtop's own process. */
    SHIZUKU_APP("shizuku_app", "Shizuku app"),

    /** The official Shizuku provider plugin, through the plugin broker. */
    SHIZUKU_PLUGIN("shizuku_plugin", "Shizuku plugin"),
}

/** What the user picked: a backend, Auto, or no elevated access at all. */
enum class ElevatedChoice(val id: String, val label: String) {
    AUTO("auto", "Auto"),
    SHIZUKU_APP("shizuku_app", "Shizuku app"),
    SHIZUKU_PLUGIN("shizuku_plugin", "Shizuku plugin"),
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
}

/** The pure choice between the two backends; everything that decides lives here so it can be tested. */
object ElevatedAccess {
    /**
     * The backend [choice] means right now, or null for none. Auto takes the first one that is [BackendState.READY],
     * the Shizuku app before the plugin (it is updated by its own developers and works as it normally would). A
     * named backend is used while it is there at all, even before droidtop is allowed in it, and is never swapped
     * for the other one behind the user's back. Off is always none.
     */
    fun resolve(choice: ElevatedChoice, app: BackendState, plugin: BackendState): ElevatedBackendId? = when (choice) {
        ElevatedChoice.OFF -> null
        ElevatedChoice.SHIZUKU_APP -> ElevatedBackendId.SHIZUKU_APP.takeIf { app != BackendState.ABSENT }
        ElevatedChoice.SHIZUKU_PLUGIN -> ElevatedBackendId.SHIZUKU_PLUGIN.takeIf { plugin != BackendState.ABSENT }
        ElevatedChoice.AUTO -> when {
            app == BackendState.READY -> ElevatedBackendId.SHIZUKU_APP
            plugin == BackendState.READY -> ElevatedBackendId.SHIZUKU_PLUGIN
            else -> null
        }
    }

    /** Where the person changes the source of elevated access; every reason below names it. */
    const val SETTINGS_PLACE = "Settings > Accounts and sources > Plugins and integrations"

    private const val NOT_ALLOWED = "droidtop is not allowed in Shizuku yet: $SETTINGS_PLACE > Allow droidtop in Shizuku"

    /**
     * Why [choice] gives no elevated access with the backends in these states, or null when it does
     * ([ElevatedShell.unavailableReason]). The Shizuku app running over ADB without having allowed droidtop is the
     * likely console case (build 1649: Quick Menu Kill said only "Not confirmed" three times while Shizuku ran).
     */
    fun unavailableReason(choice: ElevatedChoice, app: BackendState, plugin: BackendState): String? = when (choice) {
        ElevatedChoice.OFF -> "Elevated access is Off: $SETTINGS_PLACE > Elevated access"
        ElevatedChoice.SHIZUKU_APP -> when (app) {
            BackendState.READY -> null
            BackendState.NEEDS_PERMISSION -> NOT_ALLOWED
            BackendState.ABSENT -> "The Shizuku app is not running: start it, or pick another source in $SETTINGS_PLACE > Elevated access"
        }
        ElevatedChoice.SHIZUKU_PLUGIN ->
            if (plugin == BackendState.READY) null
            else "The Shizuku plugin picked in $SETTINGS_PLACE > Elevated access is not running"
        ElevatedChoice.AUTO -> when {
            app == BackendState.READY || plugin == BackendState.READY -> null
            app == BackendState.NEEDS_PERMISSION -> NOT_ALLOWED
            else -> "Shizuku is not running: start the Shizuku app, or add the Shizuku plugin"
        }
    }

    /**
     * The choices the row offers: only what is actually there. With neither backend present the list is empty and
     * the row is not drawn. Off and Auto appear as soon as there is anything to choose between.
     */
    fun options(app: BackendState, plugin: BackendState): List<ElevatedChoice> {
        val present = buildList {
            if (app != BackendState.ABSENT) add(ElevatedChoice.SHIZUKU_APP)
            if (plugin != BackendState.ABSENT) add(ElevatedChoice.SHIZUKU_PLUGIN)
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
) : PrivilegedShell {
    /** Who says a class of risky action is allowed: the person's switches. Replaceable only so a test can decide. */
    var risk: RiskyGate = RiskyActions

    fun active(): ElevatedBackendId? = ElevatedAccess.resolve(choice(), app.state(), plugin.state())

    /** The choices to show, from the live state of both backends. Not for the main thread: it pings binders. */
    fun options(): List<ElevatedChoice> = ElevatedAccess.options(app.state(), plugin.state())

    private fun target(): PrivilegedShell = when (active()) {
        ElevatedBackendId.SHIZUKU_APP -> app
        ElevatedBackendId.SHIZUKU_PLUGIN -> plugin
        null -> NoPrivilegedOps
    }

    override fun capabilities(): TaskPrivileges = target().capabilities()

    override fun available(): TaskPrivileges = capabilities()

    /** Only the Shizuku app's binder arrives late; the plugin backend is a registry lookup with nothing to wait for. */
    override fun connect(timeoutMs: Long) {
        when (choice()) {
            ElevatedChoice.OFF, ElevatedChoice.SHIZUKU_PLUGIN -> Unit
            ElevatedChoice.AUTO, ElevatedChoice.SHIZUKU_APP -> app.connect(timeoutMs)
        }
    }

    override fun unavailableReason(): String? = ElevatedAccess.unavailableReason(choice(), app.state(), plugin.state())

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
