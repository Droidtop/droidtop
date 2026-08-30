package dev.droidtop.library

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch as coroutineLaunch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    // Populated by a real scraper (LutrisScraperClient/IgdbScraperClient
    // in library-core/scraper/, wired into ConsoleSystemsActivity's
    // existing manual "scrape artwork" action) -- null/default until a
    // user actually scrapes a folder, same as artworkUri already was.
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
    // Real ES-DE MD_STRING field, stored but not yet consumed -- see
    // GameMetadataEntity's own doc comment for why (no custom-collections
    // concept in droidtop's data model yet, same tracked gap).
    val collectionSortName: String? = null,
)

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
    TWINE,
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
     * Real, optional progressive variant of [scan] -- emits growing
     * snapshots as results actually become available, instead of forcing
     * the caller to wait for the single slowest part of a scan before
     * showing anything at all. Default implementation just wraps [scan]
     * as one single final emission, so every provider keeps working with
     * zero changes required; only [dev.droidtop.library.consoles.ConsoleRomProvider]
     * overrides this for real, since it's the one provider whose scan can
     * take meaningfully long (a real SD card, a real folder with
     * thousands of files) -- see its own doc comment.
     */
    fun scanProgressive(): Flow<List<LibraryEntry>> = flow { emit(scan()) }

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
    fun rescanProgressive(): Flow<List<LibraryEntry>> = scanProgressive()
}

class Library(
    private val providers: List<LibraryProvider>,
    private val playHistory: PlayHistoryStore = NoOpPlayHistoryStore,
) {
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
        withPlayHistory(scanned)
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
        mergedProgressive(kinds) { it.scanProgressive() }

    /**
     * Real, streaming counterpart to a plain rescan -- same growing-
     * snapshot behavior as [scanKindsProgressive], but calling each
     * matching provider's [LibraryProvider.rescanProgressive] instead,
     * so a provider with its own persistent cache (see that method's own
     * doc comment) actually re-walks instead of trusting stale cached
     * rows. The real action behind shell-gamepad's Settings "Rescan
     * library" -- see that link's own doc comment for why a real,
     * user-facing trigger for this matters (previously the only way to
     * force a fresh scan was clearing app data via adb by hand).
     */
    fun rescanKindsProgressive(kinds: Set<LibraryEntryKind>): Flow<List<LibraryEntry>> =
        mergedProgressive(kinds) { it.rescanProgressive() }

    private fun mergedProgressive(
        kinds: Set<LibraryEntryKind>,
        streamFor: (LibraryProvider) -> Flow<List<LibraryEntry>>,
    ): Flow<List<LibraryEntry>> = channelFlow {
        val matchingProviders = providers.filter { provider -> provider.kinds.any { it in kinds } }
        val perProviderResults = MutableList(matchingProviders.size) { emptyList<LibraryEntry>() }
        coroutineScope {
            matchingProviders.forEachIndexed { index, provider ->
                coroutineLaunch {
                    try {
                        val completed = withTimeoutOrNull(60_000) {
                            streamFor(provider).collect { partial ->
                                perProviderResults[index] = partial
                                send(withPlayHistory(perProviderResults.flatten()))
                            }
                            true
                        }
                        if (completed == null) {
                            Log.e("droidtop.Library", "Provider ${provider::class.simpleName} timed out scanning")
                        }
                    } catch (t: Throwable) {
                        Log.e("droidtop.Library", "Provider ${provider::class.simpleName} failed to scan", t)
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun scanProviderSafely(provider: LibraryProvider): List<LibraryEntry> = try {
        // A caught exception alone doesn't cover a provider that genuinely
        // never returns (a real possibility, not just a defensive guess --
        // e.g. a storage provider that blocks indefinitely on denied
        // access rather than throwing or returning null). Note this only
        // actually helps if the provider's own work has real suspension
        // points -- a coroutine timeout can't preempt a single long
        // blocking File I/O call already in progress, only stop waiting
        // for it further once it does return control. Making the scan
        // itself fast (see ConsoleRomProvider's own per-system
        // parallelism) matters more than this timeout for that reason.
        withTimeoutOrNull(15_000) { provider.scan() } ?: run {
            Log.e("droidtop.Library", "Provider ${provider::class.simpleName} timed out scanning")
            emptyList()
        }
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
            providers.first { entry.kind in it.kinds }.launch(entry)
            playHistory.recordPlay(entry.id, System.currentTimeMillis())
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
            .firstOrNull { entry.kind in it.kinds } ?: return@withContext null
        romProvider.toggleFavorite(entry.id)
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

    private suspend fun withPlayHistory(entries: List<LibraryEntry>): List<LibraryEntry> {
        if (entries.isEmpty()) return entries
        val history = playHistory.getAll(entries.map { it.id })
        if (history.isEmpty()) return entries
        return entries.map { entry ->
            history[entry.id]?.let { entry.copy(lastPlayedEpochMs = it.lastPlayedEpochMs, playCount = it.playCount) } ?: entry
        }
    }
}
