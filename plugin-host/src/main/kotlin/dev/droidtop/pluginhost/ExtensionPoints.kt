package dev.droidtop.pluginhost

/** docs/plugin-api.md 3: how much power providing an extension point gives the plugin. */
enum class PointRisk {
    LOW, MEDIUM, HIGH, CRITICAL;

    /** High and critical points need `provide:<point>` consent (4.2). */
    val needsConsent: Boolean get() = this == HIGH || this == CRITICAL
}

/**
 * One extension point droidtop offers plugins: the registry row for
 * docs/plugin-api.md 3. [versions] are the majors this build serves;
 * [label] is the short name the approval screen's "Adds" section shows.
 */
data class ExtensionPoint(
    val id: String,
    val label: String,
    val risk: PointRisk,
    val versions: Set<Int> = setOf(1),
    val officialOnly: Boolean = false,
    /** Hooks are events droidtop waits for; listed here because a plugin declares them in `provides`. */
    val hook: Boolean = false,
)

/** The extension-point registry. An id not in [all] is unsupported (kept, listed, never called). */
object ExtensionPoints {
    val all: List<ExtensionPoint> = listOf(
        ExtensionPoint("library.sources", "Get games from a source", PointRisk.HIGH),
        ExtensionPoint("library.metadata", "Game information", PointRisk.MEDIUM),
        ExtensionPoint("library.artwork", "Artwork and media", PointRisk.MEDIUM),
        ExtensionPoint("library.updates", "Update checks", PointRisk.MEDIUM),
        ExtensionPoint("saves.sync", "Save sync", PointRisk.HIGH),
        ExtensionPoint("achievements.provider", "Achievements", PointRisk.MEDIUM),
        ExtensionPoint("launch.provider", "Launch games", PointRisk.HIGH),
        ExtensionPoint("launch.game_settings", "Per-game settings", PointRisk.LOW),
        ExtensionPoint("launch.hooks", "Before and after a game launches", PointRisk.MEDIUM, hook = true),
        ExtensionPoint("runtime.overlay", "In-game overlays", PointRisk.MEDIUM),
        ExtensionPoint("input.mapping", "Controller mapping", PointRisk.MEDIUM),
        ExtensionPoint("perf.source", "Performance readings", PointRisk.LOW),
        ExtensionPoint("ui.settings", "Settings", PointRisk.LOW),
        ExtensionPoint("ui.quick_tile", "Quick Menu tiles", PointRisk.LOW),
        ExtensionPoint("ui.status_tile", "Status tiles and widgets", PointRisk.LOW),
        ExtensionPoint("ui.context_action", "Context actions", PointRisk.MEDIUM),
        ExtensionPoint("ui.search", "Search", PointRisk.MEDIUM),
        ExtensionPoint("theme.pack", "Themes", PointRisk.MEDIUM),
        ExtensionPoint("ui.tray", "Taskbar and tray items", PointRisk.LOW),
        ExtensionPoint("files.handler", "File actions and open with", PointRisk.HIGH),
        ExtensionPoint("ui.widget", "Desktop widgets", PointRisk.LOW),
        ExtensionPoint("gaming.rows", "Gaming home rows", PointRisk.MEDIUM),
        ExtensionPoint("launcher.actions", "Launcher app drawer and home actions", PointRisk.MEDIUM),
        ExtensionPoint("onboarding.step", "Setup steps", PointRisk.HIGH, officialOnly = true),
        ExtensionPoint("ui.companion", "Companion screen panels", PointRisk.LOW),
        ExtensionPoint("apps.bridge", "Status and actions for another app", PointRisk.MEDIUM),
        ExtensionPoint("accounts.provider", "Accounts", PointRisk.LOW),
        ExtensionPoint("data.export", "Import and export", PointRisk.LOW),
        ExtensionPoint("containers.packages", "Container package sources", PointRisk.CRITICAL),
        ExtensionPoint("media.source", "Act as a media player", PointRisk.MEDIUM),
        ExtensionPoint("intents.in", "Be opened by other apps and links", PointRisk.HIGH),
    )

    private val byId = all.associateBy { it.id }

    fun find(id: String): ExtensionPoint? = byId[id]

    /** True when this build serves [point] at [version]. */
    fun supports(point: String, version: Int): Boolean = find(point)?.versions?.contains(version) == true
}
