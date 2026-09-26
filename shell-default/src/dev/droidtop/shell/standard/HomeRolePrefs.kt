package dev.droidtop.shell.standard

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * Owns which of droidtop's own two `CATEGORY_HOME` activities is actually
 * enabled — [HomeTrampolineActivity] (Standard; forwards into
 * `com.android.launcher3.Launcher` once it decides Standard is the
 * target, docs/SPEC.md 2c) or [AlternativeLauncherActivity] (Alternative,
 * which forwards to a different installed launcher). Exactly one is ever
 * enabled at a time, mirroring `farmerbb/Taskbar`'s own real, shipping
 * `HomeActivity`/`HSLActivity` toggle (verified against its actual source
 * this session) — `PackageManager.setComponentEnabledSetting` overrides
 * the manifest's default `android:enabled` value at runtime, so both real
 * activities stay declared normally in the manifest and this is the only
 * thing that decides which one Android actually offers as a HOME
 * candidate.
 *
 * [HomeImplementation.NONE] means droidtop claims no HOME role at all —
 * both disabled — matching onboarding's "decide later" option (see
 * `docs/SPEC.md`'s Onboarding section).
 */
object HomeRolePrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_ALTERNATIVE_TARGET = "droidtop_alternative_launcher_target"

    private const val STANDARD_ACTIVITY = dev.droidtop.library.settings.Modes.LAUNCHER_ACTIVITY
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

    /**
     * Whether droidtop is the app Android opens on Home -- the real
     * answer, not whether one of droidtop's HOME activities is enabled.
     * Enabling one only makes droidtop a candidate: on Android 10+ Home is
     * a role the person grants, and on the Android 14 rig "droidtop's own
     * launcher" in setup left Pixel Launcher as Home while Global settings
     * read "On" (dq-onboard-01).
     */
    fun isDroidtopHome(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(android.app.role.RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                return roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
            }
        }
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /**
     * What to start, FOR A RESULT, to make droidtop the Home app: Android's
     * own Home role request on 10+ (it only works started for a result,
     * because it asks who is calling), else Android's Default home app
     * screen. Enable the HOME activity first ([setActiveHomeImplementation]):
     * the request offers only an enabled one.
     */
    fun homeRequestIntent(context: Context): android.content.Intent {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(android.app.role.RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                return roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME)
            }
        }
        // Below 10 there is no role request. When no Home app has been
        // chosen "Always" yet, Home itself resolves to Android's chooser,
        // which is the Home choice with droidtop in it: open that. Only when
        // another app is already the default does Android's settings screen
        // have to do it; on BlueStacks that screen is the Default apps list,
        // one level above the Home choice (rig, dq-onboard-02).
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        val noDefaultYet = resolved == null || resolved.activityInfo?.packageName == "android"
        return if (noDefaultYet) home else homeSettingsIntent()
    }

    /** Android's own Default home app screen, for a caller that cannot wait for a result. */
    fun homeSettingsIntent(): android.content.Intent =
        android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Puts droidtop's one icon on droidtop's own home screen, through the
     * launcher's own install queue (the path a newly installed app's icon
     * takes), so the way into Gaming or Desktop is on the screen the person
     * lands on rather than only in the drawer (docs/SPEC.md 2c). Only asks:
     * the launcher places it the next time its home screen resumes
     * ([placePendingIcon]).
     */
    fun placeDroidtopIcon(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ICON_PENDING, true)
            .apply()
    }

    /**
     * Called by the launcher when its home screen resumes: queues the icon
     * [placeDroidtopIcon] asked for, once. Queued from the launcher itself
     * because the queue places items only while the launcher exists; asked
     * for at the end of setup, in a process where droidtop's launcher was
     * not running (it was not even Home yet on the Android 14 rig), the icon
     * never arrived (dq-onboard-01).
     */
    @JvmStatic
    fun placePendingIcon(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ICON_PENDING, false)) return
        prefs.edit().putBoolean(KEY_ICON_PENDING, false).apply()
        runCatching {
            com.android.launcher3.model.ItemInstallQueue.INSTANCE.get(context)
                .queueItem(context.packageName, android.os.Process.myUserHandle())
        }.onFailure { android.util.Log.w("droidtop.HomeRole", "Could not queue droidtop's icon", it) }
    }

    private const val KEY_ICON_PENDING = "droidtop_home_icon_pending"

    fun setAlternativeTarget(context: Context, component: ComponentName) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ALTERNATIVE_TARGET, component.flattenToString())
            .apply()
    }
}
