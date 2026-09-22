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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
     * Returns null unconditionally for an entry with no [mediaLocator],
     * for the same reason: an entry with no ES-DE media layout genuinely
     * has no marquee, and pretending otherwise by handing back
     * [artworkUri] would put the wrong picture in a marquee slot.
     */
    fun mediaForImageTypes(imageTypes: List<String>): String? {
        val locator = mediaLocator ?: return null
        return EsDeArtwork.resolveImageTypes(locator, imageTypes)
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
     * [rescanProgressive], but a provider that can tell one part's own
     * folder apart from another (see [EngineGameProvider]) skips a part
     * whose folder's modification time still matches [knownMtimes], and
     * pauses [pauseMs] between the parts it does not skip -- "walk only
     * changed parts." The default, for every provider with no cheaper way
     * to tell changed from unchanged (a part that isn't one directory,
     * see [GameIndexEntity]'s own doc comment), is a plain
     * [rescanProgressive]: always correct, never wrongly skips, so a
     * provider that does not override this just costs what a full
     * rescan already costs -- never less, never wrong.
     */
    fun slowRebuildProgressive(knownMtimes: Map<String, Long>, pauseMs: Long): Flow<ScanStep> = rescanProgressive()
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
    suspend fun moveEntryFacts(fromId: String, toId: String)
}

class Library(
    private val providers: List<LibraryProvider>,
    private val playHistory: PlayHistoryStore = NoOpPlayHistoryStore,
    private val favorites: FavoritesStore = NoOpFavoritesStore,
    private val index: LibraryIndexStore = NoOpLibraryIndexStore,
) {
    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backgroundScanStates = ConcurrentHashMap<Set<LibraryEntryKind>, MutableStateFlow<List<LibraryEntry>?>>()

    /** Ids whose play history or favourite changed; a publishing scan asks about them again (see LibraryFacts). */
    private val changedFactIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val backgroundScanJobs = mutableMapOf<Set<LibraryEntryKind>, Job>()

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

    /**
     * Process-owned scan results. The Library instance belongs to LibraryCore and
     * outlives every Activity/Compose screen, so collectors may come and go
     * without cancelling the filesystem walk that produces these values.
     */
    fun backgroundScanState(kinds: Set<LibraryEntryKind>): StateFlow<List<LibraryEntry>?> =
        backgroundScanStates.getOrPut(kinds.toSet()) { MutableStateFlow(null) }.asStateFlow()

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
    fun scanInBackground(kinds: Set<LibraryEntryKind>, rescan: Boolean = false, restart: Boolean = false) {
        // "A low-priority pass after start" (docs/SPEC.md 7g, step 4):
        // hooked onto the first ordinary (non-rescan) scan a shell ever
        // asks for, rather than a new call site in :app -- the shell
        // already calls this once its Games/Apps screen composes, which
        // IS "after start" for this process, and a real "Rescan library"
        // never re-arms it (see [startSlowRebuildOnce]'s own guard).
        if (!rescan) startSlowRebuildOnce(kinds)
        val key = kinds.toSet()
        val state = backgroundScanStates.getOrPut(key) { MutableStateFlow(null) }
        lateinit var job: Job
        job = scanScope.coroutineLaunch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val flow = if (rescan) rescanKindsProgressive(key) else scanKindsProgressive(key)
                flow.collect { state.value = it }
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
     * again every [SLOW_REBUILD_INTERVAL_MS] for as long as the process
     * lives, so a games root someone edits while droidtop keeps running
     * (adds a game, deletes one) is picked up without the user ever
     * pressing "Rescan library."
     *
     * Every indexed provider is re-walked each round, but
     * [LibraryProvider.slowRebuildProgressive]'s own mtime check (fed
     * from [LibraryIndexStore.folderMtimes]) is what makes a round cheap
     * in the common case: a part whose folder has not changed is not
     * re-detected at all, only the parts that changed since the index
     * last saw them are. Publishes into the SAME [backgroundScanState] a
     * normal scan of [kinds] would -- the slow pass keeps the index
     * honest, it does not add a second, separate view of it.
     */
    private fun startSlowRebuildOnce(kinds: Set<LibraryEntryKind>) {
        if (!slowRebuildStarted.compareAndSet(false, true)) return
        val key = kinds.toSet()
        scanScope.coroutineLaunch {
            delay(SLOW_REBUILD_START_DELAY_MS)
            while (true) {
                try {
                    libraryProgressive(key, rescan = false, slow = true).collect { entries ->
                        backgroundScanStates.getOrPut(key) { MutableStateFlow(null) }.value = entries
                    }
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    Log.e("droidtop.Library", "Slow rebuild pass failed", t)
                }
                delay(SLOW_REBUILD_INTERVAL_MS)
            }
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
        // The slow rebuild pass (docs/SPEC.md 7g, step 4): re-walks every
        // indexed provider even though it already has a slice (an
        // ordinary scan never does -- see the indexedSlot check below),
        // but through slowRebuildProgressive rather than scanProgressive,
        // so a provider that supports it skips its own unchanged parts.
        slow: Boolean = false,
    ): Flow<List<LibraryEntry>> = channelFlow {
        val matchingProviders = providers.filter { provider -> provider.kinds.any { it in kinds } }
        val slices = MutableList(matchingProviders.size) { LibrarySlice() }
        val indexedSlot = BooleanArray(matchingProviders.size)
        matchingProviders.forEachIndexed { i, provider ->
            if (!provider.indexed) return@forEachIndexed
            val known = index.load(provider.indexKey) ?: return@forEachIndexed
            slices[i] = known
            indexedSlot[i] = true
            ScanLog.write(
                "index: ${provider.indexKey} ${known.entries().size} entries in ${known.segments.size} parts" +
                    if (rescan) ", walking again" else "",
            )
        }
        val lock = Any()
        fun published(): List<LibraryEntry> = synchronized(lock) { slices.flatMap { it.entries() } }
        // Play history and favourites are asked about ONCE for what the
        // index holds, and after that only for the ids a finished part
        // brings. Every finished part used to ask about every id in the
        // library again: a walk of F folders over N games made F queries of
        // N ids each (docs/SPEC.md 7g, "what was wrong underneath").
        val facts = LibraryFacts()
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
        coroutineScope {
            matchingProviders.forEachIndexed { slot, provider ->
                if (indexedSlot[slot] && !rescan && !slow) return@forEachIndexed
                coroutineLaunch {
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
                        val stream = when {
                            rescan -> provider.rescanProgressive()
                            slow -> provider.slowRebuildProgressive(
                                this@Library.index.folderMtimes(provider.indexKey),
                                SLOW_REBUILD_PAUSE_MS,
                            )
                            else -> provider.scanProgressive()
                        }
                        stream.collect { step ->
                            val merged = synchronized(lock) {
                                val next = slices[slot].merge(step)
                                slices[slot] = next
                                next
                            }
                            if (provider.indexed) this@Library.index.save(provider.indexKey, merged)
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
        publish(force = true)
    }.flowOn(if (slow) slowDispatcher else Dispatchers.IO)

    /**
     * What the play-history and favourites stores say about the ids seen so
     * far in one progressive scan, so that publishing the library again is
     * a pass over a list in memory and not two database queries over every
     * id it holds.
     */
    private inner class LibraryFacts {
        private val history = java.util.concurrent.ConcurrentHashMap<String, PlayHistoryRecord>()
        private val favorite = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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
            for (id in ids) {
                played[id]?.let { history[id] = it } ?: history.remove(id)
                if (id in favourites) favorite.add(id) else favorite.remove(id)
            }
        }

        suspend fun learn(entries: List<LibraryEntry>) {
            val ids = entries.map { it.id }.filter { asked.add(it) }
            if (ids.isEmpty()) return
            history.putAll(playHistory.getAll(ids))
            favorite.addAll(favorites.getAll(ids))
        }

        fun apply(entries: List<LibraryEntry>): List<LibraryEntry> {
            if (history.isEmpty() && favorite.isEmpty()) return entries
            return entries.map { entry -> entry.withFacts(history[entry.id], entry.id in favorite) }
        }
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
        val dropped = mutableSetOf<String>()
        for (provider in providers.filter { it.indexed }) {
            val slice = index.load(provider.indexKey) ?: continue
            val kept = slice.keepOnlyRoots(rootPaths)
            if (kept == slice) continue
            dropped += slice.entries().map { it.id } - kept.entries().map { it.id }.toSet()
            index.save(provider.indexKey, kept)
            ScanLog.write("index: ${provider.indexKey} dropped the parts of roots that are no longer configured")
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
        changedFactIds += listOf(missing.id, replacement.id)
        providers.filterIsInstance<EntryFactsOwner>().forEach { it.moveEntryFacts(missing.id, replacement.id) }
        for (provider in providers.filter { it.indexed }) {
            val slice = index.load(provider.indexKey) ?: continue
            val without = slice.without(missing.id)
            if (without != slice) index.save(provider.indexKey, without)
        }
        backgroundScanStates.values.forEach { state ->
            val current = state.value ?: return@forEach
            state.value = withLibraryFacts(current.filterNot { it.id == missing.id })
        }
        ScanLog.write("index: ${missing.id} folded into ${replacement.id}")
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
        if (history.isEmpty() && favorite.isEmpty()) return entries
        return entries.map { entry -> entry.withFacts(history[entry.id], entry.id in favorite) }
    }

    private fun LibraryEntry.withFacts(played: PlayHistoryRecord?, isFavorite: Boolean): LibraryEntry {
        val withHistory = played?.let { copy(lastPlayedEpochMs = it.lastPlayedEpochMs, playCount = it.playCount) } ?: this
        // A ROM's favourite came from its provider already; this store
        // only ever holds the other kinds, so a hit is authoritative.
        return if (isFavorite) withHistory.copy(favorite = true) else withHistory
    }

    private companion object {
        /** The most often a walk in progress hands the shell the library again. */
        const val PUBLISH_INTERVAL_MS = 250L

        /** docs/SPEC.md 7g, step 4: how long the slow pass waits after the first ordinary scan starts before it begins. */
        const val SLOW_REBUILD_START_DELAY_MS = 5_000L

        /** docs/SPEC.md 7g, step 4: how long the slow pass waits between rounds once it has run -- "kept honest ... over time." */
        const val SLOW_REBUILD_INTERVAL_MS = 30 * 60_000L

        /** docs/SPEC.md 7g, step 4: the pause between parts the slow pass takes and "Rescan library" deliberately does not. */
        const val SLOW_REBUILD_PAUSE_MS = 500L
    }
}
