package dev.droidtop.library

import android.util.Log
import java.io.File
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch as coroutineLaunch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One "installed thing," modeled after Playnite's plugin architecture: a
 * native Android app, a Wine profile, and a Linux-container app are all
 * equally first-class entries here, not three different UI code paths.
 *
 * This is the seam a future launcher shell (:shell-gamepad) reads from —
 * building it this way now, even though that shell doesn't exist yet, is
 * the point: the library model needs to already be launcher-ready
 * (metadata, artwork, playtime) so bolting on a gamepad-console UI later is
 * a new shell module, not a rearchitecture.
 */
@Serializable
data class LibraryEntry(
    val id: String,
    val title: String,
    val kind: LibraryEntryKind,
    val artworkUri: String? = null,
    // Real ES-DE `manuals` media presence -- see
    // dev.droidtop.library.EsDeArtwork.resolveManual's own doc comment
    // for why this is filesystem-derived (same as [artworkUri]) rather
    // than a GameMetadataEntity field; badge-consumed by EsDeThemedBadges'
    // own "manual" slot.
    val manualUri: String? = null,
    // Real ES-DE `videos` media presence -- same filesystem-derived
    // convention as [manualUri] (see EsDeArtwork.resolveVideo's own doc
    // comment); consumed by the theme renderer's "video" element to play
    // a real gameplay-preview clip instead of falling back to a static
    // image when one exists.
    val videoUri: String? = null,
    // Where this game's scraped media lives, so a themed element can ask
    // for the media type it actually declared (`<imageType>marquee</...>`)
    // instead of every element getting the one pre-resolved [artworkUri].
    // Coordinates only -- see [GameMediaLocator] for why this is not a
    // resolved per-type map, and [mediaForImageTypes] for the lookup.
    // Null for entries with no ES-DE media layout behind them (native
    // apps, store PC games with remote art), which keep using [artworkUri].
    val mediaLocator: GameMediaLocator? = null,
    val playtimeSeconds: Long = 0,
    val lastPlayedEpochMs: Long? = null,
    // Real, persisted count of how many times this entry has actually been
    // launched via Library.launch() -- see PlayHistoryStore/PlayHistoryDatabase.
    val playCount: Int = 0,
    // Only set for LibraryEntryKind.CONSOLE_ROM -- the real console system
    // id (see dev.droidtop.library.consoles.ConsoleSystemDef), since
    // CONSOLE_ROM alone doesn't distinguish NES from GBA from PS1 the way
    // separate LibraryEntryKinds distinguish RENPY from RPG_MAKER_MV.
    // shell-gamepad's Games section still buckets every CONSOLE_ROM entry
    // under one "Consoles" system-list card for now, not per real console
    // -- real per-system browsing (using this field) is the next UI step,
    // not built in this pass.
    val systemId: String? = null,
    // Real per-game metadata -- field set and conventions confirmed
    // directly against real ES-DE source (es-app/src/MetaData.cpp's own
    // `gameDecls` table, cloned locally at /root/es-de-reference for
    // ongoing reference), not guessed:
    //   desc -> description, developer, publisher, genre, players (all
    //   MD_STRING/MD_MULTILINE_STRING, real scraped fields)
    //   releasedate -> releaseDate (MD_DATE)
    //   rating -> rating (MD_RATING)
    // Real ES-DE's own ScreenScraper.cpp confirmed no ESRB/age-rating
    // field exists anywhere in its schema at all -- an earlier version of
    // this list guessed one; dropped, not real ES-DE parity.
    // Populated by a real scrape -- ScreenScraper/TheGamesDB/libretro for
    // console ROMs (Scrape.kt), Lutris/IGDB for PC and engine games
    // (PcScrape.kt) -- and read back onto every entry by
    // withScrapedMetadata. Null/default until a user actually scrapes,
    // same as artworkUri already was.
    val description: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val genre: String? = null,
    // Real ES-DE MD_DATE default/format: "YYYYMMDDT000000" (see
    // DateTimeComponent's own real format handling, already ported in
    // EsDeThemedClock's strftimeToJavaPattern) -- stored as the theme's
    // own real raw string rather than a parsed Date, since that's what a
    // <datetime metadata="releasedate"> element's own real format
    // property expects to format directly.
    val releaseDate: String? = null,
    // Real ES-DE MD_RATING convention (confirmed via ScreenScraper.cpp's
    // own real computation): 0.0-1.0, rounded to the nearest 0.1 --
    // ScreenScraper's own real 0-20 "note" score divided by 20 then
    // rounded, not a 0-5/0-10 star count.
    val rating: Float? = null,
    // Real ES-DE MD_STRING "players" key -- a free-form string ("1",
    // "1-2", "1-4"), not a parsed integer/range type, matching how real
    // scraper sources themselves report it.
    val players: String? = null,
    val favorite: Boolean = false,
    // The rest of real ES-DE's own per-GAME metadata field set (see
    // dev.droidtop.library.consoles.GameMetadataEntity's own doc comment
    // for the full real MetaData.cpp cross-reference and which fields
    // are deliberately NOT modeled here, and why) -- user-editable via
    // dev.droidtop.shell.gamepad's GameMetadataEditor, badge-consumed by
    // EsDeThemedBadges.
    val completed: Boolean = false,
    val kidGame: Boolean = false,
    val hidden: Boolean = false,
    val broken: Boolean = false,
    val noGameCount: Boolean = false,
    val noMultiScrape: Boolean = false,
    val hideMetadata: Boolean = false,
    // Real ES-DE MD_CONTROLLER convention: one of BadgeComponent.cpp's
    // own real controller shortNames (e.g. "gamepad_generic",
    // "joystick_arcade_4_buttons") -- see EsDeControllers.kt for the
    // full real, ported list.
    val controllerShortName: String? = null,
    // Which registered LibraryProvider/launch mechanism this specific
    // game should use instead of its system's real default -- droidtop's
    // own real equivalent of ES-DE's MD_ALT_EMULATOR, since droidtop has
    // no RetroArch-core concept, only distinct LibraryProviders per
    // launch mechanism.
    val altEmulator: String? = null,
    // Real ES-DE MD_SCREEN field, applied to droidtop's own actual
    // multi-display work -- an index into whichever real display outputs
    // dev.droidtop.app.DisplayOutputRepository currently reports, null
    // meaning "no override, use the real current default."
    val launchScreen: Int? = null,
    val sortName: String? = null,
    // Real ES-DE MD_STRING field, stored but not yet consumed -- real
    // custom collections now exist (CollectionEntity/
    // CollectionMemberEntity), this specifically needs a real per-
    // collection sort-order UI/consumer, a smaller, separate follow-up.
    val collectionSortName: String? = null,
    // Real ES-DE badge "collection" slot data (confirmed against
    // BadgeComponent.cpp's own real SLOT_COLLECTION) -- true when this
    // game is a member of at least one real custom collection
    // (CollectionMemberEntity), computed at library-merge time
    // (ConsoleRomProvider.withMetadata's own reverse-membership query),
    // not stored directly. Real ES-DE's own "folder" slot -- whether
    // this game sits inside a real gamelist SUBFOLDER -- stays
    // unmodeled: droidtop's ROM scan has no folder/subdirectory concept
    // at all, a genuinely separate, bigger gap than collections was.
    val inCollection: Boolean = false,
    // Set only for PC games (store-owned or folder-scanned); null for
    // ROMs, native apps and engine games. See [PcInfo].
    val pcInfo: PcInfo? = null,
    /**
     * The walk that used to find this game no longer does, and the
     * library kept it anyway (docs/SPEC.md 7g): shown as "broken -
     * missing", with its favourite, play history, metadata and
     * collection memberships intact, and no Play offered. It clears the
     * moment a walk finds the same path again.
     *
     * NOT [broken], which is real ES-DE `broken` metadata -- the user's
     * own statement that a game does not work. This is droidtop's own
     * statement that the folder is not there. A game can be both, and
     * conflating them would let a walk overwrite something only the user
     * can say.
     */
    val missing: Boolean = false,
    /**
     * The game the user said this folder is, by making two entries one
     * game (docs/SPEC.md 7m, "The same game"); null is the name the
     * folder's own name derives. A library fact ([GameLinksStore]),
     * joined when the library publishes, like play history: no walk
     * writes it.
     */
    val gameName: String? = null,
    /**
     * The F95zone thread the user linked to this game, by id (docs/SPEC.md
     * 7g, "Where an update comes from"). A library fact, like [gameName].
     */
    val f95Thread: Long? = null,
    /**
     * The newest version the update source last reported for [f95Thread],
     * as it wrote it; null until it has been asked. A library fact.
     */
    val latestKnown: String? = null,
    /**
     * Set only on the entry a list draws for a whole game
     * ([LibraryGameGroup.displayEntry]): the version a source knows of
     * that none of the game's folders is ([GroupedGame.availableUpdate]).
     * One folder cannot know this; the game can.
     */
    val availableUpdate: String? = null,
    // The rest of a game's scraped flavour (docs/SPEC.md 7h), read back
    // by withScrapedMetadata like the ES-DE fields above: its series, its
    // links, and which source each field came from.
    val series: String? = null,
    val links: List<GameLink> = emptyList(),
    val fieldSources: Map<String, String> = emptyMap(),
    // Scraped hero art (ES-DE `fanart`), logo (ES-DE `marquee`) and icon
    // for an entry with no ES-DE layout behind it (a store install);
    // [mediaForImageTypes] answers a theme's fanart and marquee from them.
    val heroUri: String? = null,
    val logoUri: String? = null,
    val iconUri: String? = null,
) {
    /**
     * The image file this entry should show for a themed element that
     * declared [imageTypes], or null if it has none of them.
     *
     * Real ES-DE semantics, ported from GamelistView::setGameImage
     * (GamelistView.cpp:1255-1330): first declared type that exists wins,
     * and "none of them exist" means NO IMAGE -- the element falls back to
     * its own `<default>`, not to the box art. Callers must therefore
     * distinguish "the theme asked for a type" (honour the null) from "the
     * theme asked for nothing" (keep using [artworkUri], droidtop's
     * existing single-artwork default), which is what
     * [EsDeImageTypes.forImageElement] returning an empty list expresses.
     *
     * Returns null for an entry with no [mediaLocator] unless it has the
     * scraped media named below, for the same reason: an entry with no ES-DE media layout genuinely
     * has no marquee, and pretending otherwise by handing back
     * [artworkUri] would put the wrong picture in a marquee slot.
     *
     * A store install's scraped hero and logo ([heroUri], [logoUri]) are
     * its `fanart` and `marquee`: they are exactly those types, filed in
     * the metadata row because the entry has no layout to find them in.
     */
    fun mediaForImageTypes(imageTypes: List<String>): String? {
        val locator = mediaLocator
            ?: return imageTypes.firstNotNullOfOrNull { type ->
                when (type) {
                    "fanart" -> heroUri
                    "marquee" -> logoUri
                    else -> null
                }
            }
        return EsDeArtwork.resolveImageTypes(locator, imageTypes)
    }
}

