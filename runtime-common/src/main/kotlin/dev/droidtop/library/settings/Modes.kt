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

    /**
     * The Launcher3 fork's HOME activity. Defined here rather than in
     * `:shell-default` so mode gating and
     * [dev.droidtop.shell.standard.HomeRolePrefs] name it once.
     */
    const val LAUNCHER_ACTIVITY = "com.android.launcher3.Launcher"

    @Volatile
    private var snapshot: Set<Mode> = Mode.entries.toSet()

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
        snapshot = ModeGate.enabledModes(
            launcherIsDroidtopHome = launcherIsDroidtopHome(context),
            gamingEnabled = prefs.getBoolean(KEY_ENABLED_PREFIX + Mode.GAMING.id, true),
            desktopEnabled = prefs.getBoolean(KEY_ENABLED_PREFIX + Mode.DESKTOP.id, true),
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
}
