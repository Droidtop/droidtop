package dev.droidtop.shell.standard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * The one way into onboarding while it is unfinished (docs/SPEC.md 7b,
 * "Onboarding survives becoming Home").
 *
 * Every droidtop entry point asks here first: the HOME activities
 * (`com.android.launcher3.Launcher`, [AlternativeLauncherActivity]),
 * droidtop's one drawer icon (`LauncherGamesActivity`) and `MainActivity`.
 * While setup is unfinished each of them hands over to the onboarding that
 * is already running, at the step it was on, instead of drawing itself.
 *
 * The rig showed why a first-run check at process start was not enough
 * (dq-coordinator-24, finding 1): the moment droidtop became the Home app
 * -- picked in Android's chooser before droidtop was ever opened, or in
 * Settings > Default apps while onboarding sat at step 3 -- Android
 * started the launcher, which drew the Android home screen over the
 * unfinished setup. Nothing said setup was unfinished; Recents (labelled
 * "Games") and the drawer icon (which restarted at step 1) were the only
 * ways back.
 *
 * `:shell-default` cannot depend on `:app` (the graph is one-way), so the
 * activity is named by component, the same pattern [BackButtonMenu] uses.
 */
object OnboardingGate {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_COMPLETE = "droidtop_onboarding_complete"
    private const val ONBOARDING_ACTIVITY = "dev.droidtop.app.OnboardingActivity"

    /**
     * Set when the person chose "Leave setup" on onboarding's own Back
     * dialog. For the rest of this process the Home key shows the home
     * screen instead of dragging them back into setup; a deliberate tap on
     * droidtop's icon still resumes it, and the next process start asks
     * again. Leaving is a choice onboarding offers, so it has to be
     * honoured by the key that would otherwise undo it.
     */
    @Volatile
    private var leftThisProcess = false

    @JvmStatic
    fun isComplete(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_COMPLETE, false)

    /** Onboarding's "Leave" answer; see [leftThisProcess]. */
    @JvmStatic
    fun leave() {
        leftThisProcess = true
    }

    /**
     * Resumes the unfinished onboarding and returns true, or returns false
     * when there is nothing to resume and the caller should draw itself.
     *
     * [fromHome] is a Home-key arrival (the launcher or the Alternative
     * forwarder): those respect [leave], an icon tap does not.
     *
     * One instance, whichever route arrives: CLEAR_TOP with SINGLE_TOP
     * brings the running onboarding to the front of its task (delivering
     * the intent to it rather than stacking a second copy), and a fresh
     * instance, after the process died, resumes from the saved run
     * (`OnboardingProgress` in `:app`). Two copies stacked in one task was
     * a real bug of the first-run gate this replaces.
     */
    @JvmStatic
    @JvmOverloads
    fun resumeIfUnfinished(context: Context, fromHome: Boolean = false): Boolean {
        if (isComplete(context)) return false
        if (fromHome && leftThisProcess) return false
        leftThisProcess = false
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(context.packageName, ONBOARDING_ACTIVITY)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        context.startActivity(intent)
        return true
    }
}