/**
 * One of a game's links, as its source names it ("Official website",
 * "Steam", "Wikipedia"). Stored in the metadata row as JSON.
 */
@Serializable
data class GameLink(val label: String, val url: String) {
    companion object {
        fun encode(links: List<GameLink>): String? {
            if (links.isEmpty()) return null
            val array = org.json.JSONArray()
            links.forEach { array.put(org.json.JSONObject().put("label", it.label).put("url", it.url)) }
            return array.toString()
        }

        fun decode(json: String?): List<GameLink> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = org.json.JSONArray(json)
                (0 until array.length()).mapNotNull { i ->
                    val row = array.optJSONObject(i) ?: return@mapNotNull null
                    val url = row.optString("url", "").ifBlank { null } ?: return@mapNotNull null
                    GameLink(row.optString("label", "").ifBlank { url }, url)
                }
            }.getOrDefault(emptyList())
        }
    }
}

/**
 * Facts that only a PC game from a store or a scanned folder has, kept
 * as one nested value rather than four loose fields, so [LibraryEntry]'s
 * own vocabulary stays ES-DE's metadata schema (docs/SPEC.md §7g).
 *
 * Produced by `:runtime-windows`'s `PcLibrary` from the vendored
 * gamenative-tux data layer. Null for everything that is not a PC game.
 */
@Serializable
data class PcInfo(
    /** Display name of where it came from: "Steam", "GOG", "Epic", "Amazon", "Folder". */
    val source: String,
    /**
     * The id the PC provider identifies this game by ("steam:440"),
     * carried on the entry rather than being the entry's own
     * [LibraryEntry.id].
     *
     * Why it exists: when engine detection claims a store-installed
     * game the entry that survives is the ENGINE one, keyed by the
     * game's folder (see [StoreInstall]). The store's own identity is
     * still real and still needed -- it is how a previous scrape of the
     * PC entry is found again, and the only stable handle back to the
     * store row -- so it travels with the surviving entry instead of
     * disappearing with the suppressed one.
     */
    val storeId: String? = null,
    val installed: Boolean,
    /** On-disk size when installed, download size when not, 0 when unknown. */
    val sizeBytes: Long = 0,
    val installPath: String? = null,
    /**
     * Community compatibility reports, from gamenative's own game-runs
     * service.
     *
     * **Reference, never a gate** (directed 2026-09-01): droidtop shows
     * this and the user decides. It must never block a download, hide an
     * entry, or reorder the library on its own — it is other people's
     * results on other hardware, which makes it useful information and a
     * bad decision-maker.
     */
    val compatibility: PcCompatibility? = null,
)

/** @see PcInfo.compatibility — reference only. */
@Serializable
data class PcCompatibility(
    val averageRating: Float,
    val playableReports: Int,
    val gpuPlayableReports: Int,
    val hasBeenTried: Boolean,
    val reportedNotWorking: Boolean,
) {
    /**
     * One short line for a badge or detail row. Deliberately factual
     * ("3 of 5 reports playable") rather than a verdict ("works"), so the
     * user reads evidence and judges it themselves.
     */
    fun summary(): String = when {
        !hasBeenTried -> "No compatibility reports yet"
        reportedNotWorking && playableReports == 0 -> "Reported not working"
        playableReports > 0 -> {
            val stars = String.format("%.1f", averageRating)
            "$playableReports playable ${if (playableReports == 1) "report" else "reports"} - $stars/5"
        }
        else -> "Tried, no playable reports"
    }
}

/**
 * A game some PC store says is installed, at a real directory on this
 * device — the handful of facts engine detection needs in order to keep
 * a store game's store-side information when it claims that folder.
 *
 * The one ownership rule (docs/SPEC.md §7g): **if engine detection
 * recognises a store game's install directory, the engine entry owns
 * that game and the `pc` entry is suppressed.** A Ren'Py game runs
 * natively on enginehost; running its Windows build under Wine plus CPU
 * translation is strictly worse, and is the route that does not work on
 * this target today at all. The rule is evaluated from the FOLDER, by
 * both providers, using the same [GameEngineDetector.engineOwnsInstall]
 * call — never from whichever provider happened to return first, so the
 * same library deduplicates identically on every scan.
 *
 * Suppression alone would throw away everything the `pc` entry knew, so
 * this type is what carries that across: source, store id, installed
 * state, size, install path, community compatibility, and the store's
 * cover art. See [dev.droidtop.library.LibraryEntry.withStoreInstall].
 */
data class StoreInstall(
    val installDir: File,
    val pcInfo: PcInfo,
    /** The store's own cover/icon URL, used only when the folder has no ES-DE artwork. */
    val artworkUri: String? = null,
)

/**
 * The path key [StoreInstall]s and detected game folders are matched on.
 *
 * [File.absolutePath] rather than [File.getCanonicalPath]: canonicalising
 * touches the filesystem (symlink resolution) once per candidate folder,
 * which a scan over a real SD card cannot afford, and both sides of this
 * comparison already come from the same kind of absolute path. Trailing
 * separators are dropped because a store row's `installPath` may carry
 * one and a scanned directory never does.
 */
internal fun String.asInstallKey(): String? =
    takeIf { it.isNotBlank() }?.let { File(it).absolutePath.trimEnd(File.separatorChar) }

/**
 * [StoreInstall]s keyed by install directory, with ties broken
 * deterministically.
 *
 * Two store rows CAN name the same directory — the same game owned on
 * two stores, or a folder scan that also sees a store install. Taking
 * "whichever came first" would make the library depend on DAO iteration
 * order, which is exactly the kind of discovery-order non-determinism
 * that has bitten this project before, so the winner is the lowest
 * [PcInfo.storeId] and the result is the same on every scan.
 */
internal fun List<StoreInstall>.byInstallDir(): Map<String, StoreInstall> {
    val byDir = HashMap<String, StoreInstall>()
    for (install in this) {
        val key = install.installDir.absolutePath.asInstallKey() ?: continue
        val existing = byDir[key]
        if (existing == null || install.tieBreakKey() < existing.tieBreakKey()) byDir[key] = install
    }
    return byDir
}

private fun StoreInstall.tieBreakKey(): String = pcInfo.storeId ?: pcInfo.source

/** The [StoreInstall] a detected game folder belongs to, if any store claims it. */
internal fun Map<String, StoreInstall>.forFolder(folder: File): StoreInstall? =
    folder.absolutePath.asInstallKey()?.let { this[it] }

/**
 * Folds a suppressed `pc` entry's information into the engine entry that
 * claimed the same folder. Returns the entry untouched when [install] is
 * null, which is the normal case: a game in the user's own games folder
 * has no store behind it and must be unaffected by any of this.
 *
 * Identity and routing stay the engine entry's own — [LibraryEntry.id],
 * [LibraryEntry.title] and [LibraryEntry.kind] are what send this game
 * to enginehost, and [LibraryEntry.systemId] stays null rather than
 * becoming `"pc"` so the entry keeps grouping under its engine's system
 * rather than under the Wine-launched PC one. Everything that was only
 * ever known store-side comes across.
 */
internal fun LibraryEntry.withStoreInstall(install: StoreInstall?): LibraryEntry {
    if (install == null) return this
    return copy(
        pcInfo = pcInfo ?: install.pcInfo,
        // Local ES-DE media wins over a store CDN URL, same precedence
        // withScrapedMetadata already applies: what is on disk is the
        // live truth, the remote URL is the fallback.
        artworkUri = artworkUri ?: install.artworkUri,
    )
}

enum class LibraryEntryKind {
    NATIVE_ANDROID_APP,
    WINE_PROFILE,
    LINUX_CONTAINER_APP,

    /**
     * An app/window streamed from elsewhere rather than run locally —
     * launched via windowcast (droidtop's actual streaming system;
     * a separate, broader project, not a droidtop-owned module — see
     * docs/SPEC.md's remote-streaming note), not implemented in this repo.
     * Same LibraryEntry shape as anything else — the whole point of this
     * model is that "runs elsewhere and streams here" isn't a special case
     * in the UI.
     */
    REMOTE_STREAM,

    /**
     * A detected engine game — kind named after the engine, not any one
     * launcher, since the same engine can be reachable through several
     * real paths (see [GameLaunchStrategy]/[GameLaunchStrategyResolver]):
     * enginehost today, a Wine prefix or a Linux container where the
     * engine/export actually supports it.
     */
    RENPY,
    RPG_MAKER_MV,
    RPG_MAKER_MZ,
    RPG_MAKER_VX_ACE,
    RPG_MAKER_VX,
    RPG_MAKER_XP,

