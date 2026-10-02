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
 */
class ElevatedShell(
    private val app: ElevatedBackend,
    private val plugin: ElevatedBackend,
    private val choice: () -> ElevatedChoice,
) : PrivilegedShell {
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

    override fun forceStop(packageName: String): ForceStopResult = target().forceStop(packageName)

    override fun exec(argv: List<String>): ShellOutput? = target().exec(argv)

    override fun grantPermission(packageName: String, permission: String): Boolean =
        target().grantPermission(packageName, permission)
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
