package dev.droidtop.library

import java.io.File

/**
 * Where one game's scraped media LIVES, as three strings -- not what it
 * has. This is the whole data-model answer to `<imageType>`.
 *
 * The problem it solves: a [LibraryEntry] carried exactly one
 * already-resolved `artworkUri`, so every themed element showed the same
 * picture no matter which media type the theme asked for. The obvious fix
 * -- resolving every media type per game at scan time -- is the wrong one
 * on this target: a library of hundreds of ROMs would pay ten folders
 * times three extensions times two candidate roots of `stat` per entry,
 * per scan, to answer a question almost every element never asks.
 *
 * So the entry carries the COORDINATES instead. Building one is free at
 * every scan site: the games root, system id and base name are all
 * already in hand there (they are the same three arguments
 * [EsDeArtwork.resolve] is being called with anyway), and constructing it
 * touches the filesystem zero times. The lookup then happens where the
 * question is actually asked -- in a theme element that declared an
 * `imageType`, for the handful of games currently on screen, memoised by
 * the renderer for as long as that element is composed. That is also what
 * real ES-DE does: GridComponent.h:490-522 resolves an entry's image path
 * lazily inside its render window and caches it on the entry, and is
 * capped at two image types precisely because the cost is per-entry
 * filesystem work.
 *
 * Null for a game with no ES-DE media layout behind it at all (a native
 * Android app, a store PC game whose art is a remote URL). Those keep
 * working exactly as before through [LibraryEntry.artworkUri].
 */