    /**
     * RPG Maker 2000/2003 — real, distinct detection signature
     * (`RPG_RT.exe`/`RPG_RT.ldb`, see [GameEngineDetector]'s own doc
     * comment) from the MV/MZ/VX Ace family above; its primary real
     * launch path is the EasyRPG Player entries in players-database.json
     * (systemId "rpgmaker-2000-2003"), not a dedicated
     * [GameLaunchStrategy] the way Kirikiroid2 is for [KIRIKIRI].
     */
    RPG_MAKER_2000_2003,
    KIRIKIRI,

    /**
     * Visual-novel-adjacent engines with real, verified detection
     * signatures (ported from the user's own Pythia project, see
     * [GameEngineDetector]'s doc comment) but no dedicated launch
     * strategy of their own yet — same generic
     * [GameLaunchStrategy.WINE_PREFIX]/[GameLaunchStrategy.LINUX_CONTAINER]
     * resolution every engine gets, no enginehost/Kirikiroid2-style
     * dedicated interpreter for any of these.
     */
    AUGUST,
    BURIKO,
    CATSYSTEM2,
    CMVS,
    FLASH_AIR,
    GODOT,
    HTML,
    UNREAL,
    UNITY,

    /**
     * A real console ROM (NES, GBA, PS1, ...), launched via
     * [dev.droidtop.library.consoles.ConsoleRomProvider] -- see that
     * class's own doc comment for the real system list (generated from
     * the open-source ES-DE project's own es_systems.xml) and launch
     * mechanism (an am-start-style [dev.droidtop.library.consoles.Player]).
     */
    CONSOLE_ROM,
}

/**
 * The library's one split between apps and games, read by every surface
 * that shows one and not the other: the Gaming shell's Apps and Games
 * sections and the Launcher's Games screen. Keyed the same everywhere, so
 * [Library.backgroundScanState] hands every surface the same scan.
 */
object LibraryKinds {
    // Apps are what is NOT a game. A Wine profile and a Linux-container
    // game used to live here, which is why the PC surface -- the declared
    // home of "every PC and engine game" (DECISIONS 2026-09-10 17:05) --
    // could never be handed a store or Wine title: the Games section never
    // saw one. They are games; the PC card is where they belong.
    val APPS: Set<LibraryEntryKind> = setOf(
        LibraryEntryKind.NATIVE_ANDROID_APP,
        LibraryEntryKind.REMOTE_STREAM,
    )

    /**
     * Emulated/interpreted content -- droidtop's equivalent of ES-DE's
     * "systems." THE COMPLEMENT on purpose: this used to be a hand-kept
     * list of four engine kinds, which silently dropped every OTHER engine
     * (KiriKiri, RM2000/2003, Buriko, CatSystem2, CMVS, Flash, Godot,
     * HTML, ...) -- a new engine kind now lands in Games automatically
     * instead of nowhere.
     */
    val GAMES: Set<LibraryEntryKind> = LibraryEntryKind.entries.toSet() - APPS
}

/**
 * User-facing group/category name for a [LibraryEntryKind] — shared by the
 * Gaming shell's section grouping and the second-screen companion panel
 * (moved here from a private GamepadShell copy so both surfaces name kinds
 * identically instead of each keeping its own mapping).
 */
/**
 * The one line under an entry's name that says what that entry IS: what
 * the thing itself declares (an installed app's own Android application
 * category, a scraped game's genre) and, when it declares nothing, what
 * one entry of its kind is called. Never the name of the group it is
 * filed under — that is the heading directly above it.
 */
fun LibraryEntry.kindLine(): String = genre?.takeIf { it.isNotBlank() } ?: kind.itemName()

/**
 * What ONE entry of this kind IS, in the singular — the line under a
 * tile's name, which has to say what the thing is rather than repeat the
 * heading it sits under. [displayName] is the name of the GROUP ("Apps",
 * "Visual Novels"); using it on the tile made all eighteen app tiles read
 * "Apps" two lines below a heading that already said so (rig, build 547).
 *
 * Only the kinds whose group name is not already a singular statement
 * differ; the rest are the same word either way, and are written out here
 * rather than defaulted so a new kind has to answer both questions.
 */
fun LibraryEntryKind.itemName(): String = when (this) {
    LibraryEntryKind.NATIVE_ANDROID_APP -> "Android app"
    LibraryEntryKind.WINE_PROFILE -> "Windows game"
    LibraryEntryKind.LINUX_CONTAINER_APP -> "Linux app"
    LibraryEntryKind.REMOTE_STREAM -> "Remote PC"
    LibraryEntryKind.CONSOLE_ROM -> "Console game"
    LibraryEntryKind.RENPY, LibraryEntryKind.KIRIKIRI,
    LibraryEntryKind.AUGUST, LibraryEntryKind.BURIKO, LibraryEntryKind.CATSYSTEM2,
    LibraryEntryKind.CMVS, LibraryEntryKind.FLASH_AIR,
    -> "Visual novel"
    LibraryEntryKind.RPG_MAKER_MV, LibraryEntryKind.RPG_MAKER_MZ, LibraryEntryKind.RPG_MAKER_VX_ACE,
    LibraryEntryKind.RPG_MAKER_VX, LibraryEntryKind.RPG_MAKER_XP,
    LibraryEntryKind.RPG_MAKER_2000_2003,
    -> "RPG Maker game"
    LibraryEntryKind.GODOT, LibraryEntryKind.UNREAL, LibraryEntryKind.UNITY,
    LibraryEntryKind.HTML,
    -> "PC game"
}

fun LibraryEntryKind.displayName(): String = when (this) {
    LibraryEntryKind.NATIVE_ANDROID_APP -> "Apps"
    LibraryEntryKind.WINE_PROFILE -> "Windows"
    LibraryEntryKind.LINUX_CONTAINER_APP -> "Linux"
    LibraryEntryKind.REMOTE_STREAM -> "Remote PC"
    LibraryEntryKind.RENPY, LibraryEntryKind.KIRIKIRI,
    LibraryEntryKind.AUGUST, LibraryEntryKind.BURIKO, LibraryEntryKind.CATSYSTEM2,
    LibraryEntryKind.CMVS, LibraryEntryKind.FLASH_AIR,
    -> "Visual Novels"
    LibraryEntryKind.RPG_MAKER_MV, LibraryEntryKind.RPG_MAKER_MZ, LibraryEntryKind.RPG_MAKER_VX_ACE,
    LibraryEntryKind.RPG_MAKER_VX, LibraryEntryKind.RPG_MAKER_XP,
    LibraryEntryKind.RPG_MAKER_2000_2003,
    -> "RPG Maker"
    LibraryEntryKind.CONSOLE_ROM -> "Consoles"
    // Not visual-novel-shaped — general game engines, kept as their own
    // section rather than folded into "Visual Novels" where they'd be a
    // real mismatch.
    // HTML moved here with the database's twine -> html rename: the row
    // now matches any game whose root holds a page, not Twine stories
    // alone, so "Visual Novels" would be wrong for most of what it catches.
    LibraryEntryKind.GODOT, LibraryEntryKind.UNREAL, LibraryEntryKind.UNITY,
    LibraryEntryKind.HTML,
    -> "PC Games"
}

/**
 * One source of [LibraryEntry] items — installed-APK scanning, a
 * runtime-windows Wine profile store, a runtime-linux-* container's app
 * list, or (eventually) a Steam/Epic/GOG-style external source. Each is a
 * plugin in the Playnite sense: the library aggregates across all
 * registered providers into one list. [kinds] is a set, not a single
 * value, because one physical provider can genuinely cover several kinds
 * — [EngineGameProvider] alone covers five different [GameEngine]s.
 */
interface LibraryProvider {
    val kinds: Set<LibraryEntryKind>
    suspend fun scan(): List<LibraryEntry>
    suspend fun launch(entry: LibraryEntry)

    /**
     * Whether this provider's last complete result is kept in the library
     * index ([LibraryIndexStore]) and shown at the next start instead of
     * walking again. True for everything that reads a filesystem: a games
     * root does not change between two starts of droidtop often enough to
     * pay a full walk every time, and the walk took two minutes on the
     * rig. False only for a source that is instant AND changes behind
     * droidtop's back -- the package manager's app list.
     */
    val indexed: Boolean get() = true

    /** The index key for this provider's slice; one provider, one slice. */
    val indexKey: String get() = this::class.java.simpleName

    /**
     * The walk, narrating what it finishes as it finishes it: one
     * [ScanStep.Segment] per part of the provider (a top-level folder of
     * a games root, a console system), and a [ScanStep.RootDone] when a
     * root has no more parts to walk.
     *
     * A [ScanStep.Segment] is a STATEMENT ABOUT THAT PART, not a growing
     * snapshot: it is everything that part holds now. That is what lets
     * [Library] replace one folder's entries in the index and leave the
     * rest alone, and what lets it tell a game the walk no longer finds
     * from a folder the walk has not reached yet (docs/SPEC.md 7g). The
     * providers that walk a filesystem emit per folder for the same
     * reason they always did -- a slow folder costs that folder -- see
     * their own doc comments and ScanBudget.
     *
     * The default wraps [scan] as one whole-provider segment, which is
     * the honest shape for a source that has no parts to answer in
     * (the package manager's app list).
     */
    fun scanProgressive(): Flow<ScanStep> = flow { emit(ScanStep.Segment(key = ScanStep.WHOLE, entries = scan())) }

    /**
     * Real, optional explicit "my ROMs/apps changed, look again" action --
     * default just re-runs [scanProgressive] (same behavior, no real
     * invalidation) for every provider with no persistent cache of its
     * own to invalidate. Only [dev.droidtop.library.consoles.ConsoleRomProvider]
     * overrides this for real, since it's the only provider with a
     * persistent scan cache ([dev.droidtop.library.consoles.RomDatabase])
     * a plain [scan]/[scanProgressive] call wouldn't otherwise re-walk.
     * The real, user-facing "Rescan library" Settings action calls this,
     * not [scanProgressive] -- see shell-gamepad's SettingsSection.
     */
    fun rescanProgressive(): Flow<ScanStep> = scanProgressive()

