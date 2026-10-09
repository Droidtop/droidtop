package dev.droidtop.pluginhost

import org.json.JSONObject

/**
 * Plugins in every mode (docs/plugin-api.md 1.9, Droidtop/tracker#53). A droidtop device has three modes, Gaming,
 * Standard (the Android launcher) and Desktop, and one plugin may add to any of them through the same declared
 * extension points. Two things decide whether one `provides` entry shows in a mode:
 *
 * - the plugin's own `modes` on the entry (`["standard", "desktop"]`); an entry without one shows in every mode
 *   where its point has a place ([HOMES]);
 * - the person's "Where it appears" switch for that plugin and mode on its Permissions screen, kept in the plugin's
 *   grant file under [key] (on unless switched off).
 *
 * Every surface asks [shows] with its own mode before it lists a plugin, so turning a mode off removes the plugin
 * from that mode and nowhere else. Pure: decided from the manifest and the grant snapshot, never by loading a plugin.
 */
object PluginModes {
    const val GAMING = "gaming"
    const val STANDARD = "standard"
    const val DESKTOP = "desktop"

    /** Every mode, in the order the Permissions screen lists them. */
    val ALL: List<String> = listOf(GAMING, STANDARD, DESKTOP)

    /** The grant-file key of a plugin's "Where it appears" switch for one mode. */
    const val KEY_PREFIX = "mode:"

    /**
     * The places droidtop draws each point in, per mode, in this build (the surfaces of docs/plugin-api.md 1.9).
     * A point that is not listed here is data or a host call with no place of its own (it is "in" every mode).
     */
    val HOMES: Map<String, Set<String>> = mapOf(
        // The panel and its tiles: Gaming's Quick Menu, Standard's home-screen menu, Desktop's taskbar.
        "ui.panel" to setOf(GAMING, STANDARD, DESKTOP),
        "ui.status_tile" to setOf(GAMING, STANDARD, DESKTOP),
        "ui.quick_tile" to setOf(GAMING, STANDARD, DESKTOP),
        // Home shelves: Gaming's Home and Desktop's Start menu. Standard has no shelf of games.
        "gaming.rows" to setOf(GAMING, DESKTOP),
        // A game's page and its actions exist in Gaming only.
        "ui.game_section" to setOf(GAMING),
        "ui.context_action" to setOf(GAMING),
        "social.provider" to setOf(GAMING, STANDARD, DESKTOP),
    )

    /** A mode id as droidtop spells it, or null for one it does not know. `android` and `launcher` are Standard. */
    fun canonical(id: String?): String? = when (id?.trim()?.lowercase()) {
        GAMING -> GAMING
        STANDARD, "android", "launcher" -> STANDARD
        DESKTOP -> DESKTOP
        else -> null
    }

    /**
     * The modes [entry] names in its `modes`, or null when it names none (every mode). Unknown ids are dropped, so an
     * entry naming only a mode this build does not have shows nowhere rather than everywhere.
     */
    fun declared(entry: ProvidedPoint): Set<String>? {
        val array = runCatching { JSONObject(entry.extra).optJSONArray("modes") }.getOrNull() ?: return null
        return buildSet { for (i in 0 until array.length()) canonical(array.optString(i))?.let { add(it) } }
    }

    /** True when [entry] asks to be in [mode] (or asks for no mode in particular). */
    fun entryWants(entry: ProvidedPoint, mode: String): Boolean = declared(entry)?.contains(mode) ?: true

    fun key(mode: String): String = KEY_PREFIX + mode

    /** The person's switch for [mode]: on unless they turned it off. A grant file that cannot be read counts as on, like the points it guards. */
    fun allowed(snapshot: PluginGrants.Snapshot, mode: String): Boolean = snapshot.states[key(mode)] != GrantState.DENIED

    /** Whether one entry is drawn in [mode]: the plugin wants it there and the person has not taken the plugin out of that mode. */
    fun shows(snapshot: PluginGrants.Snapshot, entry: ProvidedPoint, mode: String): Boolean =
        entryWants(entry, mode) && allowed(snapshot, mode)

    /** The modes a plugin has anything to show in: the ones its "Where it appears" switches are offered for. */
    fun modesOf(manifest: PluginManifest): List<String> = ALL.filter { mode ->
        manifest.v2.provides.any { entry -> HOMES[entry.point]?.contains(mode) == true && entryWants(entry, mode) }
    }

    /** The mode a surface id belongs to (`desktop.taskbar` is Desktop), or null for a surface every mode shares (`settings`). */
    fun ofSurface(surface: String): String? = canonical(surface.substringBefore('.'))

    /** The places a call's `context.surface` names, one per point and mode (docs/plugin-api.md 1.9). */
    object Surfaces {
        const val GAMING_QUICK_MENU = "gaming.quick_menu"
        const val GAMING_HOME = "gaming.home"
        const val STANDARD_HOME = "standard.home"
        const val DESKTOP_TASKBAR = "desktop.taskbar"
        const val DESKTOP_START_MENU = "desktop.start_menu"
        const val SETTINGS = "settings"

        /**
         * The companion screen in [mode] (`gaming.companion`): a panel on its Plugins tab or as a tab of its own. The
         * companion is in every mode, so its surface carries the mode like the others.
         */
        fun companion(mode: String): String = "$mode.companion"

        /** A panel's rows for the running game on the companion's Game tab (`gaming.companion_game`). */
        fun companionGame(mode: String): String = "$mode.companion_game"
    }
}
