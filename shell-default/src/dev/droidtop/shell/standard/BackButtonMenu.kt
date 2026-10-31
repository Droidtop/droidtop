package dev.droidtop.shell.standard

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.ModeGate
import dev.droidtop.library.settings.Modes

/**
 * droidtop's mode switcher: "Android", the modes that are on, then "Modes and settings", "Reinitialize displays" and
 * "Close all apps" ([choices]).
 *
 * One switcher, several ways to open it, so no mode is reachable only by a gesture a newcomer cannot see
 * (docs/SPEC.md 2c, "Switching modes"):
 *
 * - a long-press of the back key (`Activity.onKeyLongPress(KeyEvent.KEYCODE_BACK, ...)`) in the launcher and in
 *   `MainActivity`, the one route from anywhere, though not every device delivers it;
 * - the Android home screen's own long-press menu ("droidtop modes");
 * - the Gaming Quick Menu's System tab ("Switch mode");
 * - the Desktop taskbar ("Modes");
 * - droidtop's games screen, when it is the only droidtop surface on.
 *
 * Every route opens it through [ModeSwitcher.open]: one translucent activity in `:app` (`ModeSwitcherActivity`)
 * draws the shell's own modal sheet over whatever is on screen, so the switcher looks and answers the pad the same
 * in every mode.
 */
object BackButtonMenu {
    private const val GLOBAL_SETTINGS_FRAGMENT = "app.murinelauncher.settings.SettingsGlobalFragment"
    const val EXTRA_MODE = "dev.droidtop.app.EXTRA_MODE"

    // Real, deep-link-only extras used by SettingsGamingFragment (same
    // module as this object) to reach :app's MainActivity/GamepadShell,
    // which itself depends on :shell-default and reads these back off this
    // same object -- the established real pattern for sharing an Intent
    // contract across the one-way :app -> shells dependency edge, matching
    // EXTRA_MODE above rather than a second, duplicated copy
    // of the same string literals.
    const val EXTRA_GAMING_START_SECTION = "dev.droidtop.app.EXTRA_GAMING_START_SECTION"
    const val EXTRA_GAMING_RESCAN = "dev.droidtop.app.EXTRA_GAMING_RESCAN"

    // Jumps straight into the Gaming shell's inline theme browser
    // (SettingsCatalogView -> ThemeBrowserScreen) -- the settings
    // catalog's "Browse themes" default fulfillment, used when that item
    // is activated from the unified Preference surface, which has no
    // in-shell browser of its own to open.
    const val EXTRA_GAMING_BROWSE_THEMES = "dev.droidtop.app.EXTRA_GAMING_BROWSE_THEMES"

    /**
     * Set by Launcher's HOME-press forwarding (Launcher.onNewIntent):
     * MainActivity re-runs its dual-screen role orchestration ("fix my
     * screens" -- Android mirrors a second display nothing presents on)
     * WITHOUT reclaiming a display the user launched an app onto. An
     * explicit shell entry (this menu's own Gaming item) omits it, which
     * is what clears that parked state -- see MainActivity.
     */
    const val EXTRA_DISPLAY_REINIT = "dev.droidtop.app.EXTRA_DISPLAY_REINIT"

    /**
     * Set on a DOUBLE-tap of home (Launcher.onNewIntent's press timing):
     * the HARD display reinit — re-asserts droidtop's surfaces on BOTH
     * displays regardless of what's running on them, clearing any parked
     * display. The single-press soft reinit above never covers a launched
     * app; this one deliberately does, per direction.
     */
    const val EXTRA_DISPLAY_REINIT_FORCE = "dev.droidtop.app.EXTRA_DISPLAY_REINIT_FORCE"

    /**
     * Set when the switcher sends the person to Gaming or Desktop: they asked for droidtop back, so a screen the
     * companion was left off by another app (the stock launcher after "Android") is the companion's again
     * (Droidtop/tracker#522). Unlike the reinit extras it covers no app droidtop launched.
     */
    const val EXTRA_DISPLAYS_BACK = "dev.droidtop.app.EXTRA_DISPLAYS_BACK"

