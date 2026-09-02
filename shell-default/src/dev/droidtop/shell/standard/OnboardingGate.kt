package dev.droidtop.shell.standard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicBoolean
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * First-run gate for droidtop's own onboarding flow (welcome + games-folder
 * picker) -- real, concrete gap this closes: before this, there was no way
 * for a user to point droidtop at their actual game files short of `adb
 * push`ing into the app's private external-files dir (confirmed empty on a
 * real test device this session). `:shell-default` can't depend on `:app`
 * (see `:app`'s build.gradle.kts -- the dependency graph is one-way, `:app`
 * depends on the shells, never the reverse), so -- same established
 * pattern as [BackButtonMenu]'s Standard/Desktop/Handheld launches and
 * `:shell-gamepad`'s Settings launch -- this starts `:app`'s
 * OnboardingActivity by explicit component name rather than a typed Intent.
 */
object OnboardingGate {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_COMPLETE = "droidtop_onboarding_complete"
    private const val ONBOARDING_ACTIVITY = "dev.droidtop.app.OnboardingActivity"

    /**
     * Real bug this closes, confirmed on a real device: [launchIfNeeded] is
     * called from both `LauncherApplication.onCreate()` (this whole APK's
     * single shared Application class, so it runs once per process
     * regardless of which Activity actually launches first) AND `:app`'s
     * `MainActivity.onCreate()` -- on a cold start neither call sees the
     * other's in-flight launch (the `KEY_COMPLETE` pref is still false for
     * both, since the user hasn't finished onboarding yet), so both fire
     * and TWO `OnboardingActivity` instances stack in the same task. A user
     * who then completes the first instance lands back on a second, fresh
     * one still sitting at WELCOME -- confirmed via `dumpsys activity
     * activities` showing two distinct `OnboardingActivity` `ActivityRecord`s
     * in one task. A per-process in-memory latch (not just the on-disk
     * pref, which both racing calls read as equally stale) is what actually
     * closes this -- first caller wins, second is a no-op regardless of
     * timing.
     */
    private val launched = AtomicBoolean(false)

    // Called from LauncherApplication.java (Java can't call a Kotlin
    // object's member function as a static method without this -- same
    // reason BackButtonMenu.show is @JvmStatic).
    @JvmStatic
    fun launchIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_COMPLETE, false)) return
        if (!launched.compareAndSet(false, true)) return

        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(context.packageName, ONBOARDING_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