data class GameMediaLocator(
    /** The folder directly containing the per-system game folders, as [EsDeArtwork.resolve] means it. */
    val gamesRoot: String,
    val system: String,
    val baseName: String,
)

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
    // Real ES-DE's own list is png/jpg/webp (FileData.h:161
    // `sImageExtensions`). `webp` was missing here, so a library scraped
    // by real ES-DE with WebP output resolved nothing at all; `jpeg` is
    // droidtop's own extra tolerance for hand-placed media and stays.
    private val EXTENSIONS = listOf("png", "jpg", "webp", "jpeg")

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
        // Completed against real ES-DE's own FileData.cpp accessors
        // (get3DBoxPath -> "3dboxes" at FileData.cpp:381-385,
        // getBackCoverPath -> "backcovers" at FileData.cpp:387-391), which
        // this map was previously missing -- a theme asking for either got
        // silently skipped rather than resolved.
        "3dbox" to "3dboxes",
        "backcover" to "backcovers",
    )

    /**
     * Real ES-DE's `image` PSEUDO-TYPE. `image` is the one <imageType>
     * value that names no folder at all: FileData::getImagePath()
     * (FileData.cpp:360-379) is itself a fallback chain, trying miximage
     * first, then screenshot, then title screen, then cover. It is
     * therefore expanded here rather than looked up, and it is why the
     * carousel and grid -- which resolve a folder per type directly and
     * have no such accessor -- do not accept `image` at all.
     */
    private val IMAGE_PSEUDO_TYPE_CHAIN =
        listOf("miximages", "screenshots", "titlescreens", "covers")

    /**
     * Every scraped image for a game, in display order, labelled for a
     * viewer. [resolve] answers "the single best one and stop", which is
     * right for a theme element and useless for browsing what was
     * actually scraped.
     */
    fun allMedia(gamesRoot: File, system: String, romBaseName: String): List<Pair<String, String>> {
        val labels = mapOf(
            "miximages" to "Mix image",
            "covers" to "Box art",
            "screenshots" to "Screenshot",
            "titlescreens" to "Title screen",
            "marquees" to "Marquee",
            "physicalmedia" to "Physical media",
            "fanart" to "Fan art",
        )
        val candidateMediaRoots = listOf(
            File(gamesRoot.parentFile ?: gamesRoot, "ES-DE/downloaded_media"),
            File(gamesRoot, "downloaded_media"),
        )
        val found = mutableListOf<Pair<String, String>>()
        for (mediaType in MEDIA_TYPES_BY_PRIORITY) {
            for (mediaRoot in candidateMediaRoots) {
                val hit = EXTENSIONS.asSequence()
                    .map { ext -> File(File(File(mediaRoot, system), mediaType), "$romBaseName.$ext") }
                    .firstOrNull { it.isFile }
                if (hit != null) {
                    found += (labels[mediaType] ?: mediaType) to hit.absolutePath
                    break
                }
            }
        }
        return found
    }

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
     * Resolves one element's own parsed `<imageType>` list against one
     * game's media, the way real ES-DE's GamelistView::setGameImage does
     * (GamelistView.cpp:1255-1330): walk the theme's OWN declared order,
     * take the FIRST type that actually has a file on disk, and stop.
     *
     * Three things about this are load-bearing and are not what the
     * property name suggests:
     *
     *  * The fallback order is the THEME's order, not a fixed preference
     *    of ES-DE's. `marquee,cover` and `cover,marquee` are different
     *    requests and neither is "the" priority.
     *  * When NOTHING in the list resolves, real ES-DE calls
     *    `comp->setImage("")` -- the element shows its own `<default>`
     *    image if it declared one, and otherwise shows nothing. It
     *    specifically does NOT silently substitute the box art. Callers
     *    get null here and must honour that, not paper over it.
     *  * `image` expands to a chain of its own, see
     *    [IMAGE_PSEUDO_TYPE_CHAIN].
     *
     * `none` is not handled here -- it is not a media lookup at all but a
     * per-element instruction (draw the game's name as text in a
     * carousel/grid, suppress the static image in a video element), so it
     * belongs to the caller. Present-but-unrecognised types simply do not
     * match, matching [resolve]'s existing behaviour.
     *
     * Cost: this is the same stat-only walk [resolve] already does, but
     * bounded by what the theme asked for -- at most one folder per
     * declared type, times [EXTENSIONS], times the two candidate media
     * roots. It is deliberately NOT called during a library scan; see
     * [GameMediaLocator].
     */
    fun resolveImageTypes(locator: GameMediaLocator, imageTypes: List<String>): String? {
        if (imageTypes.isEmpty()) return null
        val gamesRoot = File(locator.gamesRoot)
        val candidateMediaRoots = listOf(
            File(gamesRoot.parentFile ?: gamesRoot, "ES-DE/downloaded_media"),
            File(gamesRoot, "downloaded_media"),
        )
        for (imageType in imageTypes) {
            val folders = when (val type = imageType.trim().lowercase()) {
                "image" -> IMAGE_PSEUDO_TYPE_CHAIN
                else -> listOfNotNull(IMAGE_TYPE_TO_FOLDER[type])
            }
            for (folder in folders) {
                for (mediaRoot in candidateMediaRoots) {
                    for (ext in EXTENSIONS) {
                        val candidate =
                            File(mediaRoot, "${locator.system}/$folder/${locator.baseName}.$ext")
                        if (candidate.isFile) return candidate.absolutePath
                    }
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

    private val VIDEO_EXTENSIONS = listOf("mp4", "avi", "mkv")

    /**
     * Real ES-DE `videos` media type (USERGUIDE.md's own documented
     * `downloaded_media` layout: `<system>/videos/<romname>.<ext>`) --
     * same two-candidate-root search as [resolve]/[resolveManual]. Real
     * ES-DE scrapers store MP4 almost exclusively; AVI/MKV are checked too
     * since nothing stops a user from hand-placing a differently-encoded
     * file here the same way [resolve]'s own PNG/JPG fallback list works.
     */
    fun resolveVideo(gamesRoot: File, system: String, romBaseName: String): String? {
        val candidateMediaRoots = listOf(
            File(gamesRoot.parentFile ?: gamesRoot, "ES-DE/downloaded_media"),
            File(gamesRoot, "downloaded_media"),
        )
        for (mediaRoot in candidateMediaRoots) {
            for (ext in VIDEO_EXTENSIONS) {
                val candidate = File(mediaRoot, "$system/videos/$romBaseName.$ext")
                if (candidate.isFile) return candidate.absolutePath
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
    GameEngine.RPG_MAKER_VX -> "rpgmaker_vx"
    GameEngine.RPG_MAKER_XP -> "rpgmaker_xp"
    // Matches players-database.json's real systemId for this engine
    // ("rpgmaker-2000-2003", the EasyRPG Player entry) exactly, unlike
    // the underscore convention every other engine bucket here uses --
    // deliberate, so EsDeArtwork/scraper lookups key on the same string
    // the player-resolution path already uses for this one engine.
    GameEngine.RPG_MAKER_2000_2003 -> "rpgmaker-2000-2003"
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