    /** Opens the mode switcher over [activity] ([ModeSwitcher.open]). */
    @JvmStatic
    fun show(activity: Activity) = ModeSwitcher.open(activity)

    /** Whether "Android" must first ask to make droidtop the Home app ([ModeGate.androidAsksForHome]). */
    fun androidAsksForHome(context: Context): Boolean = ModeGate.androidAsksForHome(
        launcherOn = HomeRolePrefs.activeHomeImplementation(context) != HomeRolePrefs.HomeImplementation.NONE,
        droidtopIsHome = HomeRolePrefs.isDroidtopHome(context),
    )

    /**
     * One row of the switcher: [mode] for a mode's own row, null for the three that are not modes ("Modes and
     * settings", "Reinitialize displays", "Close all apps"), which the sheet sets apart below the modes.
     */
    class Choice(val label: String, val mode: Mode?, val choose: () -> Unit)

    /**
     * The switcher's rows, in order, each with what choosing it does. "Android" always: it is the home screen the Home
     * button opens, droidtop's or not; [askForHome], when given, runs instead while [androidAsksForHome] says it must
     * ask first. Then Desktop and Gaming while they are on ([ModeGate.switcherModes]); a mode that is off is not
     * listed. Then "Modes and settings", which opens Global settings, where every mode is switched on and off, so
     * turning one off is always reversible from here; "Reinitialize displays"; and "Close all apps". The rows are
     * drawn by the shell's own modal sheet (`ModeSwitcherSheet` in `:shell-gamepad`, hosted by
     * `dev.droidtop.app.ModeSwitcherActivity`), the same in every mode; this module cannot depend on that one, so it
     * hands over the list.
     */
    fun choices(context: Context, askForHome: (() -> Unit)? = null): List<Choice> {
        val homeImplementation = HomeRolePrefs.activeHomeImplementation(context)
        return buildList {
            for (mode in ModeGate.switcherModes(Modes.enabled)) {
                when (mode) {
                    Mode.LAUNCHER -> add(
                        Choice(Mode.LAUNCHER.label, mode) {
                            if (askForHome != null && androidAsksForHome(context)) askForHome() else openHome(context, homeImplementation)
                        },
                    )
                    Mode.DESKTOP -> add(Choice(Mode.DESKTOP.label, mode) { launchAppMode(context, Mode.DESKTOP) })
                    Mode.GAMING -> add(Choice(Mode.GAMING.label, mode) { launchAppMode(context, Mode.GAMING) })
                }
            }
            // Names the screen it opens: Global settings is where the modes live.
            add(Choice(context.getString(com.android.launcher3.R.string.mode_menu_settings), null) { openGlobalSettings(context) })
            // The hard display reinit, findable without the double tap of Home nobody is told about (reinitializeDisplays).
            add(Choice(context.getString(com.android.launcher3.R.string.mode_menu_reinit_displays), null) { reinitializeDisplays(context) })
            // The task manager's Clear all apps (docs/SPEC.md "The task manager", tracker#252): the way out of a stuck
            // app, in the one menu every mode and the home screen's long-press open.
            add(Choice(context.getString(com.android.launcher3.R.string.mode_menu_close_all), null) { ClearAllApps.open(context) })
        }
    }

