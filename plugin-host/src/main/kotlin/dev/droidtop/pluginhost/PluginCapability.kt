package dev.droidtop.pluginhost

/**
 * What a plugin can contribute, matching the API surface the owner
 * decided on 2026-09-25 (docs/SPEC.md 12a). A closed set, same reasoning
 * as [dev.droidtop.library.integrations.IntegrationCapability] for the
 * JSON half: what droidtop hands over and what trust it needs differs per
 * capability, so "declare anything" would turn the trust model
 * per-plugin freeform (the exact question §12a left open before this
 * decision).
 *
 * A plugin declares the subset it implements in its manifest; droidtop
 * never calls a capability a plugin didn't declare, and a plugin that
 * returns a well-formed answer for a capability it didn't declare gets
 * that answer discarded, not surfaced.
 */
enum class PluginCapability(val id: String, val display: String) {
    /**
     * Search a source, download into a configured library folder, report
     * progress -- the plugin form of the JSON half's `acquire_content`.
     * This is what a content-acquisition plugin needs (search, resolve a
     * download, write into the folder droidtop hands it): droidtop
     * passes the destination folder PATH and the query, the plugin
     * reports progress back through repeated `invoke` calls or a single
     * blocking call bounded by the runner's watchdog, and it never
     * receives a database handle -- see PluginApi.kt.
     */
    ACQUIRE_CONTENT("acquire_content", "Get content"),

    /** A metadata/scrape source: given a game's known facts, returns candidate metadata/media droidtop can offer alongside its built-in scrapers. */
    METADATA_SOURCE("metadata_source", "Metadata source"),

    /** An action offered on a game's own entry (library-core's EntryDetailScreen), the plugin analogue of `open_with` for cases a bare Intent can't cover. */
    LIBRARY_ACTION("library_action", "Library action"),

    /** A status tile/control on droidtop's quick surfaces -- network/VPN state, a toggle, a reading -- refreshed on droidtop's own schedule, never a background loop the plugin owns. */
    STATUS_TILE("status_tile", "Status tile"),

    /** Settings rows rendered in droidtop's own settings style (the existing CatalogScreen/CatalogRow model), never a plugin-drawn UI. */
    SETTINGS_ROWS("settings_rows", "Settings rows"),

    /**
     * Status and actions for ONE OTHER INSTALLED APP the plugin manages
     * -- distinct from [STATUS_TILE] (droidtop's own ambient state) and
     * from a per-system or per-library-entry row: an app-management/
     * patch-tool-shaped plugin reports what it knows about a specific
     * package (installed version, pending update, a patch/job state) and
     * offers actions on it. Cross-cutting need surfaced 2026-09-25 by a
     * batch of real plugin designs; kept generic here on purpose (see
     * [PluginJob] for the long-running-action shape these actions
     * usually need, and [PluginManifest.boundServiceTargets] for the
     * "holds a live connection to that app" case).
     */
    APP_STATUS("app_status", "App status"),

    /**
     * A file-synchronization app's view of one folder (docs/SPEC.md 12a
     * "App-bridge plugin contracts"; Syncthing reading its own local
     * REST API is the named case -- all of that is plugin-side, droidtop
     * never speaks the REST API itself). droidtop calls
     * `invoke(SYNC_STATUS, {"action": "status", "path": <a real folder>})`
     * before launching a game that lives in that folder, and warns when
     * the answer carries conflicts. The answer's values, all optional
     * but `conflicts` being the one that gates the warning:
     * - `state` -- one short word (idle/syncing/error/...); droidtop
     *   shows it, it never parses it;
     * - `progress` -- 0-100;
     * - `conflicts` -- a count; anything non-zero warns before launch,
     *   exactly as droidtop's own built-in fallback (a
     *   `*.sync-conflict-*` file scan) already does when no
     *   sync_status plugin is installed;
     * - `details` -- one complete sentence, the same convention
     *   [SETTINGS_ROWS] fixed.
     */
    SYNC_STATUS("sync_status", "Sync status"),

    /**
     * Applies a per-game input/key-remapping profile (docs/SPEC.md 12a
     * "App-bridge plugin contracts"; a Key Mapper app is the named
     * case -- talking to it is plugin-side, via
     * [PluginContext.launchAppWithExtras] or a service declared in
     * [PluginManifest.boundServiceTargets]). droidtop fires
     * `invoke(INPUT_PROFILE, {"action": "apply", "gameId", "title",
     * "kind", "systemId", "path"})` on EVERY launch path -- from
     * [Library.launch][dev.droidtop.library.Library.launch], so
     * launcher pins, the Gaming shell, Desktop and deep links behave
     * identically -- fire-and-forget on background IO, never blocking
     * the launch on a binder call, and `{"action": "clear", ...same
     * facts}` when the shell gains foreground again (the game session
     * ended). droidtop's own built-in fallback is its per-game
     * controller notes (shown to the player instead of applied); this
     * capability is the automated equivalent.
     */
    INPUT_PROFILE("input_profile", "Input profile"),

    /**
     * An update tracker's view of installed apps' pending updates
     * (docs/SPEC.md 12a "App-bridge plugin contracts"; Obtainium
     * reading its own tracked-apps data is the named case).
     * `invoke(APP_UPDATES, {"action": "updates"})` answers
     * `values["updates"]` = a JSON array string of objects
     * `{package, installed, latest, url?}`. droidtop filters that to
     * the packages its players database names and shows the rest as
     * rows in its one Settings area and on a system's Player choice
     * screen -- the whole answer is untrusted input like any plugin
     * result (12a trust point 5): displayed, size-capped, never used as
     * a path or intent target beyond a plain browser `url`. When no
     * app_updates plugin is installed, droidtop's own built-in
     * fallback checks the known-repo emulators against GitHub's
     * releases/latest API itself.
     */
    APP_UPDATES("app_updates", "App updates"),

    /**
     * Sets and clears a "Playing \<game\>" presence on a chat platform
     * (docs/SPEC.md 12a "App-bridge plugin contracts"; Discord is the
     * named case, and the plugin is expected to reach the Discord APP
     * where the owner's "where possible" holds -- e.g. the official
     * Social SDK route SPEC §7e already records, or the app's own
     * surfaces). droidtop fires `invoke(PRESENCE, {"action": "set",
     * "game": <title>})` on every launch and `invoke(PRESENCE,
     * {"action": "clear"})` on return to the shell, from the same
     * fire-and-forget path [INPUT_PROFILE] uses. droidtop's own
     * built-in fallback (a user-signed-in Discord session and the
     * documented gateway protocol, off by default) runs when no
     * presence plugin is installed.
     */
    PRESENCE("presence", "Presence"),
    ;

    companion object {
        fun fromId(id: String): PluginCapability? = entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}
