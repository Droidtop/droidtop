package dev.droidtop.library

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val playtimeSeconds: Long = 0,
    val lastPlayedEpochMs: Long? = null,
    // Only set for LibraryEntryKind.CONSOLE_ROM -- the real console system
    // id (see dev.droidtop.library.consoles.ConsoleSystemDef), since
    // CONSOLE_ROM alone doesn't distinguish NES from GBA from PS1 the way
    // separate LibraryEntryKinds distinguish RENPY from RPG_MAKER_MV.
    // shell-gamepad's Games section still buckets every CONSOLE_ROM entry
    // under one "Consoles" system-list card for now, not per real console
    // -- real per-system browsing (using this field) is the next UI step,
    // not built in this pass.
    val systemId: String? = null,
)

enum class LibraryEntryKind {
    NATIVE_ANDROID_APP,
    WINE_PROFILE,
    LINUX_CONTAINER_APP,

    /**
     * An app configured on a paired remote GameStream/Sunshine host, launched
     * via runtime-remote-stream rather than run locally. Same LibraryEntry
     * shape as anything else — the whole point of this model is that "runs
     * on a remote PC over Moonlight" isn't a special case in the UI.
     */
    REMOTE_STREAM,

    /**
     * A detected engine game — kind named after the engine, not any one
     * launcher, since the same engine can be reachable through several
     * real paths (see [GameLaunchStrategy]/[GameLaunchStrategyResolver]):
     * JoiPlay today, a Wine prefix or a Linux container where the
     * engine/export actually supports it.
     */
    RENPY,
    RPG_MAKER_MV,
    RPG_MAKER_MZ,
    RPG_MAKER_VX_ACE,
    KIRIKIRI,

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
}

class Library(private val providers: List<LibraryProvider>) {
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
        coroutineScope {
            providers
                .filter { provider -> provider.kinds.any { it in kinds } }
                .map { provider -> async { scanProviderSafely(provider) } }
                .awaitAll()
                .flatten()
        }
    }

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

    suspend fun launch(entry: LibraryEntry) {
        withContext(Dispatchers.IO) {
            providers.first { entry.kind in it.kinds }.launch(entry)
        }
    }
}
