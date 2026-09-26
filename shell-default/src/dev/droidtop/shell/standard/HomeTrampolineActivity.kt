package dev.droidtop.shell.standard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes

/**
 * droidtop's real `CATEGORY_HOME` component for Standard (built
 * 2026-09-26, docs/SPEC.md 2c). `com.android.launcher3.Launcher` no
 * longer declares `CATEGORY_HOME` itself -- this tiny Activity does, so a
 * Home press is decided before any of Launcher3's own heavy `onCreate`
 * work (`setupViews()`, the `LauncherModel` bind) ever runs. Read is one
 * `SharedPreferences` lookup ([Modes.homeTarget], already shared with
 * [AlternativeLauncherActivity] and `Launcher.java`'s own safety-net copy
 * of this same decision) -- no disk scan, no database, nothing that could
 * itself take a visible moment.
 *
 * This is what removes the multi-second Standard frame a Home press used
 * to show even when the target was Gaming or Desktop: previously
 * Launcher3's own `onCreate` always ran to completion (its `onDestroy`
 * unconditionally dereferences fields only `onCreate`'s full body
 * assigns, so it could not bail out early without crashing) before its
 * `onStart` fired the redirect. Now, when the target is not Standard, this
 * Activity forwards straight into `MainActivity` and Launcher3 is never
 * constructed at all. When the target IS Standard, it forwards straight
 * into `com.android.launcher3.Launcher`, explicit component to explicit
 * component -- the same pattern [BackButtonMenu.openHome]'s own "Android"
 * choice already uses -- so Standard draws exactly as before.
 *
 * Every Home press recreates this Activity fresh (it always finishes
 * immediately, never shown), so the double-tap "hard display reinit"
 * detection that used to live in `Launcher.onNewIntent`'s own redirect
 * branch (re-asserting droidtop on both displays when a Home press finds
 * the target already showing) moves here, tracked the same way Launcher's
 * own copy did: a process-lifetime timestamp, not per-Activity state.
 * `Launcher.java`'s own copies of this decision (`onCreate`, `onStart`,
 * `onNewIntent`) are unreachable now that this Activity holds
 * `CATEGORY_HOME` -- left in place as a harmless no-op safety net rather
 * than rewritten, since Launcher3 is vendored (hook, don't rewrite) and
 * every one of its own internal callers already passes `isExplicitHome`
 * or a target that already equals Standard, so the copies never disagree
 * with this one.
 */
class HomeTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Unfinished setup comes first, whichever home activity is live
        // (docs/SPEC.md 7b).
        if (OnboardingGate.resumeIfUnfinished(this, fromHome = true)) {
            finish()
            return
        }

        if (BackButtonMenu.isExplicitHome(intent)) {
            // An explicit "Android" from the mode switcher (or a stray
            // relaunch of this exact component) never redirects; forward
            // straight into Standard as asked.
            forwardToStandard()
            return
        }

        val target = Modes.homeTarget(this)
        if (target != Mode.LAUNCHER.id) {
            val nowMs = SystemClock.elapsedRealtime()
            val doubleTap = nowMs - lastHomePressMs < DOUBLE_TAP_WINDOW_MS
            lastHomePressMs = nowMs
            val redirect = Intent(Intent.ACTION_MAIN).apply {
                setClassName(packageName, MAIN_ACTIVITY)
                putExtra(BackButtonMenu.EXTRA_MODE, target)
                putExtra(BackButtonMenu.EXTRA_DISPLAY_REINIT, true)
                if (doubleTap) putExtra(BackButtonMenu.EXTRA_DISPLAY_REINIT_FORCE, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(redirect)
            finish()
            return
        }

        lastHomePressMs = SystemClock.elapsedRealtime()
        forwardToStandard()
    }

    private fun forwardToStandard() {
        val redirect = Intent(Intent.ACTION_MAIN).apply {
            setClassName(packageName, STANDARD_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(redirect)
        finish()
    }

    private companion object {
        const val MAIN_ACTIVITY = "dev.droidtop.app.MainActivity"
        const val STANDARD_ACTIVITY = "com.android.launcher3.Launcher"
        const val DOUBLE_TAP_WINDOW_MS = 600L

        // Process-lifetime, like Launcher.java's own sDroidtopLastHomePressMs:
        // double-tap detection has to span separate Activity instances,
        // since every Home press creates a fresh one of these.
        @Volatile
        var lastHomePressMs = 0L
    }
}
