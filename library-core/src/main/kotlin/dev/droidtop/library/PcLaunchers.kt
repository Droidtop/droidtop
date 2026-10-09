package dev.droidtop.library

import android.content.Context

/**
 * The launchers a PC game can come to droidtop through, besides its store
 * (docs/SPEC.md 7g, "Stores"): a game's store stays its real store and the
 * launcher is a second fact, `via` ("GOG · via Heroic"), by id like
 * [PcSource.Store]. The PC Games "Imported from" filter reads it.
 *
 * Today only the Lutris install-script importer (docs/SPEC.md 7e3) records
 * one: it is the writer of [WineGameSettings] whose [WineGameSettings.source]
 * reads "Lutris: <installer>". Scraping a game's facts from lutris.net is not
 * an import and never sets it.
 */
object PcLaunchers {
    data class Launcher(val id: String, val label: String)

    val LUTRIS = Launcher("lutris", "Lutris")
    val HEROIC = Launcher("heroic", "Heroic")

    /** Every launcher droidtop knows, in the order a list names them. */
    val all: List<Launcher> = listOf(LUTRIS, HEROIC)

    fun label(id: String): String = all.firstOrNull { it.id == id }?.label ?: id

    /** The [WineGameSettings.source] an importer of [launcher] writes for [installer]. */
    fun importedSource(launcher: Launcher, installer: String): String = "${launcher.label}: $installer"

    /** The launcher a [WineGameSettings.source] names, or null. Pure. */
    fun viaOf(source: String?): String? =
        source?.let { text -> all.firstOrNull { text.startsWith("${it.label}: ") }?.id }

    /** Every game's launcher, by entry id, from one read of the game settings: off the main thread. */
    fun viaByEntry(context: Context): Map<String, String> =
        WineGameSettingsPrefs.all(context).mapNotNull { (id, settings) -> viaOf(settings.source)?.let { id to it } }.toMap()
}
