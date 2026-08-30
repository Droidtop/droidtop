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
 * The single, shared media-lookup droidtop uses -- previously duplicated
 * (a separate `GameMediaResolver` existed briefly for
 * [dev.droidtop.library.consoles.ConsoleRomProvider] alone, assuming
 * `downloaded_media` sits directly under the ROMs root); merged back into
 * this one real implementation rather than carrying two silently-different
 * conventions. Real ES-DE installs place `downloaded_media` in two
 * different real places depending on setup (a sibling `ES-DE/` folder next
 * to the ROMs root -- ES-DE's own portable-install convention -- or
 * directly under the ROMs root itself), so [resolve] checks both rather
 * than assuming either one is *the* real layout.
 *
 * Media-type folder names (miximages, covers, screenshots, titlescreens,
 * marquees, physicalmedia, fanart) are ES-DE's own real `<imageType>`
 * values, cross-checked against the bundled DEcaffe theme's own theme.xml
 * (which references screenshot/cover/titlescreen/marquee/physicalmedia/
 * fanart directly) plus ES-DE's own documented default scrape set
 * (miximages -- its own generated composite hero image -- first, the
 * closest to what a background/detail-view card actually wants).
 *
 * [system] is whatever folder name droidtop scans a game under -- a real
 * console system id (see [dev.droidtop.library.consoles.ConsoleSystemDef])
 * for ROMs, or [GameEngine.esDeSystemName]'s own convention for engine
 * games (Ren'Py/RPG Maker/Kirikiri have no built-in ES-DE system, so this
 * is droidtop's own naming for those -- a user wanting droidtop and a real
 * ES-DE install to share scraped media for these would need a matching
 * custom system in their own es_systems.xml, ES-DE supports this per its
 * "Game system customizations" docs, not assumed to already exist).
 */
object EsDeArtwork {
    private val MEDIA_TYPES_BY_PRIORITY =
        listOf("miximages", "covers", "screenshots", "titlescreens", "marquees", "physicalmedia", "fanart")
    private val EXTENSIONS = listOf("png", "jpg", "jpeg")

    /**
     * Real ES-DE `<imageType>` values (singular, as a theme writes them --
     * e.g. DEcaffe's own `screenshot,cover,titlescreen`) to the real
     * on-disk media folder name (plural, matching [MEDIA_TYPES_BY_PRIORITY]
     * and ES-DE's own `downloaded_media` layout). Cross-checked against the
     * bundled DEcaffe theme.xml's own real `<imageType>` values, not
     * guessed at beyond that — an imageType this map doesn't recognize is
     * skipped by [resolve], not a parse/lookup failure.
     */
    private val IMAGE_TYPE_TO_FOLDER = mapOf(
        "miximage" to "miximages",
        "cover" to "covers",
        "screenshot" to "screenshots",
        "titlescreen" to "titlescreens",
        "marquee" to "marquees",
        "physicalmedia" to "physicalmedia",
        "fanart" to "fanart",
    )

    /**
     * [gamesRoot] is the folder directly containing the per-system ROM/game
     * folders (e.g. `.../Roms`, the parent of `.../Roms/nes`). Checks the
     * sibling `ES-DE/downloaded_media` layout first, then a direct
     * `downloaded_media` under [gamesRoot] itself.
     */
    fun resolve(gamesRoot: File, system: String, romBaseName: String): String? =
        resolve(gamesRoot, system, romBaseName, MEDIA_TYPES_BY_PRIORITY.mapNotNull { folder ->
            IMAGE_TYPE_TO_FOLDER.entries.firstOrNull { it.value == folder }?.key
        })

    /**
     * Same real lookup, but walking [imageTypes] in the CALLER's own order
     * instead of [MEDIA_TYPES_BY_PRIORITY]'s fixed priority — for a
     * gameselector-driven element honoring its own theme-declared
     * `<imageType>screenshot,cover,titlescreen</imageType>` ordering rather
     * than droidtop's own generic default. Falls back to
     * [MEDIA_TYPES_BY_PRIORITY] if [imageTypes] is empty or contains
     * nothing this crate recognizes, same as the no-imageTypes overload.
     */
    fun resolve(gamesRoot: File, system: String, romBaseName: String, imageTypes: List<String>): String? {
        val folders = imageTypes.mapNotNull { IMAGE_TYPE_TO_FOLDER[it.trim().lowercase()] }
            .ifEmpty { MEDIA_TYPES_BY_PRIORITY }
        val candidateMediaRoots = listOf(
            File(gamesRoot.parentFile ?: gamesRoot, "ES-DE/downloaded_media"),
            File(gamesRoot, "downloaded_media"),
        )
        for (mediaRoot in candidateMediaRoots) {
            for (mediaType in folders) {
                for (ext in EXTENSIONS) {
                    val candidate = File(mediaRoot, "$system/$mediaType/$romBaseName.$ext")
                    if (candidate.isFile) return candidate.absolutePath
                }
            }
        }
        return null
    }

    /**
     * Real ES-DE `manuals` media type (confirmed via USERGUIDE.md's own
     * documented `downloaded_media` layout: `<system>/manuals/<romname>.pdf`)
     * -- deliberately NOT a per-game metadata field (confirmed against
     * `MetaData.cpp`'s real `gameDecls` table, which has no "manual" key
     * at all); `BadgeComponent`'s own real "manual" badge slot is driven
     * by whether this file exists, same as [resolve]'s own artwork-
     * presence check, not a stored flag. Same two-candidate-root search
     * as [resolve].
     */
    fun resolveManual(gamesRoot: File, system: String, romBaseName: String): String? {
        val candidateMediaRoots = listOf(
            File(gamesRoot.parentFile ?: gamesRoot, "ES-DE/downloaded_media"),
            File(gamesRoot, "downloaded_media"),
        )
        for (mediaRoot in candidateMediaRoots) {
            val candidate = File(mediaRoot, "$system/manuals/$romBaseName.pdf")
            if (candidate.isFile) return candidate.absolutePath
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
    GameEngine.AUGUST -> "august"
    GameEngine.BURIKO -> "buriko"
    GameEngine.CATSYSTEM2 -> "catsystem2"
    GameEngine.CMVS -> "cmvs"
    GameEngine.FLASH_AIR -> "flash_air"
    GameEngine.GODOT -> "godot"
    GameEngine.TWINE -> "twine"
    GameEngine.UNREAL -> "unreal"
    GameEngine.UNITY -> "unity"
}
