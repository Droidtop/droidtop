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
     * This is what a romgi-derived plugin needs (search, resolve a
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
    ;

    companion object {
        fun fromId(id: String): PluginCapability? = entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}
