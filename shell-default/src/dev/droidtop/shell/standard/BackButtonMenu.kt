package dev.droidtop.shell.standard

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.ModeGate
import dev.droidtop.library.settings.Modes

/**
 * droidtop's mode switcher — Android, Desktop, Gaming, or Settings.
 *
 * One switcher, several ways to open it, so no mode is reachable only by a
 * gesture a newcomer cannot see (docs/SPEC.md 2c, "Switching modes"):
 *
 * - a long-press of the back key (`Activity.onKeyLongPress(
 *   KeyEvent.KEYCODE_BACK, ...)`) in the launcher and in `MainActivity`;
 * - the Android home screen's own long-press menu ("droidtop modes");
 * - the Gaming Quick Menu's System tab ("Switch mode");
 * - the Desktop taskbar ("Modes");
 * - droidtop's games screen, when it is the only droidtop surface on.
 *
 * All but the first open it through [ModeSwitcher.open], which hosts it in
 * [ModeSwitcherActivity] so a surface with no Activity of its own to hand
 * (a settings catalog row, a Compose screen in another module) opens the
 * same dialog.
 *
 * The long press stays because it is the one route from anywhere, but on
 * its own it failed the rig: BlueStacks never delivers a long-pressed Back,
 * and a newcomer is never told it exists (dq-coordinator-24, finding 3).
 * On gesture navigation, whether a held back-swipe reaches onKeyLongPress
 * at all is OS-version/OEM-dependent.
 */
object BackButtonMenu {
    private const val APP_MAIN_ACTIVITY = "dev.droidtop.app.MainActivity"
    private const val STANDARD_LAUNCHER_ACTIVITY = "com.android.launcher3.Launcher"
    private const val ALTERNATIVE_LAUNCHER_ACTIVITY = "dev.droidtop.shell.standard.AlternativeLauncherActivity"
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
     * "Android" is always offered: it is the home screen the Home button
     * opens, droidtop's or not. A mode that is off is not listed; "Settings" opens
     * Global settings, where every mode is switched on and off, so turning
     * a mode off is always reversible from here (dq-coordinator-23, F5: it
     * used to open the launcher's Home settings, which have no Modes, and
     * recovering Gaming took a data clear).
     */
    @JvmStatic
    @JvmOverloads
    fun show(activity: Activity, onDismiss: (() -> Unit)? = null) {
        val homeImplementation = HomeRolePrefs.activeHomeImplementation(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        // Real, individually focusable Views chained in one vertical
        // LinearLayout, not a stock AlertDialog list: the ListView the
        // dialog used to build its rows from left D-pad focus stuck on the
        // first row no matter how many times Down was pressed, and a tap on
        // "Gaming" restarted MainActivity without ever landing on the
        // Gaming shell (rig, dq-modefix-01, BlueStacks/Android 9). A plain
        // LinearLayout's own focus search -- the same mechanism every other
        // droidtop screen already relies on for pad navigation -- moves
        // between real sibling Views far more reliably across Android
        // versions than a ListView's internal selection tracking, and each
        // row's own click closure captures its mode directly instead of
        // looking an index back up in a parallel list.
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
        }
        root.addView(
            TextView(activity).apply {
                text = "Switch mode"
                textSize = 20f
                setTextColor(TITLE_COLOR)
                setPadding(dp(8), 0, dp(8), dp(16))
            },
        )

        var dialog: AlertDialog? = null
        val rows = mutableListOf<TextView>()
        fun addRow(label: String, onSelect: () -> Unit) {
            rows += TextView(activity).apply {
                text = label
                textSize = 17f
                setTextColor(ROW_COLOR)
                isFocusable = true
                isClickable = true
                background = activity.getDrawable(com.android.launcher3.R.drawable.droidtop_list_selector)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setOnClickListener {
                    onSelect()
                    dialog?.dismiss()
                }
                root.addView(this)
            }
        }

        // Same rows, same order [ModeGate.switcherModes] already gives the
        // rest of droidtop -- "Android" always, Desktop/Gaming only while
        // enabled -- plus this switcher's own two fixed actions.
        for (mode in ModeGate.switcherModes(Modes.enabled)) {
            when (mode) {
                Mode.LAUNCHER -> addRow(Mode.LAUNCHER.label) { openHome(activity, homeImplementation) }
                Mode.DESKTOP -> addRow(Mode.DESKTOP.label) { launchAppMode(activity, Mode.DESKTOP) }
                Mode.GAMING -> addRow(Mode.GAMING.label) { launchAppMode(activity, Mode.GAMING) }
            }
        }
        addRow(SETTINGS_ITEM) { openGlobalSettings(activity) }
        addRow(REINIT_DISPLAYS_ITEM) { reinitializeDisplays(activity) }

        root.addView(
            View(activity).apply {
                setBackgroundColor(DIVIDER_COLOR)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(12)
                    bottomMargin = dp(10)
                }
            },
        )
        // This dialog's own hint row (p2-ux-droidtop-dialog-hint-row): the
        // Quick Menu's hint row underneath it named controls that don't
        // apply here ("Lower/Raise/Act/Close"), and a hint naming a button
        // nothing here handles is worse than none. This one names what the
        // dialog itself does.
        root.addView(
            TextView(activity).apply {
                text = "Up/Down Navigate  ·  A Select  ·  B Cancel"
                textSize = 13f
                setTextColor(HINT_COLOR)
                gravity = Gravity.START
                setPadding(dp(8), 0, dp(8), 0)
            },
        )

