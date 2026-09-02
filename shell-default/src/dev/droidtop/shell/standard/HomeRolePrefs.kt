package dev.droidtop.shell.standard

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * Owns which of droidtop's own two `CATEGORY_HOME` activities is actually
 * enabled — `com.android.launcher3.Launcher` (Standard) or
 * [AlternativeLauncherActivity] (Alternative, which forwards to a
 * different installed launcher). Exactly one is ever enabled at a time,
 * mirroring `farmerbb/Taskbar`'s own real, shipping `HomeActivity`/
 * `HSLActivity` toggle (verified against its actual source this session) —
 * `PackageManager.setComponentEnabledSetting` overrides the manifest's
 * default `android:enabled` value at runtime, so both real activities stay
 * declared normally in the manifest and this is the only thing that
 * decides which one Android actually offers as a HOME candidate.
 *
 * [HomeImplementation.NONE] means droidtop claims no HOME role at all —
 * both disabled — matching onboarding's "decide later" option (see
 * `docs/SPEC.md`'s Onboarding section).
 */
object HomeRolePrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_ALTERNATIVE_TARGET = "droidtop_alternative_launcher_target"

    private const val STANDARD_ACTIVITY = "com.android.launcher3.Launcher"
    private const val ALTERNATIVE_ACTIVITY = "dev.droidtop.shell.standard.AlternativeLauncherActivity"

    enum class HomeImplementation { STANDARD, ALTERNATIVE, NONE }

    fun setActiveHomeImplementation(context: Context, implementation: HomeImplementation) {
        val pm = context.packageManager
        val standardEnabled = implementation == HomeImplementation.STANDARD
        val alternativeEnabled = implementation == HomeImplementation.ALTERNATIVE
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, STANDARD_ACTIVITY),
            enabledState(standardEnabled),
            PackageManager.DONT_KILL_APP,
        )
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, ALTERNATIVE_ACTIVITY),
            enabledState(alternativeEnabled),
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun enabledState(enabled: Boolean): Int =
        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    /** Reads real current component state -- not a separately-tracked flag that could drift from it. */
    fun activeHomeImplementation(context: Context): HomeImplementation {
        val pm = context.packageManager
        return when {
            isEnabled(pm, context.packageName, ALTERNATIVE_ACTIVITY) -> HomeImplementation.ALTERNATIVE
            isEnabled(pm, context.packageName, STANDARD_ACTIVITY) -> HomeImplementation.STANDARD
            else -> HomeImplementation.NONE
        }
    }

    private fun isEnabled(pm: PackageManager, packageName: String, activity: String): Boolean {
        val state = pm.getComponentEnabledSetting(ComponentName(packageName, activity))
        // DEFAULT means "whatever the manifest says" -- Standard's own
        // manifest default is android:enabled="true", Alternative's is
        // "false", matching pre-onboarding/legacy behavior.
        return when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> activity == STANDARD_ACTIVITY
        }
    }

    /** The launcher [AlternativeLauncherActivity] forwards to -- null if never configured. */
    fun alternativeTarget(context: Context): ComponentName? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ALTERNATIVE_TARGET, null)
            ?.let { ComponentName.unflattenFromString(it) }

    fun setAlternativeTarget(context: Context, component: ComponentName) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ALTERNATIVE_TARGET, component.flattenToString())
            .apply()
    }
}
