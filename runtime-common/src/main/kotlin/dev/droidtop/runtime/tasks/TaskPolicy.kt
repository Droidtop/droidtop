package dev.droidtop.runtime.tasks

/**
 * One app Android is running, or that droidtop opened and has no reason to think is gone.
 * [displayId] is the screen its task is on (0 is the built-in one); [taskId] is known only
 * when the list came from a privileged source.
 */
data class RunningApp(
    val packageName: String,
    val label: String,
    val displayId: Int,
    val taskId: Int?,
    val visible: Boolean,
)

/**
 * How trustworthy a running-apps list is, so a surface can say so plainly.
 * [EXACT]: read from the system's own task list through a privileged provider.
 * [LAUNCHED_ONLY]: only the apps droidtop itself started; Android shows a plain app no more.
 */
enum class Fidelity { EXACT, LAUNCHED_ONLY }

data class RunningSnapshot(
    val apps: List<RunningApp>,
    val fidelity: Fidelity,
    /** One plain sentence about what this list cannot show or why a better source failed; null when nothing needs saying. */
    val note: String? = null,
)

/** What privileged helpers are running right now (a `priv.packages` and a `priv.shell` provider plugin). */
data class TaskPrivileges(val forceStop: Boolean, val shell: Boolean) {
    companion object {
        val NONE = TaskPrivileges(forceStop = false, shell = false)
    }
}

/** The ways droidtop can end another app, strongest first. */
enum class CloseStep {
    /** Ask a `priv.packages` provider (Shizuku, or a root provider) to force-stop the package: ends it for real. */
    FORCE_STOP,

    /** `ActivityManager.killBackgroundProcesses`: a normal permission, but Android decides, and it cannot be confirmed. */
    KILL_BACKGROUND,
}

/**
 * The task manager's rules, with no Android in them so they are unit-tested: what is protected, what
 * Clear all closes, when it asks first, and which close paths a given set of privileges has.
 * docs/SPEC.md, "The task manager".
 */
object TaskPolicy {
    /** Clear all asks first when it would close more than this many apps (owner, #252: "a short confirm if more than a few"). */
    const val CONFIRM_CLEAR_ALL_ABOVE = 3

    /** Enginehost hosts engine games: Clear all leaves it alone, because a game may be running in it (#252). Closing it by name is allowed. */
    const val ENGINEHOST_PACKAGE = "dev.enginehost"

    /** System surfaces nobody wants closed by a task manager. */
    private val SYSTEM_PROTECTED = setOf("android", "com.android.systemui")

    /**
     * Packages Clear all never closes: droidtop itself (its shell and the companion are its own tasks),
     * Enginehost, the system surfaces, the home app in use, and whatever the user marked in [userProtected].
     */
    fun protectedPackages(ownPackage: String, homePackages: Set<String>, userProtected: Set<String>): Set<String> =
        SYSTEM_PROTECTED + ownPackage + ENGINEHOST_PACKAGE + homePackages + userProtected

    /** Left out of the running-apps list itself: droidtop's own tasks, home apps and the system surfaces are not apps to manage. */
    fun hiddenFromList(ownPackage: String, homePackages: Set<String>): Set<String> =
        SYSTEM_PROTECTED + ownPackage + homePackages

    /** The apps Clear all would close, one per package, in list order. */
    fun clearAllTargets(apps: List<RunningApp>, protected: Set<String>): List<RunningApp> =
        apps.filter { it.packageName !in protected }.distinctBy { it.packageName }

    fun needsClearAllConfirm(targetCount: Int): Boolean = targetCount > CONFIRM_CLEAR_ALL_ABOVE

    /**
     * Which close paths exist for [privileges], in the order they are tried. The background kill is
     * always listed as the last, unconfirmable try: on the console's Android 13 it ends an app that
     * has really gone to the background and is documented to do nothing for other apps on later
     * releases, so its result is never reported as "closed".
     */
    fun closeSteps(privileges: TaskPrivileges): List<CloseStep> =
        buildList {
            if (privileges.forceStop) add(CloseStep.FORCE_STOP)
            add(CloseStep.KILL_BACKGROUND)
        }

    /** What a screen calls a display: the built-in one is 0, everything else is an attached screen. */
    fun displayLabel(displayId: Int): String = if (displayId == 0) "Built-in screen" else "Second screen"

    /** The display "move to the other screen" targets: the built-in screen for an app elsewhere, otherwise the first other display; null with one screen. */
    fun otherDisplay(current: Int, all: List<Int>): Int? =
        if (current != 0 && 0 in all) 0 else all.firstOrNull { it != current }

    /** The one sentence a Close or Clear all that could not confirm anything shows, saying what to enable. */
    const val ENABLE_HINT =
        "Android does not let droidtop end another app on its own. Enable the Shizuku plugin in Settings > Plugins to close apps for real."
}