    /**
     * The slow rebuild pass (docs/SPEC.md 7g, step 4): like
     * [rescanProgressive], but a provider that can tell a changed part
     * from an unchanged one skips a part whose change stamp still matches
     * [knownMtimes] (a folder's own modification time for
     * [EngineGameProvider], a stamp over a system's folders for
     * [dev.droidtop.library.consoles.ConsoleRomProvider]), and pauses
     * [pauseMs] between the parts it does not skip -- "walk only changed
     * parts." The default, for every provider with no cheaper way to
     * tell changed from unchanged, is a plain
     * [rescanProgressive]: always correct, never wrongly skips, so a
     * provider that does not override this just costs what a full
     * rescan already costs -- never less, never wrong.
     */
    fun slowRebuildProgressive(knownMtimes: Map<PartRef, Long>, pauseMs: Long): Flow<ScanStep> = rescanProgressive()
}

/**
 * A provider that keeps facts of its own about an entry id, and can move
 * them when a missing game is folded into the game that replaced it
 * (docs/SPEC.md 7g).
 *
 * The fold moves everything the library knows about the old path to the
 * new one. Play history and favourites belong to [Library] itself and it
 * moves those; scraped metadata rows and collection memberships live in
 * the provider that owns that database
 * ([dev.droidtop.library.consoles.ConsoleRomProvider]), which is what
 * this asks for rather than [Library] reaching into a Room DAO it should
 * not know about.
 */
interface EntryFactsOwner {
    /**
     * Moves what this provider knows about [fromId] to [toId]. With
     * [keepSource] (two present games made one, [Library.mergeGames]) a
     * scraped row is copied and [fromId] keeps its own, because its folder
     * is still there; without it (a missing game folded away) it moves.
     */
    suspend fun moveEntryFacts(fromId: String, toId: String, keepSource: Boolean)
}

/** What [Library.launch] by id did: the game was dispatched, or why it was not, in words a person can read. */
sealed interface LaunchResult {
    data object Launched : LaunchResult
    data class Refused(val reason: String) : LaunchResult
}

