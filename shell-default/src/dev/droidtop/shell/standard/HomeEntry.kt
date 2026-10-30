package dev.droidtop.shell.standard

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes
import dev.droidtop.library.theme.ThemeSafeMode
import dev.droidtop.runtime.HomeDisplays
import dev.droidtop.runtime.ShellPlacement

/**
 * The one Home decision (docs/SPEC.md 2c, Droidtop/tracker#771), for both of droidtop's HOME activities: Standard's
 * trampoline ([HomeTrampolineActivity]) and the forwarder to another launcher ([AlternativeLauncherActivity]). In
 * order:
 *
 * 1. unfinished setup comes first ([OnboardingGate], docs/SPEC.md 7b);
 * 2. Home on the companion's screen brings the companion back there (the owner, 2026-10-17: "Companion if it's that
 *    screen, main if it's that one");
 * 3. an explicit "Android" from the mode switcher ([BackButtonMenu.isExplicitHome]) is Standard, as asked;
 * 4. a crash-looping theme ([ThemeSafeMode]) is escaped to Gaming, and a home target that is not Standard
 *    ([Modes.homeTarget]) opens that mode, re-running the display roles; a second press within
 *    [DOUBLE_TAP_WINDOW_MS] forces it;
 * 5. else Standard.
 *
 * [route] starts whatever the first four need and answers [Route.HANDLED]; on [Route.STANDARD] the activity does its
 * own Standard step after [enterStandard].
 */
internal object HomeEntry {
    enum class Route { HANDLED, STANDARD }

    private const val DOUBLE_TAP_WINDOW_MS = 600L
    private const val TAG = "droidtop.ReturnToGame"

    // Process-lifetime: a double tap spans two activity instances, since every Home press creates a fresh one.
    @Volatile
    private var lastHomePressMs = 0L

    fun route(activity: Activity, intent: Intent?): Route {
        if (OnboardingGate.resumeIfUnfinished(activity, fromHome = true)) return Route.HANDLED

        // One press time, read before any branch, so a double tap is seen whichever way the first press went.
        val now = SystemClock.elapsedRealtime()
        val doubleTap = now - lastHomePressMs < DOUBLE_TAP_WINDOW_MS
        lastHomePressMs = now

        val here = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            activity.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
        } else {
            android.view.Display.DEFAULT_DISPLAY
        }
        if (HomeDisplays.targetFor(here) == HomeDisplays.Target.COMPANION) {
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_MAIN).setClassName(activity.packageName, StandardComponents.COMPANION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.ActivityOptions.makeBasic().setLaunchDisplayId(here).toBundle(),
                )
            }.onFailure { Log.w(TAG, "HOME on display $here: the companion did not start", it) }
            return Route.HANDLED
        }

        if (BackButtonMenu.isExplicitHome(intent)) return Route.STANDARD

        val target = if (ThemeSafeMode.active) Mode.GAMING.id else Modes.homeTarget(activity, intent)
        if (target == Mode.LAUNCHER.id) {
            Log.i(TAG, "HOME on display $here: to Standard")
            return Route.STANDARD
        }
        val redirect = BackButtonMenu.appModeIntent(activity, target)
            .putExtra(BackButtonMenu.EXTRA_DISPLAY_REINIT, true)
        if (doubleTap) redirect.putExtra(BackButtonMenu.EXTRA_DISPLAY_REINIT_FORCE, true)
        // Started where it belongs, once: on the default display and then moved by the screen placement, it was started
        // twice, and the second start covered what the first opened (Droidtop/tracker#824).
        val present = activity.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.displays?.map { it.displayId }?.toSet().orEmpty()
        val shellOn = ShellPlacement.launchDisplay(HomeDisplays.shellDisplayId, ShellPlacement.remembered(activity), present, here)
        Log.i(TAG, "HOME on display $here: to $target on display ${shellOn ?: here} (double tap $doubleTap)")
        val placed = shellOn != null && runCatching {
            activity.startActivity(redirect, android.app.ActivityOptions.makeBasic().setLaunchDisplayId(shellOn).toBundle())
        }.onFailure { Log.w(TAG, "HOME: display $shellOn refused the shell, starting it here", it) }.isSuccess
        // A refused display leaves it to the screen placement, as before.
        if (!placed) activity.startActivity(redirect)
        return Route.HANDLED
    }

    /**
     * Standard is the surface in use now, whichever launcher draws it: recorded for the second screen, which reads
     * it to show Standard's content (docs/SPEC.md 4c, "The Alternative forwarder keeps the second screen"), and the
     * secondary displays' home placement re-asserted ([reassertSecondaryDisplays]).
     */
    fun enterStandard(activity: Activity) {
        Modes.setLastMode(activity, Mode.LAUNCHER)
        reassertSecondaryDisplays(activity)
    }
}