        // DroidtopDialog: the same dark chrome palette as DroidtopTheme
        // (docs/SPEC.md section 2a chrome theming). This menu used to
        // render in the stock AlertDialog look, visually unrelated to
        // every other droidtop surface.
        dialog = AlertDialog.Builder(activity, com.android.launcher3.R.style.DroidtopDialog)
            .setView(root)
            .setOnDismissListener { onDismiss?.invoke() }
            .show()
        // The dialog opens with real pad focus already on the first row,
        // rather than leaving the very first Down press "acquire" focus
        // (the visible symptom of the stuck-on-"Android" bug above).
        rows.firstOrNull()?.requestFocus()
    }

    private const val TITLE_COLOR = 0xFFEDEDED.toInt()
    private const val ROW_COLOR = 0xFFEDEDED.toInt()
    private const val HINT_COLOR = 0xFFA0A0A0.toInt()
    private const val DIVIDER_COLOR = 0x33FFFFFF

    /** Names the screen it opens: Global settings is where the modes live. */
    private const val SETTINGS_ITEM = "Modes and settings"

    /**
     * Confirmed live on a real dual-screen console (2026-09-25): an addon
     * display can go empty -- and so mirror the built-in panel -- through
     * a door the pre-launch cover (LaunchDisplay.coverVacatedDisplays)
     * does not watch: an app that was on it exits on its own, with
     * droidtop's own shell never losing foreground on ITS display, so
     * nothing re-runs the role orchestration. The HARD reinit
     * (EXTRA_DISPLAY_REINIT_FORCE) already fixes this once it runs -- it
     * was reachable only by double-tapping Home, a gesture nobody is told
     * exists. Naming it here, in the one menu already reachable from every
     * mode (see the class doc), is the fix: no new detection, no new
     * shortcut plumbing, just making the existing recovery findable.
     */
    private const val REINIT_DISPLAYS_ITEM = "Reinitialize displays"

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
                setClassName(context.packageName, APP_MAIN_ACTIVITY)
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
        val activityName = when (implementation) {
            HomeRolePrefs.HomeImplementation.STANDARD -> STANDARD_LAUNCHER_ACTIVITY
            HomeRolePrefs.HomeImplementation.ALTERNATIVE -> ALTERNATIVE_LAUNCHER_ACTIVITY
            HomeRolePrefs.HomeImplementation.NONE -> null
        }
        Modes.setLastMode(context, Mode.LAUNCHER)
        if (activityName == null || !HomeRolePrefs.isDroidtopHome(context)) {
            // droidtop's home activity is enabled but Android's Home opens
            // another app: "Android" is that app's home screen, which is
            // what the Home button shows. Starting droidtop's own launcher
            // there did nothing visible (rig, Android 14, dq-onboard-01).
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(context.packageName, activityName)
            putExtra(EXTRA_MODE, Mode.LAUNCHER.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Whether [intent] is an explicit request for the Android home screen
     * ([openHome]) rather than a Home press, which goes wherever
     * [Modes.homeTarget] says.
     */
    @JvmStatic
    fun isExplicitHome(intent: Intent?): Boolean = intent?.getStringExtra(EXTRA_MODE) == Mode.LAUNCHER.id

    private fun launchAppMode(activity: Activity, mode: Mode) {
        Modes.setLastMode(activity, mode)
        val intent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(activity.packageName, APP_MAIN_ACTIVITY)
            putExtra(EXTRA_MODE, mode.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

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
    const val ACTIVITY = "dev.droidtop.shell.standard.ModeSwitcherActivity"

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
 * Hosts [BackButtonMenu] for a caller with no Activity of its own to show
 * a dialog from. Translucent: the dialog appears over whatever was on
 * screen, and the activity goes when the dialog does.
 */
class ModeSwitcherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BackButtonMenu.show(this) { finish() }
    }
}
