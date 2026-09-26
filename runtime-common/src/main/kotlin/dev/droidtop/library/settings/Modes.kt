package dev.droidtop.library.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * droidtop's three UI modes. One process hosts all of them, so the whole
 * point of this file is that a mode a person has turned off contributes
 * NO running code: no Application-level initialiser, no service, no
 * bound system component, no scan (docs/SPEC.md, "Modes and what each
 * contributes").
 *
 * [id] is the wire value: it travels in
 * [dev.droidtop.shell.standard.BackButtonMenu.EXTRA_MODE] and is what the
 * last-mode/default-mode preferences store.
 */
enum class Mode(val id: String, val label: String) {
    /** The Launcher3 fork: home screen, app drawer, widgets. */
    LAUNCHER("standard", "Android"),

    /** The gaming shell: ES-DE theme engine, Quick Menu, PC surface, scraper. */
    GAMING("gaming", "Gaming"),

    /** Containers, the Wine/Linux desktop, window streaming. */
    DESKTOP("desktop", "Desktop"),
    ;

    companion object {
        @JvmStatic
        fun byId(id: String?): Mode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Every piece of droidtop that belongs to a mode rather than to the shared
 * core, and which mode(s) it belongs to. Enumerating them is the point:
 * "a disabled mode runs no code" is only checkable if the code a mode owns
 * is a list rather than a habit (docs/SPEC.md, "Modes and what each
 * contributes"). [dev.droidtop.app.ModeStartup] starts exactly the pieces
 * [ModeGate.piecesToStart] returns, and stops the rest.
 *
 * Pieces here are only the ones that would otherwise run with their mode
 * off -- process-start work and components the SYSTEM binds or broadcasts
 * to on its own. A mode's Activities and Compose trees are not listed
 * because they cannot start without the mode: MainActivity renders one
 * shell or none, and the launcher's Activities follow the HOME role.
 */
enum class ModePiece(vararg owners: Mode) {
    /**
     * The Launcher3 fork's four system-started components: the
     * notification-dots listener, the screen-off accessibility service,
     * and the session-commit and widgets-restored receivers.
     */
    LAUNCHER_SYSTEM_COMPONENTS(Mode.LAUNCHER),

    /** Handing the secondary screen to Launcher3's own second-screen UI. */
    LAUNCHER_SECOND_SCREEN(Mode.LAUNCHER),

    /** droidtop's own notification listener, read only by the Quick Menu. */
    GAMING_NOTIFICATION_LISTENER(Mode.GAMING),

    /** The Gaming companion/input surface on a secondary screen. */
    GAMING_SECOND_SCREEN(Mode.GAMING),

    /** Warming the platforms database for Gaming's synchronous label lookups. */
    GAMING_PLATFORMS_DATABASE(Mode.GAMING),

    /** The Desktop companion/input surface on a secondary screen. */
    DESKTOP_SECOND_SCREEN(Mode.DESKTOP),

    /**
     * The desktop session (`DesktopSessionService`): the primary container,
     * its compositor and everything on the desktop. Stopped when Desktop
     * mode is switched off.
     */
    DESKTOP_SESSION(Mode.DESKTOP),

    /**
     * "Open with droidtop" for downloaded programs and packages: it runs
     * them in Desktop mode's Wine environments and containers, so with
     * Desktop off it is not offered to other apps at all.
     */
    DESKTOP_OPEN_WITH(Mode.DESKTOP),

    /**
     * The device VPN a container serves: it needs a container, so it is
     * Desktop's, and with Desktop off the service is neither offered to
     * the system nor left running.
     */
    DESKTOP_VPN(Mode.DESKTOP),

    /**
     * The vendored gamenative backbone. Two owners, not one: Gaming's PC
     * surface and Desktop's containers both need it. The shared PC launch
     * path starts it on demand as well, which is why it is reached through
     * one idempotent entry point rather than started twice.
     */
    WINDOWS_BACKBONE(Mode.GAMING, Mode.DESKTOP),
    ;

    val owners: Set<Mode> = owners.toSet()
}

/**
 * The pure half of mode gating: no Android, no I/O, so it is unit-tested
 * directly (`ModesTest`).
 */
object ModeGate {
    /**
     * Which modes are live. LAUNCHER is on exactly when droidtop's own
     * HOME activity is the enabled one -- "Alternative" forwards to
     * somebody else's launcher and runs none of the fork, and "neither"
     * runs none of it either.
     */
    fun enabledModes(
        launcherIsDroidtopHome: Boolean,
        gamingEnabled: Boolean,
        desktopEnabled: Boolean,
    ): Set<Mode> = buildSet {
        if (launcherIsDroidtopHome) add(Mode.LAUNCHER)
        if (gamingEnabled) add(Mode.GAMING)
        if (desktopEnabled) add(Mode.DESKTOP)
    }

    /**
     * Which app-hosted shell [dev.droidtop.app.MainActivity] should
     * render. An explicit request wins, then the user's default, then the
     * last one used -- but only ever a mode that is actually enabled, and
     * never LAUNCHER (that one is an Activity of its own). Null means
     * there is nothing for MainActivity to show, and it should hand back
     * to the launcher rather than fall through to Desktop, which is what
     * it used to do.
     */
    /**
     * What this process should have running, given what is enabled. A
     * piece whose owners are all off is absent from the result, and
     * ModeStartup then stops it rather than merely not starting it -- a
     * mode switched off mid-session must stop contributing immediately,
     * not at the next process start.
     */
    fun piecesToStart(enabled: Set<Mode>): Set<ModePiece> =
        ModePiece.entries.filterTo(mutableSetOf()) { piece -> piece.owners.any { it in enabled } }

    /**
     * The rows [dev.droidtop.shell.standard.BackButtonMenu]'s mode switcher
     * offers, in order: "Android" always (it is the home screen the Home
     * button opens whether or not droidtop holds it), then Desktop and
     * Gaming only while they are enabled. Settings and Reinitialize
     * displays are the switcher's own fixed rows, not modes, so they are
     * appended by the caller rather than named here.
     */
    fun switcherModes(enabled: Set<Mode>): List<Mode> = buildList {
        add(Mode.LAUNCHER)
        if (Mode.DESKTOP in enabled) add(Mode.DESKTOP)
        if (Mode.GAMING in enabled) add(Mode.GAMING)
    }

    fun resolveAppMode(
        explicitId: String?,
        defaultId: String?,
        lastId: String?,
        enabled: Set<Mode>,
    ): Mode? {
        fun usable(id: String?): Mode? =
            Mode.byId(id)?.takeIf { it != Mode.LAUNCHER && it in enabled }
        return usable(explicitId) ?: usable(defaultId) ?: usable(lastId)
            ?: enabled.firstOrNull { it != Mode.LAUNCHER }
    }

    /**
     * Where the Home key takes a person (docs/SPEC.md 2c, "Home goes to
     * the default mode"): the default mode they chose, when they chose one
     * and it is on; otherwise the mode they last used; otherwise the
     * Android home screen, which is where a Home press already is.
     *
     * The default wins over the last-used mode because that is what the
     * choice MEANS. On the rig (dq-coordinator-24, finding 4) a person who
     * answered "Opens into Android" opened Gaming once, and from then on
     * every Home press forwarded straight back into Gaming, because Home
     * followed only the last-used mode.
     */
    fun homeTarget(defaultId: String?, lastId: String?, enabled: Set<Mode>): Mode {
        fun usable(id: String?): Mode? =
            Mode.byId(id)?.takeIf { it == Mode.LAUNCHER || it in enabled }
        return usable(defaultId) ?: usable(lastId) ?: Mode.LAUNCHER
    }
}

/**
 * The one place that decides what starts. Read once at process start
 * ([load]) and again whenever a mode is switched on or off ([setEnabled]);
 * everything mode-specific asks [isEnabled] rather than reading
 * preferences of its own.
 */
object Modes {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_LAST_MODE = "droidtop_last_mode"

    /**
     * A user-set "always start in Gaming", distinct from [lastMode],
     * which is overwritten every time the shell switcher is used.
     */
    private const val KEY_DEFAULT_MODE = "droidtop_default_mode"
    private const val KEY_ENABLED_PREFIX = "droidtop_mode_enabled_"

    /** Written by onboarding when it finishes (`GamesRootPrefs.markOnboardingComplete`). */
    private const val KEY_ONBOARDING_COMPLETE = "droidtop_onboarding_complete"

    /**
     * droidtop's real `CATEGORY_HOME` component for Standard -- since
     * 2026-09-26 a small trampoline ahead of the Launcher3 fork itself
     * (`dev.droidtop.shell.standard.HomeTrampolineActivity`), not
     * `com.android.launcher3.Launcher` directly: the trampoline decides
     * the Home target from [homeTarget] before Launcher3's own `onCreate`
     * ever runs, so a Home press whose target is Gaming or Desktop never
     * inflates Standard's view tree at all (docs/SPEC.md 2c). Defined here
     * rather than in `:shell-default` so mode gating and
     * [dev.droidtop.shell.standard.HomeRolePrefs] name it once.
     */
    const val LAUNCHER_ACTIVITY = "dev.droidtop.shell.standard.HomeTrampolineActivity"

    private val state = kotlinx.coroutines.flow.MutableStateFlow<Set<Mode>>(Mode.entries.toSet())

    private val snapshot: Set<Mode> get() = state.value

    /**
     * The same set as [enabled], as something a screen can watch: a shell
     * whose mode is switched off while it is on screen leaves (MainActivity),
     * instead of running on until the process dies (rig, dq-onboard-01).
     */
    val enabledFlow: kotlinx.coroutines.flow.StateFlow<Set<Mode>> get() = state

    /**
     * What to (re)start when the snapshot changes. `:app` installs the
     * one listener there is -- the same seam pattern
     * `PcGameRuntimeRegistry` and `SettingsScreenRegistry` already use,
     * and the reason this module needs no dependency on the shells.
     */
    private val listeners = mutableListOf<() -> Unit>()

    fun onChanged(listener: () -> Unit) {
        synchronized(listeners) { listeners += listener }
    }

    /** What the last [load]/[reload] saw. Cheap enough to read anywhere. */
    val enabled: Set<Mode> get() = snapshot

    @JvmStatic
    fun isEnabled(mode: Mode): Boolean = mode in snapshot

    /**
     * Process start: run the Gaming rename migration once, then take the
     * snapshot every gate reads.
     */
    @JvmStatic
    fun load(context: Context) {
        ModeRenameMigration.applyOnce(context)
        reload(context)
    }

    /** Re-read after a mode has been switched on or off. */
    @JvmStatic
    fun reload(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Gaming and Desktop are on once setup has turned them on, not
        // before (docs/SPEC.md 2c): until onboarding finishes nothing has
        // been chosen, so neither mode's pieces run. The Windows backbone
        // used to boot in Application.onCreate on a fresh install and
        // crashed droidtop's very first launch on Android 14 (rig,
        // dq-onboard-01).
        val setUp = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        state.value = ModeGate.enabledModes(
            launcherIsDroidtopHome = launcherIsDroidtopHome(context),
            gamingEnabled = setUp && prefs.getBoolean(KEY_ENABLED_PREFIX + Mode.GAMING.id, true),
            desktopEnabled = setUp && prefs.getBoolean(KEY_ENABLED_PREFIX + Mode.DESKTOP.id, true),
        )
        val toNotify = synchronized(listeners) { listeners.toList() }
        toNotify.forEach { it() }
    }

    private fun launcherIsDroidtopHome(context: Context): Boolean =
        when (context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, LAUNCHER_ACTIVITY))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            // DEFAULT is the manifest's own android:enabled="true".
            else -> true
        }

    /** Storage read, for settings screens that show the switch itself. */
    fun isEnabledInStorage(context: Context, mode: Mode): Boolean = when (mode) {
        Mode.LAUNCHER -> launcherIsDroidtopHome(context)
        else -> context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED_PREFIX + mode.id, true)
    }

    fun setEnabled(context: Context, mode: Mode, enabled: Boolean) {
        require(mode != Mode.LAUNCHER) {
            "Launcher mode follows the HOME role; use HomeRolePrefs"
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED_PREFIX + mode.id, enabled)
            .apply()
        reload(context)
    }

    @JvmStatic
    fun lastMode(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_MODE, Mode.LAUNCHER.id) ?: Mode.LAUNCHER.id

    @JvmStatic
    fun setLastMode(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_MODE, mode.id)
            .apply()
    }

    fun defaultMode(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEFAULT_MODE, null)

    fun setDefaultMode(context: Context, mode: Mode?) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (mode == null) editor.remove(KEY_DEFAULT_MODE) else editor.putString(KEY_DEFAULT_MODE, mode.id)
        editor.apply()
    }

    /** The app-hosted shell to render; see [ModeGate.resolveAppMode]. */
    fun resolveAppMode(context: Context, explicitId: String?): Mode? = ModeGate.resolveAppMode(
        explicitId = explicitId,
        defaultId = defaultMode(context),
        lastId = lastMode(context),
        enabled = snapshot,
    )

    /**
     * Where a Home press goes, as a mode id; see [ModeGate.homeTarget].
     * Read by the HOME activities (the Launcher3 fork and the Alternative
     * forwarder), which is why it answers in the wire id Java can compare.
     */
    @JvmStatic
    fun homeTarget(context: Context): String = ModeGate.homeTarget(
        defaultId = defaultMode(context),
        lastId = lastMode(context),
        enabled = snapshot,
    ).id
}
