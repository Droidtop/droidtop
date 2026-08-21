package dev.droidtop.library

import java.io.File

/**
 * Resolves game artwork the same way ES-DE's own gamelist scraper stores
 * it, so a games root that's already been scraped by ES-DE (or populated
 * by hand in the same layout) works as droidtop's artwork backend with no
 * extra tooling on droidtop's side -- checked against ES-DE's real,
 * published docs (USERGUIDE.md: "ES-DE does not use tags inside the
 * gamelist.xml files to find game media but instead matches the media to
 * the names of the game/ROM files"), not guessed. droidtop is deliberately
 * basing this on the open-source Linux build of ES-DE
 * (gitlab.com/es-de/emulationstation-de, MIT-licensed) rather than its
 * closed-source Android port -- USERGUIDE.md describes the core project's
 * own on-disk conventions, which are the same across platforms; nothing
 * here is derived from the Android app itself.
 *
 * Layout: `<mediaRoot>/<system>/<mediaType>/<romBaseName>.<ext>`, checked
 * in ES-DE's own priority order for picking "the one representative image"
 * (miximages -- ES-DE's own generated composite art -- first, then covers,
 * then screenshots, then titlescreens).
 *
 * [system] here is whatever folder name droidtop scans a game under (see
 * [GameEngine.esDeSystemName]) -- ES-DE's own systems list (es_systems.xml)
 * is console/emulator-shaped and has no built-in "Ren'Py"/"RPG Maker"
 * system, so this is droidtop's own convention, not something ES-DE ships
 * out of the box. A user who wants droidtop and a real ES-DE install to
 * share scraped media for these engines would need to define a matching
 * custom system in their own es_systems.xml (ES-DE supports this per its
 * "Game system customizations" docs) -- not assumed to already exist.
 */
object EsDeArtwork {
    private val MEDIA_TYPES_BY_PRIORITY = listOf("miximages", "covers", "screenshots", "titlescreens")
    private val EXTENSIONS = listOf("png", "jpg", "jpeg")

    fun resolve(mediaRoot: File, system: String, romBaseName: String): String? {
        for (mediaType in MEDIA_TYPES_BY_PRIORITY) {
            for (ext in EXTENSIONS) {
                val candidate = File(mediaRoot, "$system/$mediaType/$romBaseName.$ext")
                if (candidate.isFile) return candidate.toURI().toString()
            }
        }
        return null
    }
}

/** Folder-name convention used both to scan under [File] roots and to key into [EsDeArtwork]'s media lookup. */
fun GameEngine.esDeSystemName(): String = when (this) {
    GameEngine.RENPY -> "renpy"
    GameEngine.RPG_MAKER_MV -> "rpgmaker_mv"
    GameEngine.RPG_MAKER_MZ -> "rpgmaker_mz"
    GameEngine.RPG_MAKER_VX_ACE -> "rpgmaker_vxace"
    GameEngine.KIRIKIRI -> "kirikiri"
}
