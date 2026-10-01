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
    /** One plain sentence on what providing this point lets the plugin do: the line the approval list shows. */
    val lets: String = "",
)

/** The extension-point registry. An id not in [all] is unsupported (kept, listed, never called). */
object ExtensionPoints {
    val all: List<ExtensionPoint> = listOf(
        ExtensionPoint("library.sources", "Get games from a source", PointRisk.HIGH, lets = "Lets it search a source and download games into your game folders."),
        ExtensionPoint("library.metadata", "Game information", PointRisk.MEDIUM, lets = "Lets it supply descriptions and details for your games."),
        ExtensionPoint("library.artwork", "Artwork and media", PointRisk.MEDIUM, lets = "Lets it supply covers, screenshots and other media for your games."),
        ExtensionPoint("library.updates", "Update checks", PointRisk.MEDIUM, lets = "Lets it tell you when a game has a newer version."),
        ExtensionPoint("saves.sync", "Save sync", PointRisk.HIGH, lets = "Lets it copy your game saves to and from another place."),
        ExtensionPoint("achievements.provider", "Achievements", PointRisk.MEDIUM, lets = "Lets it show achievements for your games."),
        ExtensionPoint("launch.provider", "Launch games", PointRisk.HIGH, lets = "Lets it decide what runs when you launch a game."),
        ExtensionPoint("launch.game_settings", "Per-game settings", PointRisk.LOW, lets = "Lets it add settings to a game's own page."),
        ExtensionPoint("launch.hooks", "Before and after a game launches", PointRisk.MEDIUM, hook = true, lets = "Lets it run something just before and after a game launches."),
        ExtensionPoint("runtime.overlay", "In-game overlays", PointRisk.MEDIUM, lets = "Lets it draw over a running game."),
        ExtensionPoint("input.mapping", "Controller mapping", PointRisk.MEDIUM, lets = "Lets it supply controller mappings."),
        ExtensionPoint("perf.source", "Performance readings", PointRisk.LOW, lets = "Lets it report performance readings."),
        ExtensionPoint("ui.settings", "Settings", PointRisk.LOW, lets = "Lets it add its own page in Settings."),
        ExtensionPoint("ui.main", "Its own full-screen app", PointRisk.LOW, lets = "Lets it run its own full-screen app, in its own process."),
        ExtensionPoint("ui.quick_tile", "Quick Menu tiles", PointRisk.LOW, lets = "Lets it add tiles to the Quick Menu."),
        ExtensionPoint("ui.status_tile", "Status tiles and widgets", PointRisk.LOW, lets = "Lets it add status tiles and widgets."),
        ExtensionPoint("ui.context_action", "Context actions", PointRisk.MEDIUM, lets = "Lets it add actions to the menu on a game or file."),
        ExtensionPoint("ui.search", "Search", PointRisk.MEDIUM, lets = "Lets it add results to search."),
        ExtensionPoint("theme.pack", "Themes", PointRisk.MEDIUM, lets = "Lets it provide themes."),
        ExtensionPoint("ui.tray", "Taskbar and tray items", PointRisk.LOW, lets = "Lets it add items to the taskbar and tray."),
        ExtensionPoint("files.handler", "File actions and open with", PointRisk.HIGH, lets = "Lets it receive files you choose to open or send to it."),
        ExtensionPoint("ui.widget", "Desktop widgets", PointRisk.LOW, lets = "Lets it add widgets to the desktop."),
        ExtensionPoint("gaming.rows", "Gaming home rows", PointRisk.MEDIUM, lets = "Lets it add rows to the Gaming home screen."),
        ExtensionPoint("launcher.actions", "Launcher app drawer and home actions", PointRisk.MEDIUM, lets = "Lets it add actions to the app drawer and home screen."),
        ExtensionPoint("onboarding.step", "Setup steps", PointRisk.HIGH, officialOnly = true, lets = "Lets it add a step to droidtop's first-run setup."),
        ExtensionPoint("ui.companion", "Companion screen panels", PointRisk.LOW, lets = "Lets it add panels to the companion screen."),
        ExtensionPoint("apps.bridge", "Status and actions for another app", PointRisk.MEDIUM, lets = "Lets it show status and actions for another app you have installed."),
        ExtensionPoint("accounts.provider", "Accounts", PointRisk.LOW, lets = "Lets it hold a sign-in for a service."),
        ExtensionPoint("data.export", "Import and export", PointRisk.LOW, lets = "Lets it import and export your data."),
        ExtensionPoint("containers.packages", "Container package sources", PointRisk.CRITICAL, lets = "Lets it supply software that droidtop installs into containers."),
        ExtensionPoint("media.source", "Act as a media player", PointRisk.MEDIUM, lets = "Lets it act as a media player."),
        ExtensionPoint("intents.in", "Be opened by other apps and links", PointRisk.HIGH, lets = "Lets other apps and links open things in it."),
    )

    private val byId = all.associateBy { it.id }

    fun find(id: String): ExtensionPoint? = byId[id]

    /** True when this build serves [point] at [version]. */
    fun supports(point: String, version: Int): Boolean = find(point)?.versions?.contains(version) == true
}