    /**
     * Re-sends the existing HARD display reinit
     * ([EXTRA_DISPLAY_REINIT_FORCE], docs/SPEC.md 4c) that a double-tap of
     * Home already triggers, so it is reachable without knowing that
     * gesture exists. No [EXTRA_MODE]: [resolveMode] only changes the
     * active mode when that extra is present, so this leaves whichever
     * mode is already showing alone and only forces the display-role
     * orchestration to re-run and re-assert droidtop's surfaces.
     */
    fun reinitializeDisplays(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                setClassName(context.packageName, StandardComponents.MAIN)
                putExtra(EXTRA_DISPLAY_REINIT_FORCE, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /**
     * Opens the Android home screen droidtop holds -- its own launcher, or
     * the one "Alternative" forwards to -- and records it as the last mode.
     * The one way into Launcher mode: this menu's "Android" and the end of
     * onboarding both come here. The intent names the mode explicitly, so
     * the home activity shows itself instead of forwarding to the default
     * mode the way a Home press does ([Modes.homeTarget]).
     */
    fun openHome(context: Context, implementation: HomeRolePrefs.HomeImplementation) {
        Modes.setLastMode(context, Mode.LAUNCHER)
        context.startActivity(homeIntent(context, implementation))
    }

    /** What [openHome] starts, without recording the mode: for a notification that returns to the Android home screen. */
    @JvmStatic
    fun homeIntent(context: Context, implementation: HomeRolePrefs.HomeImplementation): Intent {
        val activityName = when (implementation) {
            HomeRolePrefs.HomeImplementation.DROIDTOP -> StandardComponents.LAUNCHER
            HomeRolePrefs.HomeImplementation.ALTERNATIVE -> StandardComponents.ALTERNATIVE
            HomeRolePrefs.HomeImplementation.NONE -> null
        }
        if (activityName == null || !HomeRolePrefs.isDroidtopHome(context)) {
            // droidtop's home activity is enabled but Android's Home opens
            // another app: "Android" is that app's home screen, which is
            // what the Home button shows. Starting droidtop's own launcher
            // there did nothing visible (rig, Android 14, dq-onboard-01).
            return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(context.packageName, activityName)
            putExtra(EXTRA_MODE, Mode.LAUNCHER.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Whether [intent] is an explicit request for the Android home screen
     * ([openHome]) rather than a Home press, which goes wherever
     * [Modes.homeTarget] says.
     */
    @JvmStatic
    fun isExplicitHome(intent: Intent?): Boolean = intent?.getStringExtra(EXTRA_MODE) == Mode.LAUNCHER.id

    private fun launchAppMode(context: Context, mode: Mode) {
        Modes.setLastMode(context, mode)
        context.startActivity(appModeIntent(context, mode.id).putExtra(EXTRA_DISPLAYS_BACK, true))
    }

    /**
     * The one way Standard starts an app-hosted mode's screen: `:app`'s MainActivity in its own task, on the mode
     * [modeId] (Droidtop/tracker#771). Callers add their own extras (the display reinit, displays back).
     */
    @JvmStatic
    fun appModeIntent(context: Context, modeId: String): Intent =
        Intent(Intent.ACTION_MAIN)
            .setClassName(context.packageName, StandardComponents.MAIN)
            .putExtra(EXTRA_MODE, modeId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Global settings, on the settings surface every mode shares. */
    @JvmStatic
    fun openGlobalSettings(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(context.packageName, "com.android.launcher3.settings.SettingsActivity")
                putExtra(":settings:fragment", GLOBAL_SETTINGS_FRAGMENT)
                // Settings has its own task: a fresh page, never the one
                // left open last time, and never on top of a shell.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        )
    }
}

/** Opens the mode switcher from anywhere; see [BackButtonMenu]. */
object ModeSwitcher {
    const val ACTIVITY = "dev.droidtop.app.ModeSwitcherActivity"

    @JvmStatic
    fun open(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(context.packageName, ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

/**
 * droidtop's own screens the launcher opens, by class name since `:shell-default` cannot depend on `:app`:
 * the Games grid (the home screen's menu) and the Quick Menu (the home screen's menu and gestures;
 * docs/SPEC.md, Launcher mode "Gestures").
 */
object DroidtopScreens {
    const val GAMES_ACTIVITY = "dev.droidtop.app.LauncherGamesActivity"
    const val QUICK_MENU_ACTIVITY = "dev.droidtop.app.QuickMenuActivity"

    @JvmStatic
    fun openGames(context: Context) {
        context.startActivity(
            Intent("dev.droidtop.app.action.SHOW_GAMES")
                .setClassName(context.packageName, GAMES_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @JvmStatic
    fun openQuickMenu(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .setClassName(context.packageName, QUICK_MENU_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
