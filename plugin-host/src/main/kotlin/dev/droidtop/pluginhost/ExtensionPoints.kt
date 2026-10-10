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
        // docs/plugin-api.md 3 A11: BIOS files for the emulator setup helper. The files are placed by droidtop, in the emulator's BIOS folder.
        ExtensionPoint("emulator.bios", "BIOS files for emulators", PointRisk.MEDIUM, lets = "Lets it offer BIOS files that droidtop downloads for your emulators, when you ask for them."),
        // docs/plugin-api.md 3 A12: Android app catalogs (an F-Droid repository client, a release-page tracker). The plugin
        // lists; droidtop downloads, checks the package and its signing key, installs and decides what is an update.
        ExtensionPoint("apps.catalog", "Android apps from a catalog", PointRisk.HIGH, lets = "Lets it offer Android apps that droidtop downloads, checks and installs when you ask."),
        ExtensionPoint("library.metadata","Game information", PointRisk.MEDIUM, lets = "Lets it supply descriptions and details for your games."),
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
        ExtensionPoint("ui.main", "Its own full-screen app", PointRisk.LOW, lets = "Lets it run its own full-screen app."),
        ExtensionPoint("ui.panel", "Quick Menu panel", PointRisk.LOW, lets = "Lets it add its own panel to the Quick Menu and the companion screen, where you use and set it up."),
        ExtensionPoint("ui.game_section", "Rows on a game's page", PointRisk.MEDIUM, lets = "Lets it add its own rows to a game's page."),
        ExtensionPoint("ui.quick_tile", "Quick Menu tiles", PointRisk.LOW, lets = "Lets it add tiles to the Quick Menu."),
        ExtensionPoint("ui.status_tile", "Status tiles and widgets", PointRisk.LOW, lets = "Lets it add status tiles and widgets."),
        ExtensionPoint("ui.context_action", "Context actions", PointRisk.MEDIUM, lets = "Lets it add actions to the menu on a game or file."),
        ExtensionPoint("ui.search", "Search", PointRisk.MEDIUM, lets = "Lets it add results to search."),
        ExtensionPoint("theme.pack", "Themes", PointRisk.MEDIUM, lets = "Lets it provide themes."),
        ExtensionPoint("ui.tray", "Taskbar and tray items", PointRisk.LOW, lets = "Lets it add items to the taskbar and tray."),
        ExtensionPoint("files.handler", "File actions and open with", PointRisk.HIGH, lets = "Lets it receive files you choose to open or send to it."),
        ExtensionPoint("ui.widget", "Desktop widgets", PointRisk.LOW, lets = "Lets it add widgets to the desktop."),
        ExtensionPoint("gaming.rows", "Shelves on Home", PointRisk.MEDIUM, lets = "Lets it add shelves of your own games to Home."),
        ExtensionPoint("launcher.actions", "Launcher app drawer and home actions", PointRisk.MEDIUM, lets = "Lets it add actions to the app drawer and home screen."),
        ExtensionPoint("onboarding.step", "Setup steps", PointRisk.HIGH, officialOnly = true, lets = "Lets it add a step to droidtop's first-run setup."),
        ExtensionPoint("social.provider", "Friends and chat", PointRisk.MEDIUM, lets = "Lets it show your friends and conversations from a service, and send the messages you write there."),
        ExtensionPoint("apps.bridge", "Status and actions for another app", PointRisk.MEDIUM, lets = "Lets it show status and actions for another app you have installed."),
        ExtensionPoint("accounts.provider", "Accounts", PointRisk.LOW, lets = "Lets it hold a sign-in for a service."),
        ExtensionPoint("data.export", "Import and export", PointRisk.LOW, lets = "Lets it import and export your data."),
        ExtensionPoint("containers.packages", "Container package sources", PointRisk.CRITICAL, lets = "Lets it supply software that droidtop installs into containers."),
        ExtensionPoint("media.source", "Act as a media player", PointRisk.MEDIUM, lets = "Lets it act as a media player."),
        ExtensionPoint("intents.in", "Be opened by other apps and links", PointRisk.HIGH, lets = "Lets other apps and links open things in it."),
        // docs/plugin-api.md 3 E8, E9: long-running and periodic work; each entry also has its own switch on the plugin's page.
        ExtensionPoint("jobs.service", "Keep running in the background", PointRisk.HIGH, lets = "Lets it keep a task running while you use other things, shown in droidtop's notification."),
        ExtensionPoint("jobs.schedule", "Scheduled tasks", PointRisk.LOW, lets = "Lets it run a task on a schedule, such as a check every few hours."),
        // docs/plugin-api.md 3 F8: the program a context needs on the person's computers, which droidtop-agent installs there
        // only after the person approves it on that computer.
        ExtensionPoint("computers.context_adapter", "A program for your computers", PointRisk.MEDIUM, lets = "Lets it offer a program that droidtop-agent installs on your computer once you approve it there, to keep its data in step with an app on that computer."),
    )

    private val byId = all.associateBy { it.id }

    fun find(id: String): ExtensionPoint? = byId[id]

    /** True when this build serves [point] at [version]. */
    fun supports(point: String, version: Int): Boolean = find(point)?.versions?.contains(version) == true

    /**
     * True when [manifest] declares [point] at a version this build serves. droidtop calls only the points a plugin
     * declared (docs/plugin-api.md 1.2); [PluginGrants.pointRefusal] checks this on every host call, so the rule holds
     * at the boundary and not only in the surfaces that choose which plugins to ask.
     */
    fun declares(manifest: PluginManifest, point: String): Boolean =
        manifest.v2.provides.any { it.point == point && supports(point, it.version) }
}