class Library(
    private val providers: List<LibraryProvider>,
    private val playHistory: PlayHistoryStore = NoOpPlayHistoryStore,
    private val favorites: FavoritesStore = NoOpFavoritesStore,
    private val index: LibraryIndexStore = NoOpLibraryIndexStore,
    /** The per-game records (docs/SPEC.md 7g); a launch by id reads the one it needs first. */
    private val records: GameRecordStore = NoOpGameRecordStore,
    /** Asked before each slow round; false (battery saver) skips that round (docs/SPEC.md 7g). */
    private val slowRoundAllowed: () -> Boolean = { true },
    /** What the user said about a game (its name after a merge, its F95zone thread) and the update source's answers (docs/SPEC.md 7g, 7m). */
    private val links: GameLinksStore = NoOpGameLinksStore,
    /** Where a linked thread's newest version is asked (docs/SPEC.md 7g, "Where an update comes from"). */
    f95Api: F95CheckerApi = HttpF95CheckerApi,
) {
    private val updates = F95UpdateCheck(links, f95Api)

    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backgroundScanStates = ConcurrentHashMap<Set<LibraryEntryKind>, MutableStateFlow<List<LibraryEntry>?>>()

    /** Ids whose play history or favourite changed; a publishing scan asks about them again (see LibraryFacts). */
    private val changedFactIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val backgroundScanJobs = mutableMapOf<Set<LibraryEntryKind>, Job>()

    /**
     * Each provider's slice as the library holds it NOW, keyed by
     * [LibraryProvider.indexKey]: the one copy every walk merges its steps
     * into, every publication reads, and every index save writes
     * (docs/SPEC.md 7g, "one writer per provider").
     *
     * There used to be one private copy per running walk, each loaded at
     * the walk's start and each saved whole after every step. Two walks
     * of one provider at once (the slow pass and a rescan, which is
     * routine) then overwrote each other: a root the user removed came
     * back from the slow pass' stale copy, and a root the user added was
     * deleted from the index because the slow pass' copy had never held
     * it. Now every change to a slice happens under that provider's
     * [sliceLocks] entry, on the current slice, so no walk can undo
     * another's.
     */
    private val slices = ConcurrentHashMap<String, LibrarySlice>()
    private val sliceLocks = ConcurrentHashMap<String, Mutex>()

    /** What the play-history and favourites stores say about the ids the library has shown; one for every walk and publication. */
    private val facts = LibraryFacts()

    /**
     * Moves on every [keepOnlyRoots]. A walk remembers the value it
     * started under, and a step of it that belongs to a games root is
     * dropped if the roots changed since: that walk is reading the old
     * set, and its step would put a removed root's games back.
     */
    private val rootsGeneration = java.util.concurrent.atomic.AtomicInteger()

    /** Ordinary walks (a first scan, a rescan) running now. The slow pass never runs beside one. */
    private val activeWalks = MutableStateFlow(0)

    /** The slow pass' round in progress, cancelled by any ordinary walk that starts. */
    @Volatile
    private var slowRound: Job? = null

    // The slow rebuild pass' own dispatcher (docs/SPEC.md 7g, step 4):
    // a dedicated, single, MIN_PRIORITY thread rather than the shared
    // Dispatchers.IO pool every ordinary scan uses -- "run on
    // Dispatchers.IO at low thread priority" only means something real
    // if the walk work itself lands on a low-priority thread, not just
    // whichever coroutine happens to call collect() on the result.
    private val slowDispatcher: CoroutineDispatcher =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "droidtop-slow-rebuild").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }
        }.asCoroutineDispatcher()
    private val slowRebuildStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Every key [backgroundScanStates] holds; moves when a list is first asked for, so [observed] covers it. */
    private val stateKeys = MutableStateFlow<Set<Set<LibraryEntryKind>>>(emptySet())

    /**
     * Whether any surface collects one of the library's lists right now
     * (docs/SPEC.md 2c): a Gaming or Desktop shell on screen, the
     * Launcher's Games grid open. The slow pass runs only while this is
     * true, so a process whose every observer is gone -- Gaming turned
     * off, the grid closed -- walks no games root.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val observed: Flow<Boolean> = stateKeys.flatMapLatest { keys ->
        val counts = keys.mapNotNull { backgroundScanStates[it]?.subscriptionCount }
        if (counts.isEmpty()) flowOf(false) else combine(counts) { each -> each.any { it > 0 } }
    }.distinctUntilChanged()

    private fun stateFor(key: Set<LibraryEntryKind>): MutableStateFlow<List<LibraryEntry>?> =
        backgroundScanStates.getOrPut(key) { MutableStateFlow(null) }.also {
            if (key !in stateKeys.value) stateKeys.update { keys -> keys + setOf(key) }
        }

    /**
     * Process-owned scan results. The Library instance belongs to LibraryCore and
     * outlives every Activity/Compose screen, so collectors may come and go
     * without cancelling the filesystem walk that produces these values.
     */
    fun backgroundScanState(kinds: Set<LibraryEntryKind>): StateFlow<List<LibraryEntry>?> =
        stateFor(kinds.toSet()).asStateFlow()

    /**
     * Start (or explicitly restart) a scan in [scanScope], never in a UI
     * scope.
     *
     * [restart] is for the one case where joining a running scan would be
     * wrong: the set of folders being scanned has itself changed, so the
     * walk in flight is walking the OLD roots and its results are stale
     * whatever it finds. Everything else (a configuration change replaying
     * a rescan intent) joins the running job instead.
     */
    /** What [rebuildIndexFromRecords] did: the records it read, and the games on screen it kept without one. */
    data class IndexRebuild(val records: Int, val keptWithoutRecord: Int)

    /**
     * Rebuilds the index from the per-game records and shows the result,
     * without walking any folder: every running or remembered background
     * collection is restarted against the rebuilt index as a plain
     * (non-rescan) scan, which for indexed providers means "load and
     * publish", not "walk".
     *
     * A rebuild never shows a smaller library than the one on screen
     * (docs/SPEC.md 7g). It used to throw the in-memory slices away and
     * publish whatever the records alone produced, and a library whose
     * games mostly had no readable record went from 168 engine games to 6
     * until the next walk (rig, build 814). Each provider's rebuilt slice
     * is now [LibrarySlice.including] what that provider was showing, and
     * written back, which also writes the missing games' records from the
     * list; the next walk decides what is really gone, as it always does.
     */
    suspend fun rebuildIndexFromRecords(): IndexRebuild {
        val rebuilt = index.rebuildFromRecords()
        var kept = 0
        withContext(kotlinx.coroutines.NonCancellable) {
            for (provider in providers) {
                if (!provider.indexed) continue
                lockOf(provider).withLock {
                    // Nothing of this provider's is on screen yet: the
                    // rebuilt index is read the first time it is asked for.
                    val shown = slices[provider.indexKey] ?: return@withLock
                    val fresh = index.load(provider.indexKey) ?: LibrarySlice()
                    val union = fresh.including(shown)
                    kept += union.entries().size - fresh.entries().size
                    if (union != fresh) index.save(provider.indexKey, union)
                    slices[provider.indexKey] = union
                }
            }
        }
        if (kept > 0) {
            ScanLog.write("index: kept $kept shown games that had no readable record, and wrote their records")
        }
        for (kinds in backgroundScanStates.keys.toList()) {
            scanInBackground(kinds, rescan = false, restart = true)
        }
        return IndexRebuild(records = rebuilt, keptWithoutRecord = kept)
    }

    /**
     * Walks [kinds] again now and returns once that walk has finished, with
     * how many entries [kinds] then holds: "Rescan library", which reports
     * when it is done rather than starting a walk nobody can see. A walk
     * already in flight is replaced, because the person asked for a fresh one.
     */
    suspend fun rescanNow(kinds: Set<LibraryEntryKind>): Int {
        val key = kinds.toSet()
        scanInBackground(key, rescan = true, restart = true)
        synchronized(backgroundScanJobs) { backgroundScanJobs[key] }?.join()
        return stateFor(key).value?.size ?: 0
    }

    fun scanInBackground(
        kinds: Set<LibraryEntryKind>,
        rescan: Boolean = false,
        restart: Boolean = false,
        // Called once the walk this call started has read everything, and
        // never for a walk that was cancelled or died with the process, or
        // when this call only joined a walk already running.
        onFinished: (() -> Unit)? = null,
    ) {
        // "A low-priority pass after start" (docs/SPEC.md 7g, step 4):
        // hooked onto the first ordinary (non-rescan) scan a shell ever
        // asks for, rather than a new call site in :app. Deliberately
        // NOT scoped to [kinds]: shell-gamepad's Games and Apps sections
        // each run their own LaunchedEffect and race to be first (real,
        // confirmed on the rig -- APP_KINDS' effect has no Flow
        // indirection in front of it and consistently wins), so scoping
        // the slow pass to whichever kinds happened to call first would
        // silently leave the OTHER section's providers -- on a real
        // library, usually EngineGameProvider/ConsoleRomProvider, the
        // ones this step exists for -- never rebuilt at all.
        if (!rescan) startSlowRebuildOnce()
        val key = kinds.toSet()
        val state = stateFor(key)
        lateinit var job: Job
        job = scanScope.coroutineLaunch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val flow = if (rescan) rescanKindsProgressive(key) else scanKindsProgressive(key)
                flow.collect { state.value = it }
                onFinished?.invoke()
            } finally {
                synchronized(backgroundScanJobs) {
                    if (backgroundScanJobs[key] === job) backgroundScanJobs.remove(key)
                }
            }
        }
        synchronized(backgroundScanJobs) {
            val running = backgroundScanJobs[key]
            if (running?.isActive == true && restart) {
                // The roots changed under it: the walk in flight is
                // walking folders that are no longer the answer.
                running.cancel()
                backgroundScanJobs.remove(key)
            }
            val stillRunning = backgroundScanJobs[key]
            // A configuration change replays the Activity's rescan intent.
            // Joining the process-owned job is what makes that harmless;
            // cancelling/restarting here would still lose the folder currently
            // being scanned even though Compose no longer owns the coroutine.
            if (stillRunning?.isActive == true) {
                // A lazy coroutine is already attached to scanScope. Dispose
                // of this unused child rather than retaining it indefinitely.
                job.cancel()
                return
            }
            backgroundScanJobs[key] = job
            job.start()
        }
    }

    // Two real, separate bugs this fixes, found by actually reading how
    // Daijishō does the equivalent (its DaijishouSynchronizationModel):
    //
    // 1. Every provider's scan() does blocking File I/O with no dispatcher
    //    of its own -- called from a Composable's LaunchedEffect
    //    (shell-gamepad's GamepadShell), that would otherwise run on the
    //    Main dispatcher, freezing rendering and input alike. A real
    //    ROMs folder's "j2me" system directory alone had 18,126 entries.
    //
    // 2. providers.flatMap { it.scan() } runs every provider strictly
    //    sequentially with zero isolation -- if any single provider hangs
    //    or throws, the whole scan hangs or fails with no partial results
    //    ever surfacing, and there's no way to tell which provider is the
    //    problem. Daijishō's own real design (confirmed via its decompiled
    //    sources) never does this: it syncs platforms concurrently
    //    (maxConcurrentSynchronizationOfPlatforms, bounded 3-5 by CPU
    //    count) with each one independently tracked and individually
    //    error-handled, so one bad platform can't take the rest down.
    //    Here: each provider runs as its own coroutine, failures are
    //    caught and logged per-provider rather than propagating, and every
    //    other provider's results still come back.
    suspend fun scanAll(): List<LibraryEntry> = scanKinds(LibraryEntryKind.entries.toSet())

    /**
     * Real bug this fixes, reported directly: Apps was showing empty even
     * though NativeAppProvider itself completes almost instantly, because
     * [scanAll] combined every provider into one list and only ever
     * returned once *all* of them finished -- a slow ConsoleRomProvider
     * scan (a real SD card, real folder sizes) silently gated Apps' own,
     * already-ready results. shell-gamepad now calls this once per section
     * (Games' kinds, Apps' kinds) as two fully independent scans, so one
     * section's slow provider can never block another section's fast one
     * from ever rendering.
     */
    suspend fun scanKinds(kinds: Set<LibraryEntryKind>): List<LibraryEntry> = withContext(Dispatchers.IO) {
        val scanned = coroutineScope {
            providers
                .filter { provider -> provider.kinds.any { it in kinds } }
                .map { provider -> async { scanProviderSafely(provider) } }
                .awaitAll()
                .flatten()
        }
        withLibraryFacts(scanned)
    }

    /**
     * Starts the slow rebuild pass' recurring loop, once per process
     * (docs/SPEC.md 7g, step 4: "kept honest ... over time," not a
     * one-shot): a short delay after the FIRST ordinary scan starts, then
     * a round every [SLOW_REBUILD_INTERVAL_MS], so a games root someone
     * edits while droidtop keeps running (adds a game, deletes one) is
     * picked up without the user ever pressing "Rescan library." Covers
     * every provider, not just whichever kinds the FIRST
     * [scanInBackground] call happened to ask about -- see that call
     * site's own doc comment for the real race this avoids.
     *
     * Rounds run only while something [observed] the library
     * (docs/SPEC.md 2c): the loop waits for an observer before a round,
     * and the last observer leaving cancels the round in progress. It
     * used to run for the life of the process, so turning Gaming off
     * left a walk of every games root every 30 minutes behind it. An
     * observer coming back after [SLOW_REBUILD_RETURN_MS] or more away
     * gets a round at once rather than at the end of the interval (a
     * game copied over USB appears when the person comes back), and a
     * round [slowRoundAllowed] refuses (battery saver) is skipped.
     *
     * A round never runs beside an ordinary walk: it waits for every
     * walk in flight to finish, and a walk that starts during a round
     * cancels the round. On a first start, with no index yet, that walk
     * reads the whole library, and a round beside it used to read the
     * whole library a second time while the person was first looking at
     * it. See [runSlowRound] for what one round does.
     */
    private fun startSlowRebuildOnce() {
        if (!slowRebuildStarted.compareAndSet(false, true)) return
        scanScope.coroutineLaunch {
            delay(SLOW_REBUILD_START_DELAY_MS)
            while (true) {
                observed.first { it }
                // The update check rides the same clock and the same
                // conditions (observed, not in battery saver) but never
                // the same coroutine: it is network, a walk is disk, and
                // neither waits for the other (docs/SPEC.md 7g, "Where an
                // update comes from").
                if (slowRoundAllowed()) checkUpdatesInBackground()
                activeWalks.first { it == 0 }
                if (slowRoundAllowed()) runObservedRound()
                waitForNextRound()
            }
        }
    }

    /**
     * A round of update checks over the linked threads that are due, on
     * [scanScope] and never awaited by anything: a walk does not wait for
     * the network, and an answer reaches the lists when it arrives.
     */
    private fun checkUpdatesInBackground() {
        scanScope.coroutineLaunch { publishUpdates(updates.round()) }
    }

    /** Hands an update round's answers to every list, when it changed anything. */
    private suspend fun publishUpdates(outcome: F95UpdateCheck.Outcome?) {
        if (outcome == null || outcome.changedIds.isEmpty()) return
        changedFactIds += outcome.changedIds
        republish()
    }

    /**
     * What the user said about the game [ids] are the folders of, and what
     * its update source last answered: the thread, when one is linked.
     */
    suspend fun gameLinks(ids: Collection<String>): GameLinks? = withContext(Dispatchers.IO) {
        val all = links.getAll(ids)
        all.values.firstOrNull { it.f95Thread != null } ?: all.values.firstOrNull()
    }

    /**
     * Links [thread] to the game whose folders are [ids] -- every folder,
     * because a thread is the game's, not one version's -- or unlinks it
     * when [thread] is null, and asks about a newly linked thread at once
     * (docs/SPEC.md 7g). Returns why the ask failed, or null.
     */
    suspend fun linkF95Thread(ids: Collection<String>, thread: Long?): String? = withContext(Dispatchers.IO) {
        links.setF95Thread(ids, thread)
        changedFactIds += ids
        val outcome = thread?.let { updates.round(only = it) }
        changedFactIds += outcome?.changedIds.orEmpty()
        republish()
        outcome?.error
    }

    /**
     * Asks about [thread] now, a person's "Check now": the same round,
     * limited to one thread and to once a minute ([F95UpdateCheck]).
     * Returns why the ask failed, or null.
     */
    suspend fun checkF95ThreadNow(thread: Long): String? = withContext(Dispatchers.IO) {
        val outcome = updates.round(only = thread) ?: return@withContext "A check is already running; try again in a moment."
        publishUpdates(outcome)
        outcome.error
    }

    /** One round, cancelled by an ordinary walk starting or by the last observer leaving. */
    private suspend fun runObservedRound() = coroutineScope {
        val round = scanScope.coroutineLaunch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                runSlowRound()
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e("droidtop.Library", "Slow rebuild pass failed", t)
            }
        }
        slowRound = round
        val unobserved = coroutineLaunch {
            observed.first { !it }
            round.cancel()
        }
        // A walk that started between the wait and here found no
        // round to cancel; this round then yields to it instead.
        if (activeWalks.value == 0) round.start() else round.cancel()
        round.join()
        unobserved.cancel()
    }

    /**
     * Returns after [SLOW_REBUILD_INTERVAL_MS], or sooner when the
     * library goes unobserved and is observed again after
     * [SLOW_REBUILD_RETURN_MS] or more away.
     */
    private suspend fun waitForNextRound() {
        kotlinx.coroutines.withTimeoutOrNull(SLOW_REBUILD_INTERVAL_MS) {
            while (true) {
                observed.first { !it }
                val leftAt = System.nanoTime()
                observed.first { it }
                if ((System.nanoTime() - leftAt) / 1_000_000 >= SLOW_REBUILD_RETURN_MS) break
            }
        }
    }

    /**
     * One slow round: every provider the index already holds a slice for
     * is asked, through [LibraryProvider.slowRebuildProgressive], for the
     * parts whose change stamp moved since the index took it (fed from
     * [LibraryIndexStore.folderMtimes]). A round over an unchanged library
     * is a handful of folder `stat`s, not a walk.
     *
     * Not a provider with no slice (the ordinary walk is what reads a
     * provider the first time), and not one outside the index, whose
     * every ordinary scan already walks it (the app list).
     *
     * What a round finds reaches the shell through [republish], into
     * every list on screen that shows the provider's kinds -- it keeps
     * the lists honest, it does not add a second, separate view of them.
     * It runs on [slowDispatcher], one provider at a time.
     */
    private suspend fun runSlowRound() = withContext(slowDispatcher) {
        val generation = rootsGeneration.get()
        var changed = false
        var lastRepublishedAt = 0L
        for (provider in providers.filter { it.indexed }) {
            if (sliceOf(provider) == null) continue
            try {
                provider.slowRebuildProgressive(index.folderMtimes(provider.indexKey), SLOW_REBUILD_PAUSE_MS)
                    .collect { step ->
                        if (!applyStep(provider, step, generation)) return@collect
                        changed = true
                        if (step is ScanStep.Segment) facts.learn(step.entries)
                        val now = System.nanoTime() / 1_000_000
                        if (now - lastRepublishedAt >= PUBLISH_INTERVAL_MS) {
                            republish()
                            lastRepublishedAt = now
                        }
                    }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e("droidtop.Library", "Slow rebuild of ${provider::class.simpleName} failed", t)
            }
        }
        if (changed) republish()
    }

    /**
     * Every list the shell observes, rebuilt from the current slices. The
     * ordinary walks publish their own lists as they go; this is for a
     * change nothing else is publishing (the slow pass). A list nothing
     * has asked for yet is left for its own first scan.
     */
    private suspend fun republish() {
        facts.relearn(changedFactIds)
        for ((kinds, state) in backgroundScanStates) {
            if (state.value == null) continue
            state.value = facts.apply(entriesOf(providers.filter { provider -> provider.kinds.any { it in kinds } }))
        }
    }

    private fun entriesOf(matching: List<LibraryProvider>): List<LibraryEntry> =
        matching.flatMap { slices[it.indexKey]?.entries().orEmpty() }

    private fun lockOf(provider: LibraryProvider): Mutex = sliceLocks.getOrPut(provider.indexKey) { Mutex() }

    /** The current slice, read from the index the first time; call with [lockOf] held. */
    private suspend fun currentSlice(provider: LibraryProvider): LibrarySlice? =
        slices[provider.indexKey] ?: if (provider.indexed) {
            index.load(provider.indexKey)?.also { slices[provider.indexKey] = it }
        } else {
            null
        }

    /** [provider]'s current slice, or null when the index has none for it yet. */
    private suspend fun sliceOf(provider: LibraryProvider): LibrarySlice? =
        lockOf(provider).withLock { currentSlice(provider) }

    /**
     * Merges one finished [step] into [provider]'s current slice and
     * writes it, under the provider's lock. Returns whether the slice
     * changed.
     *
     * Not cancellable once it has the lock: a write that has started
     * finishes, so the slice in memory and the index on disk never
     * disagree about a step. A step of a walk that started before the
     * roots last changed is dropped when it belongs to a games root
     * ([rootsGeneration]).
     */
    private suspend fun applyStep(provider: LibraryProvider, step: ScanStep, walkGeneration: Int): Boolean =
        withContext(kotlinx.coroutines.NonCancellable) {
            lockOf(provider).withLock {
                if (step.root != null && walkGeneration != rootsGeneration.get()) return@withLock false
                val current = currentSlice(provider) ?: LibrarySlice()
                val next = current.merge(step)
                if (next == current) return@withLock false
                if (provider.indexed) index.save(provider.indexKey, next)
                slices[provider.indexKey] = next
                true
            }
        }

    /**
     * Real, streaming counterpart to [scanKinds] -- emits a growing
     * combined snapshot every time ANY matching provider produces more
     * results, instead of making the whole section wait for every
     * provider's single slowest part before rendering anything. Real
     * reported UX request this fixes: shell-gamepad's Games screen used
     * to render nothing but its loading spinner until the *entire* scan
     * across every root and every system folder finished -- with a large
     * real ROM collection, results the user could already be looking at
     * (nes/gba/psx, fast to scan) sat withheld behind whatever the
     * single slowest folder was doing. Per-provider results still
     * accumulate independently (one provider's own partial progress
     * never resets because another provider emitted), matching
     * [scanKinds]' own per-provider isolation.
     */
    fun scanKindsProgressive(kinds: Set<LibraryEntryKind>): Flow<List<LibraryEntry>> =
        libraryProgressive(kinds, rescan = false)

    /**
     * The same, but every matching provider walks again, whatever the
     * index holds -- the action behind Settings' "Rescan library" and the
     * one a changed root set forces. The index stays on screen while the
     * walk runs and is updated one finished folder at a time, so a rescan
     * never makes the library vanish or shrink to a partial.
     */
    fun rescanKindsProgressive(kinds: Set<LibraryEntryKind>): Flow<List<LibraryEntry>> =
        libraryProgressive(kinds, rescan = true)

    /**
     * THE LIBRARY IS AN INDEX; A WALK IS WHAT REFRESHES IT (docs/SPEC.md
     * 7g). Until 2026-09-17 nothing outside the console-ROM provider kept
     * a scan result, so every start of the process walked every games
     * root again and showed "No games detected yet." until it had: two
     * minutes on the rig, for a library that had not changed.
     *
     * Per matching provider: the index's slice is published at once when
     * there is one; a provider with no slice, a provider that is not
     * [LibraryProvider.indexed], or every provider on a [rescan] walks.
     *
     * THE WALK UPDATES THE INDEX A PART AT A TIME (directed 2026-09-17).
     * Every [ScanStep] a provider emits is merged into its slice the
     * moment it arrives -- that folder's entries replace that folder's
     * previous entries and nothing else changes -- and the merged slice
     * is both published and written. A game a walked folder no longer
     * holds is kept, marked [LibraryEntry.missing]; it is never dropped
     * because a walk did not see it. A walk that fails or is cancelled
     * leaves everything it had not reached alone, which is now most of
     * the slice rather than all of it.
     */
    private fun libraryProgressive(
        kinds: Set<LibraryEntryKind>,
        rescan: Boolean,
    ): Flow<List<LibraryEntry>> = channelFlow {
        val walkGeneration = rootsGeneration.get()
        val matchingProviders = providers.filter { provider -> provider.kinds.any { it in kinds } }
        val indexedSlot = BooleanArray(matchingProviders.size)
        matchingProviders.forEachIndexed { i, provider ->
            if (!provider.indexed) return@forEachIndexed
            val known = sliceOf(provider) ?: return@forEachIndexed
            indexedSlot[i] = true
            ScanLog.write(
                "index: ${provider.indexKey} ${known.entries().size} entries in ${known.segments.size} parts" +
                    if (rescan) ", walking again" else "",
            )
        }
        fun published(): List<LibraryEntry> = entriesOf(matchingProviders)
        // Play history and favourites are asked about ONCE for what the
        // index holds, and after that only for the ids a finished part
        // brings. Every finished part used to ask about every id in the
        // library again: a walk of F folders over N games made F queries of
        // N ids each (docs/SPEC.md 7g, "what was wrong underneath").
        facts.learn(published())
        // A walk finishes parts far faster than a person reads a list, and
        // every publication is the whole list for the shell to diff and
        // draw. Publish at most a few times a second while parts arrive,
        // and always once more when the walk ends, so the last state is
        // never the one that was skipped.
        var lastPublishedAt = 0L
        var unpublished = false
        val publishLock = Mutex()
        suspend fun publish(force: Boolean) = publishLock.withLock {
            val now = System.nanoTime() / 1_000_000
            if (!force && now - lastPublishedAt < PUBLISH_INTERVAL_MS) {
                unpublished = true
                return@withLock
            }
            if (!force || unpublished || lastPublishedAt == 0L) {
                facts.relearn(changedFactIds)
                send(facts.apply(published()))
                lastPublishedAt = now
                unpublished = false
            }
        }
        if (indexedSlot.any { it }) publish(force = true)
        val walking = matchingProviders.filterIndexed { slot, _ -> rescan || !indexedSlot[slot] }
        if (walking.isNotEmpty()) {
            activeWalks.update { it + 1 }
            slowRound?.cancel()
        }
        try {
            coroutineScope {
                walking.forEach { provider ->
                    coroutineLaunch {
                        // A provider outside the index starts from nothing
                        // each walk, as it always did: its walk is the
                        // whole answer, and an app that was uninstalled is
                        // gone, not missing.
                        if (!provider.indexed) lockOf(provider).withLock { slices[provider.indexKey] = LibrarySlice() }
                        // No whole-provider timeout, deliberately. There was
                        // one (60 s), and the rig showed exactly what it cost
                        // (build 523): one slow subtree under a games root ran
                        // past it and every game the provider had already
                        // found was discarded. A budget belongs to the unit of
                        // work it can bound, which is one folder, not one
                        // provider: see ScanBudget. A provider that never
                        // returns costs the parts it never reached and
                        // nothing else.
                        try {
                            val stream = if (rescan) provider.rescanProgressive() else provider.scanProgressive()
                            stream.collect { step ->
                                applyStep(provider, step, walkGeneration)
                                if (step is ScanStep.Segment) facts.learn(step.entries)
                                publish(force = false)
                            }
                        } catch (t: kotlinx.coroutines.CancellationException) {
                            throw t
                        } catch (t: Throwable) {
                            Log.e("droidtop.Library", "Provider ${provider::class.simpleName} failed to scan", t)
                        }
                    }
                }
            }
        } finally {
            if (walking.isNotEmpty()) activeWalks.update { it - 1 }
        }
        publish(force = true)
    }.flowOn(Dispatchers.IO)

    /**
     * What the play-history and favourites stores say about the ids the
     * library has seen, so that publishing the library again is a pass
     * over a list in memory and not two database queries over every id it
     * holds. One for the whole library: [changedFactIds] is consumed by
     * whichever publication asks first, so a copy per walk would leave
     * every other copy with the old answer.
     */
    private inner class LibraryFacts {
        private val history = java.util.concurrent.ConcurrentHashMap<String, PlayHistoryRecord>()
        private val favorite = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val linked = java.util.concurrent.ConcurrentHashMap<String, GameLinks>()
        private val asked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /**
         * Ids whose history or favourite changed since they were asked
         * about (a launch, a favourite toggled, a replacement folded in,
         * all possible while a walk is still publishing) are asked again.
         */
        suspend fun relearn(changed: MutableSet<String>) {
            val ids = changed.toList()
            if (ids.isEmpty()) return
            changed.removeAll(ids.toSet())
            val played = playHistory.getAll(ids)
            val favourites = favorites.getAll(ids)
            val said = links.getAll(ids)
            for (id in ids) {
                played[id]?.let { history[id] = it } ?: history.remove(id)
                if (id in favourites) favorite.add(id) else favorite.remove(id)
                said[id]?.let { linked[id] = it } ?: linked.remove(id)
            }
        }

        suspend fun learn(entries: List<LibraryEntry>) {
            val ids = entries.map { it.id }.filter { asked.add(it) }
            if (ids.isEmpty()) return
            history.putAll(playHistory.getAll(ids))
            favorite.addAll(favorites.getAll(ids))
            linked.putAll(links.getAll(ids))
        }

        fun apply(entries: List<LibraryEntry>): List<LibraryEntry> =
            entries.map { entry -> entry.withFacts(history[entry.id], entry.id in favorite, linked[entry.id]) }
    }

    /**
     * The user removed a games root, so its entries go: that is a choice
     * about what the library covers, not a drive that failed to mount,
     * and it is the one case where the index drops instead of marking
     * missing (docs/SPEC.md 7g).
     *
     * Called with the roots as they are NOW, by whoever noticed they
     * changed, rather than with the ones that went: the index knows which
     * root each part it holds belongs to, so "keep these" is the whole
     * instruction. A part under no root (a store's own database) is
     * never touched by it.
     */
    suspend fun keepOnlyRoots(rootPaths: Set<String>) = withContext(Dispatchers.IO) {
        // Every walk already running is reading the old roots: from here
        // on its steps under a games root are dropped (see applyStep), and
        // the caller starts the walk of the new set.
        rootsGeneration.incrementAndGet()
        slowRound?.cancel()
        val dropped = mutableSetOf<String>()
        for (provider in providers.filter { it.indexed }) {
            withContext(kotlinx.coroutines.NonCancellable) {
                lockOf(provider).withLock {
                    val slice = currentSlice(provider) ?: return@withLock
                    val kept = slice.keepOnlyRoots(rootPaths)
                    if (kept == slice) return@withLock
                    dropped += slice.entries().map { it.id } - kept.entries().map { it.id }.toSet()
                    index.save(provider.indexKey, kept)
                    slices[provider.indexKey] = kept
                    ScanLog.write("index: ${provider.indexKey} dropped the parts of roots that are no longer configured")
                }
            }
        }
        if (dropped.isNotEmpty()) {
            backgroundScanStates.values.forEach { state ->
                state.value = state.value?.filterNot { it.id in dropped }
            }
        }
    }

    /**
     * This detected game IS that missing one: fold the missing entry into
     * it (docs/SPEC.md 7g).
     *
     * Everything the library knows about the old path moves to the new
     * one -- play history, favourite, and whatever a provider keeps of
     * its own (scraped metadata rows, collection memberships, see
     * [EntryFactsOwner]) -- and the missing entry leaves the index. One
     * mechanism for both directions the UI offers it from: "this replaces
     * a missing game" on the game that is here, and "find its
     * replacement" on the game that is not.
     *
     * Returns false for a fold that is not one (an entry onto itself, or
     * an entry that is not missing), so a caller cannot quietly delete a
     * game that is present.
     */
    suspend fun replaceMissing(missing: LibraryEntry, replacement: LibraryEntry): Boolean = withContext(Dispatchers.IO) {
        if (!missing.missing || missing.id == replacement.id) return@withContext false
        playHistory.moveTo(missing.id, replacement.id)
        favorites.moveTo(missing.id, replacement.id)
        links.moveTo(missing.id, replacement.id)
        changedFactIds += listOf(missing.id, replacement.id)
        providers.filterIsInstance<EntryFactsOwner>().forEach { it.moveEntryFacts(missing.id, replacement.id, keepSource = false) }
        for (provider in providers.filter { it.indexed }) {
            withContext(kotlinx.coroutines.NonCancellable) {
                lockOf(provider).withLock {
                    val slice = currentSlice(provider) ?: return@withLock
                    val without = slice.without(missing.id)
                    if (without == slice) return@withLock
                    index.save(provider.indexKey, without)
                    slices[provider.indexKey] = without
                }
            }
        }
        backgroundScanStates.values.forEach { state ->
            val current = state.value ?: return@forEach
            state.value = withLibraryFacts(current.filterNot { it.id == missing.id })
        }
        ScanLog.write("index: ${missing.id} folded into ${replacement.id}")
        true
    }

    /**
     * Two games in the library are one game (docs/SPEC.md 7m, "The same
     * game"): every folder of [other] becomes a folder of [game], under
     * [game]'s name, and stays exactly where it is. Both are here, so both
     * stay playable, as versions or parts of one game. This is Pythia's
     * `reconciliation.merge`, kept as the one fact it changes: the name
     * the folders are grouped under ([GameLinksStore.setGameName]).
     *
     * What the two cards carried becomes the one card's, the way the fold
     * of a missing game does it ([replaceMissing]): play history (counts
     * added, the later last-played kept), the favourite and collection
     * memberships move from each card's entry to the entry the merged
     * game's card is. Unlike the fold, a scraped metadata row is only
     * COPIED into an empty place and never taken away, because the folder
     * it describes is still here.
     *
     * Returns false for a merge that is not one: a game with itself, or a
     * game that is not folders on this device (a store row's name is the
     * store's, not droidtop's to change).
     */
    suspend fun mergeGames(game: LibraryGameGroup, other: LibraryGameGroup): Boolean = withContext(Dispatchers.IO) {
        val keep = game.entriesByPath.keys
        val fold = other.entriesByPath.keys
        if (keep.isEmpty() || fold.isEmpty() || keep.any { it in fold }) return@withContext false
        if (!(keep + fold).all { it.startsWith("/") }) return@withContext false
        val name = game.game.name
        links.setGameName(fold, name)
        // The card the merged game will draw, worked out the way the list
        // will work it out, so the facts land on the entry that shows them.
        val merged = LibraryGrouping.group(game.entriesByPath.values + other.entriesByPath.values.map { it.copy(gameName = name) })
            .singleOrNull()
        val target = (merged ?: game).displayEntry.id
        for (source in setOf(game.displayEntry.id, other.displayEntry.id) - target) {
            playHistory.moveTo(source, target)
            favorites.moveTo(source, target)
            providers.filterIsInstance<EntryFactsOwner>().forEach { it.moveEntryFacts(source, target, keepSource = true) }
        }
        changedFactIds += keep + fold
        republish()
        ScanLog.write("index: ${fold.size} folder(s) of ${other.game.name} are now the game $name")
        true
    }

    private suspend fun scanProviderSafely(provider: LibraryProvider): List<LibraryEntry> = try {
        // Failures are isolated per provider; slowness is bounded per
        // FOLDER, inside the provider, by a ScanBudget. There used to be a
        // 15-second timeout here too, and it had the same defect as the
        // streaming one above: it could not preempt a blocking File I/O
        // call already in progress (so it never actually stopped the slow
        // work), and all it could do when it fired was throw away results
        // the provider had already produced.
        provider.scan()
    } catch (t: Throwable) {
        Log.e("droidtop.Library", "Provider ${provider::class.simpleName} failed to scan", t)
        emptyList()
    }

    /**
     * Recorded only after the provider's own [LibraryProvider.launch] call
     * returns without throwing -- a failed launch (a missing player, a
     * container session that isn't running, ...) must never be counted as
     * a real play. This is the one real signal available without deeper
     * OS-level foreground/process tracking (out of scope here, see
     * [PlayHistoryRecord]'s own doc comment): the launch was at least
     * successfully dispatched.
     */
    suspend fun launch(entry: LibraryEntry) {
        withContext(Dispatchers.IO) {
            // Which game this launch is for, published for the duration of
            // the provider call so LaunchDisplay can apply and record the
            // remembered launch screen (docs/SPEC.md section 4c) without
            // every provider signature carrying the entry through.
            // LaunchDisplay.start captures it synchronously even when the
            // chooser dialog defers the actual dispatch.
            LaunchDisplay.launchContext = LaunchContext(entry.id, entry.systemId)
            try {
                providers.first { entry.kind in it.kinds }.launch(entry)
            } finally {
                LaunchDisplay.launchContext = null
            }
            playHistory.recordPlay(entry.id, System.currentTimeMillis())
            changedFactIds += entry.id
        }
    }

    /**
     * Launches the game with [id], for a caller that holds only the id (a
     * pinned icon, the Desktop's Start menu). Never throws: whatever goes
     * wrong comes back as [LaunchResult.Refused] with a reason to show.
     *
     * Finding the game costs what the game costs, not what the library
     * costs (docs/SPEC.md 7g, "one file per game is the truth"): its
     * record first, then the index as the library holds it, and only then
     * a walk, of just the providers the index cannot answer for (the app
     * list, which is outside the index, and a provider the index has no
     * slice for yet). A provider whose slice does not list the id is not
     * walked: as of its last walk it does not hold the game, and a game
     * added since is the rescan's job. A game marked missing is refused;
     * its files are not where the library last found them.
     */
    suspend fun launch(id: String): LaunchResult = withContext(Dispatchers.IO) {
        val entry = try {
            find(id)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e("droidtop.Library", "Looking up $id to launch it failed", t)
            return@withContext LaunchResult.Refused("Couldn't look up that game (${t.message ?: t.javaClass.simpleName})")
        }
        when {
            entry == null -> LaunchResult.Refused("That game isn't in the library. If it was added recently, rescan the library.")
            entry.missing -> LaunchResult.Refused("${entry.title} is missing: its files aren't where the library last found them.")
            else -> try {
                launch(entry)
                LaunchResult.Launched
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e("droidtop.Library", "Launch of ${entry.title} failed", t)
                LaunchResult.Refused("${entry.title} could not be launched (${t.message ?: t.javaClass.simpleName})")
            }
        }
    }

    /**
     * [launch] by id in the library's own scope, so the launch outlives
     * whatever screen asked for it: the Start menu closes on the same tap.
     * [onResult] is called on the IO dispatcher.
     */
    fun launchInBackground(id: String, onResult: (LaunchResult) -> Unit = {}) {
        scanScope.coroutineLaunch { onResult(launch(id)) }
    }

    private suspend fun find(id: String): LibraryEntry? {
        records.get(id)?.let { return it.entry }
        slices.values.firstNotNullOfOrNull { slice -> slice.entries().firstOrNull { it.id == id } }?.let { return it }
        val unanswered = providers.filter { provider ->
            // Loads the slice from the index if nothing has asked yet.
            val slice = if (provider.indexed) sliceOf(provider) else null
            slice?.entries()?.firstOrNull { it.id == id }?.let { return it }
            slice == null
        }
        for (provider in unanswered) {
            scanProviderSafely(provider).firstOrNull { it.id == id }?.let { return it }
        }
        return null
    }

    /**
     * Real, user-driven favorite toggle -- see
     * [dev.droidtop.library.consoles.ConsoleRomProvider.toggleFavorite]'s
     * own doc comment. `favorite` is real ES-DE per-game metadata
     * (`EsDeThemedBadges`' own favorite-slot rendering, `GameMetadataEntity`),
     * a [dev.droidtop.library.consoles.ConsoleRomProvider]-specific concept
     * -- returns `null` (not an error) for any entry kind that isn't a
     * real console ROM, an honest "not applicable here" rather than
     * throwing for e.g. a native app or Wine profile entry.
     */
    suspend fun toggleFavorite(entry: LibraryEntry): Boolean? = withContext(Dispatchers.IO) {
        val romProvider = providers
            .filterIsInstance<dev.droidtop.library.consoles.ConsoleRomProvider>()
            .firstOrNull { entry.kind in it.kinds }
        if (romProvider != null) return@withContext romProvider.toggleFavorite(entry.id)
        // Every other kind: the library's own favourites (see
        // [FavoritesStore]). A game is a game whichever provider found it.
        val next = !entry.favorite
        favorites.setFavorite(entry.id, next)
        changedFactIds += entry.id
        next
    }

    /**
     * Real load-for-editing step behind [dev.droidtop.shell.gamepad]
     * `GameMetadataEditor` -- same "not applicable to a non-ROM entry"
     * `null` convention as [toggleFavorite].
     */
    suspend fun getMetadataForEditing(
        entry: LibraryEntry,
    ): dev.droidtop.library.consoles.GameMetadataEntity? = withContext(Dispatchers.IO) {
        val romProvider = providers
            .filterIsInstance<dev.droidtop.library.consoles.ConsoleRomProvider>()
            .firstOrNull { entry.kind in it.kinds } ?: return@withContext null
        romProvider.getMetadataForEditing(entry.id)
    }

    /**
     * Real save path behind [dev.droidtop.shell.gamepad]
     * `GameMetadataEditor` -- returns whether a matching provider was
     * actually found to save against (same honest-`null`-vs-throw
     * convention as [toggleFavorite]/[getMetadataForEditing]).
     */
    suspend fun saveMetadata(
        entry: LibraryEntry,
        metadata: dev.droidtop.library.consoles.GameMetadataEntity,
    ): Boolean = withContext(Dispatchers.IO) {
        val romProvider = providers
            .filterIsInstance<dev.droidtop.library.consoles.ConsoleRomProvider>()
            .firstOrNull { entry.kind in it.kinds } ?: return@withContext false
        romProvider.saveMetadata(metadata)
        true
    }

    // Real, library-wide collections -- unlike favorite/metadata, not
    // scoped to one entry's own kind (a collection can list any mix of
    // real console ROMs, engine games, etc.), so these grab the one real
    // ConsoleRomProvider directly rather than filtering by an entry's
    // kind. Returns real, honest empty/false defaults when no such
    // provider exists at all (same "not applicable" convention as
    // toggleFavorite), not a crash.
    private val romProvider: dev.droidtop.library.consoles.ConsoleRomProvider?
        get() = providers.filterIsInstance<dev.droidtop.library.consoles.ConsoleRomProvider>().firstOrNull()

    suspend fun getCollections(): List<dev.droidtop.library.consoles.CollectionEntity> =
        withContext(Dispatchers.IO) { romProvider?.getCollections() ?: emptyList() }

    suspend fun createCollection(name: String): dev.droidtop.library.consoles.CollectionEntity? =
        withContext(Dispatchers.IO) { romProvider?.createCollection(name) }

    suspend fun renameCollection(id: String, newName: String) {
        withContext(Dispatchers.IO) { romProvider?.renameCollection(id, newName) }
    }

    suspend fun deleteCollection(id: String) {
        withContext(Dispatchers.IO) { romProvider?.deleteCollection(id) }
    }

    /** Real add/remove toggle -- returns the real new membership state, `null` if no provider exists to toggle against. */
    suspend fun toggleCollectionMembership(collectionId: String, entry: LibraryEntry): Boolean? =
        withContext(Dispatchers.IO) { romProvider?.toggleCollectionMembership(collectionId, entry.id) }

    suspend fun isCollectionMember(collectionId: String, entry: LibraryEntry): Boolean =
        withContext(Dispatchers.IO) { romProvider?.isCollectionMember(collectionId, entry.id) ?: false }

    /** Real collectionId -> member gameIds map -- see [dev.droidtop.shell.gamepad]'s own `GameGroup.Collection` doc comment for how this feeds the system carousel. */
    suspend fun getCollectionMembership(): Map<String, List<String>> =
        withContext(Dispatchers.IO) { romProvider?.getCollectionMembership() ?: emptyMap() }

    /**
     * What the library itself knows about an entry, over what its provider
     * (or the index) reported: play history and, for non-ROM kinds,
     * favourites. Applied to every list this class hands out.
     */
    private suspend fun withLibraryFacts(entries: List<LibraryEntry>): List<LibraryEntry> {
        if (entries.isEmpty()) return entries
        val ids = entries.map { it.id }
        val history = playHistory.getAll(ids)
        val favorite = favorites.getAll(ids)
        val said = links.getAll(ids)
        return entries.map { entry -> entry.withFacts(history[entry.id], entry.id in favorite, said[entry.id]) }
    }

    private fun LibraryEntry.withFacts(played: PlayHistoryRecord?, isFavorite: Boolean, said: GameLinks?): LibraryEntry {
        val withHistory = played?.let { copy(lastPlayedEpochMs = it.lastPlayedEpochMs, playCount = it.playCount) } ?: this
        // A ROM's favourite came from its provider already; this store
        // only ever holds the other kinds, so a hit is authoritative.
        val withFavourite = if (isFavorite && !withHistory.favorite) withHistory.copy(favorite = true) else withHistory
        // The links are the library's alone, so they are set from the
        // store every time, absent included: an entry that went through a
        // record or the index carrying an old answer does not keep it.
        val name = said?.gameName
        val thread = said?.f95Thread
        val latest = said?.latestKnown
        return if (withFavourite.gameName == name && withFavourite.f95Thread == thread && withFavourite.latestKnown == latest) {
            withFavourite
        } else {
            withFavourite.copy(gameName = name, f95Thread = thread, latestKnown = latest)
        }
    }

    private companion object {
        /** The most often a walk in progress hands the shell the library again. */
        const val PUBLISH_INTERVAL_MS = 250L

        /** docs/SPEC.md 7g, step 4: how long the slow pass waits after the first ordinary scan starts before it begins. */
        const val SLOW_REBUILD_START_DELAY_MS = 5_000L

        /** docs/SPEC.md 7g, step 4: how long the slow pass waits between rounds once it has run -- "kept honest ... over time." */
        const val SLOW_REBUILD_INTERVAL_MS = 30 * 60_000L

        /** docs/SPEC.md 7g: how long nothing must have observed the library for its return to start a round at once. */
        const val SLOW_REBUILD_RETURN_MS = 5 * 60_000L

        /** docs/SPEC.md 7g, step 4: the pause between parts the slow pass takes and "Rescan library" deliberately does not. */
        const val SLOW_REBUILD_PAUSE_MS = 500L
    }
}
