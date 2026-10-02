package dev.droidtop.pluginhost

/**
 * Something droidtop itself did that an approved plugin may care about --
 * the OUTGOING half of the plugin API (docs/SPEC.md 12a "Event hooks"),
 * mirror image of [PluginCapability] (the incoming half: what droidtop
 * calls a plugin FOR). A closed set, same reasoning as
 * [PluginCapability]: droidtop only ever fires an event it actually
 * defines here, and only to a plugin that declared it wants that event
 * ([PluginManifest.subscribedEvents]) -- there is no ambient "tell me
 * about everything" subscription.
 *
 * Versioned separately from [PLUGIN_CONTRACT_VERSION] ([PLUGIN_EVENT_CONTRACT_VERSION])
 * because the event set can grow independently of the capability/API
 * surface -- adding a new event never changes what [DroidtopPlugin.invoke]
 * or [PluginContext] mean, and a plugin that doesn't know about a newer
 * event id simply never subscribes to it (an unrecognised id in
 * [PluginManifest.subscribedEvents] is silently never matched, not a
 * validation failure -- see [PluginManifest.structuralProblems]).
 */
enum class PluginEvent(val id: String, val version: Int, val display: String) {
    /**
     * Fired after [dev.droidtop.library.consoles.PlayerOverridePrefs.set]
     * (or the "first installed" default resolving to a different player)
     * changes which player/emulator/core is the default for one console
     * system. This is the real, cited need that drove building the event
     * mechanism at all (docs/SPEC.md 12a): a RetroArch-manager-shaped
     * plugin wants to know "the user just picked me as SNES's player, is
     * the core I'd launch with actually downloaded yet" without polling.
     *
     * Args (all plain strings, [PluginArgs]): `systemId`, `systemName`,
     * `playerId`, `playerName`, `playerPackage` (empty when the chosen
     * player has no package, e.g. a bare am-start template), `core`
     * (the core the chosen player will actually launch with: the core
     * its own `LIBRETRO` extra names when the entry carries one -- a
     * players-database RetroArch entry names its specific core, which
     * is not always the system-level default -- otherwise the system's
     * own configured core short name,
     * [dev.droidtop.library.consoles.ConsoleSystemDef.retroArchCore];
     * empty when the entry names no core and the system declares
     * none).
     *
     * A plugin's [DroidtopPlugin.onEvent] answer may ask droidtop to
     * start a job in response: returning [PluginResult.success] with a
     * `startJob` value set to a [PluginCapability.id] and a `job` value
     * naming the job (both plugin-defined; droidtop passes the REST of
     * the returned values straight through as that job's own args) makes
     * the caller ([dev.droidtop.library.integrations.PluginEventBus])
     * turn around and call [dev.droidtop.pluginhost.PluginCrashPolicy.startJob]
     * with exactly those args, tracked in [PluginJobsCenter] like any
     * other job. A plugin that has nothing to do returns
     * `PluginResult.success()` with no `startJob` key -- the default
     * [DroidtopPlugin.onEvent] implementation already does this, so a
     * plugin that never overrides it is simply never affected by any
     * event it happens to subscribe to.
     */
    DEFAULT_PLAYER_CHANGED("library.default_player_changed", 2, "Default player/core changed"),
    GAME_LAUNCHING("game.launching", 1, "Game launching"),
    GAME_EXITED("game.exited", 1, "Game exited"),
    LIBRARY_SCAN_FINISHED("library.scan_finished", 1, "Library scan finished"),
    MODE_CHANGED("mode.changed", 1, "Mode changed"),
    ;

    companion object {
        fun fromId(id: String): PluginEvent? = when (id.trim().lowercase()) {
            "default_player_changed" -> DEFAULT_PLAYER_CHANGED
            else -> entries.firstOrNull { it.id == id.trim().lowercase() }
        }
    }
}

/** The event contract version this build of droidtop speaks -- see [PluginEvent]'s own doc comment for why this is separate from [PLUGIN_CONTRACT_VERSION].
 * 2: `default_player_changed`'s `core` arg now reports the CHOSEN entry's own core when its `LIBRETRO` template names one
 * (1 always reported the system's configured core, which made a manager plugin ensure the wrong core for the
 * players database's per-entry cores, e.g. psx "beetle psx hw" = `mednafen_psx_hw` vs the system's `mednafen_psx`). */
const val PLUGIN_EVENT_CONTRACT_VERSION = 2
