package dev.droidtop.library.consoles

import android.content.Context
import dev.droidtop.library.LaunchDisplay
import dev.droidtop.library.EsDeArtwork
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryProvider
import dev.droidtop.library.integrations.IntegrationPlaceholders
import dev.droidtop.library.romdetect.LibretroDatabase
import dev.droidtop.library.romdetect.PlayStationDiscType
import dev.droidtop.library.romdetect.SerialScanner
import dev.droidtop.library.romdetect.SystemID
import dev.droidtop.library.romdetect.toConsoleSystemId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch as coroutineLaunch
import java.io.File
import java.io.FileInputStream
import java.util.Collections

/**
 * Real, installed apps only -- a [KnownPlayers]/[DefaultPlayers] preset
 * existing doesn't mean the emulator is actually on this device (the whole
 * point of pulling in Daijishō's real, comprehensive preset list is
 * covering "whatever's actually installed," not assuming any one is).
 */
internal fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
    context.packageManager.getApplicationInfo(packageName, 0)
    true
} catch (e: android.content.pm.PackageManager.NameNotFoundException) {
    false
}

/**
 * Every real, currently-usable [Player.AmStart] for [system] -- droidtop's
 * own custom players (see [CustomPlayerPrefs]) first (a user who bothered
 * to add one clearly wants it offered), then [KnownPlayers]' real presets
 * pulled from Daijishō's own wiki, then [DefaultPlayers.retroArch] last (a
 * broad-coverage fallback via libretro cores, not most frontends' first
 * choice when a dedicated standalone port is also installed) -- filtered to
 * only players whose app is actually installed.
 */
fun availablePlayers(context: Context, system: ConsoleSystemDef): List<Player.AmStart> {
    val custom = CustomPlayerPrefs.getForSystem(context, system.id)
    val known = KnownPlayers.forSystem(context, system.id).map { it.player }
    val retroArch = DefaultPlayers.retroArch(context, system)
    return (custom + known + listOfNotNull(retroArch)).filter { isPackageInstalled(context, it.packageName) }
}

/** [PlayerOverridePrefs]'s choice if it's still a real, available candidate; otherwise the first available one. */
fun resolvePlayer(context: Context, system: ConsoleSystemDef): Player.AmStart? {
    val candidates = availablePlayers(context, system)
    val overrideId = PlayerOverridePrefs.get(context, system.id)
    return candidates.firstOrNull { it.id == overrideId } ?: candidates.firstOrNull()
}

/**
 * Real emulator names from the players database's own labels, in order,
 * deduplicated.
 *
 * The labels are not uniform, which is why this needs to exist: some are
 * real product names ("Drastic"), some carry a redundant system prefix
 * ("gba - Linkboy"), and some are just the package id
 * ("com.fastemulator.gba") where nobody ever filled a name in. Only a
 * real name helps someone decide what to install, so the prefix is
 * dropped and anything still shaped like a package id is discarded
 * rather than shown -- telling a user to go install
 * "com.fastemulator.gba" is barely better than telling them nothing.
 *
 * Separate from [noEmulatorInstalledMessage] purely so it can be tested:
 * library-core's test source set is plain JUnit, so a function taking a
 * Context could not be covered there.
 */
internal fun usableEmulatorNames(labels: List<String>): List<String> = labels
    .map { it.substringAfter(" - ", it).trim() }
    .filterNot { it.isEmpty() || '.' in it }
    .distinct()

/**
 * What the user is told when [resolvePlayer] comes back empty.
 *
 * Real finding from the all-systems launch sweep: five systems (N64,
 * NDS, GBA, GBC, Switch) failed to launch, and all five failed here --
 * correctly, since nothing for them was installed. But the old wording,
 * "No installed Player available for system n64", spent its one chance
 * on internal vocabulary: "Player" is droidtop's own type name, and
 * "n64" is a folder id, not what the system is called. Worse, it was a
 * dead end -- droidtop knows every emulator it supports for the system
 * and had just filtered that list down to the installed ones, so it
 * could name them and simply didn't.
 */
internal fun noEmulatorInstalledMessage(context: Context, system: ConsoleSystemDef): String {
    val suggestions = usableEmulatorNames(KnownPlayers.forSystem(context, system.id).map { it.label })
    val base = "No emulator for ${system.displayName} is installed"
    return if (suggestions.isEmpty()) {
        base
    } else {
        "$base. droidtop can use ${suggestions.take(3).joinToString(", ")}" +
            (if (suggestions.size > 3) ", among others." else ".")
    }
}

/**
 * Folder-name mismatches between a real ROMs collection and ES-DE's own
 * canonical system ids. Generated (not hand-guessed) by cross-referencing
 * every real platform `shortname` in Daijishō's own public platform
 * database (github.com/Jetup13/DaijishouExp, 131 real platform JSON
 * files, the same real community-maintained convention a lot of existing
 * ROM collections -- including the one confirmed against a real test
 * device this session -- are actually organized under) against
 * [ES_DE_CONSOLE_SYSTEMS]'s own real ids, keeping only the confident,
 * verified matches (same display name/system identity on both sides, not
 * a fuzzy guess -- e.g. Daijishō's "cassette"/"pico" shortnames were
 * deliberately NOT aliased here despite superficially similar names,
 * since they identify different real systems than any ES-DE entry with a
 * similar name).
 *
 * Real, remaining, honest gap: several Daijishō platforms (RPG Maker,
 * Quake II engine, NEC PC-60, Elektor TV Games Computer, Sega Genesis
 * MSU, ...) have no ES-DE equivalent at all -- an alias can't fix that,
 * since there's no [ConsoleSystemDef] to alias TO. Adding real new
 * ConsoleSystemDef entries for those (extensions/display name sourced
 * from the same real Daijishō data) is separate, worthwhile follow-up
 * work, not attempted here.
 */
private val SYSTEM_ID_ALIASES: Map<String, String> = mapOf(
    "ps1" to "psx",
    "nsw" to "switch",
    // Real, confirmed Daijishō shortname -> ES-DE id matches.
    "3ds" to "n3ds",
    "appleii" to "apple2",
    "cdi" to "cdimono1",
    "coleco" to "colecovision",
    "cpc" to "amstradcpc",
    "gw" to "gameandwatch",
    "jaguar" to "atarijaguar",
    "jaguarcd" to "atarijaguarcd",
    "lynx" to "atarilynx",
    "master" to "mastersystem",
    "palmos" to "palm",
    "psv" to "psvita",
    "sg1000" to "sg-1000",
    "supercassette" to "scv",
    "tgcd" to "tg-cd",
    "vita" to "psvita",
    "ws" to "wonderswan",
    "wsc" to "wonderswancolor",
)

/**
 * Resolves a ROMs subfolder name to a known [ConsoleSystemDef], checking
 * [SYSTEM_ID_ALIASES] first. [systemsById] is a live snapshot from
 * [ConsoleSystemsRepository.allSystems] (built-in + real, user-edited/
 * added platforms), not a compile-time constant -- the caller loads it
 * once per scan/lookup pass and passes it in, since a plain top-level
 * `val` computed once at class-load can't reflect platform edits made
 * after that (see [ConsoleSystemsRepository]'s own doc comment).
 */
internal fun resolveSystem(folderName: String, systemsById: Map<String, ConsoleSystemDef>): ConsoleSystemDef? {
    val id = folderName.lowercase()
    return systemsById[SYSTEM_ID_ALIASES[id] ?: id]
}

/**
 * [LibraryProvider] for real console ROMs, scanning `<root>/<systemId>/
 * <romFile>` -- the same layout ES-DE itself uses (confirmed against a
 * real device's existing ROMs folder this session), so an existing
 * collection works with no reorganizing.
 *
 * Launch strategy per system: [availablePlayers] resolves every real,
 * currently-installed [Player.AmStart] for that system (custom players from
 * [CustomPlayerPrefs], real presets from [KnownPlayers] -- generated from
 * Daijishō's own public wiki, covering standalone emulators for systems
 * with no RetroArch core at all, like PS2/3DS/Switch/GameCube/PSP -- and
 * [DefaultPlayers.retroArch] as a broad libretro-core fallback), and
 * [resolvePlayer] picks [PlayerOverridePrefs]'s explicit choice if set,
 * else the first available one. A system with zero available players
 * (nothing installed that can run it) is skipped during scan, not shown as
 * broken entries.
 */
class ConsoleRomProvider(
    private val context: Context,
) : LibraryProvider {
    override val kinds: Set<LibraryEntryKind> = setOf(LibraryEntryKind.CONSOLE_ROM)

    private val dao by lazy { RomDatabase.get(context).romDao() }
    private val libretroDao by lazy { LibretroDatabase.get(context).gameDao() }

    // Real bug this fixes, reported directly: a huge single system folder
    // (a real device's "j2me" folder had 18,126 files -- and, separately,
    // turned out to contain a corrupted directory entry that hung even a
    // plain `ls` indefinitely) made the *entire* scan hang, since
    // persistence (see RomDatabase's own doc comment) used to wait for
    // every system folder in a root before writing any of them. Each
    // system folder now scans, caches, AND persists independently, so
    // j2me hanging no longer holds up nes/gba/psx/etc, which return and
    // get saved as soon as their own (much smaller) folders are read.
    //
    // Real persistent cache (RomDatabase): a system folder that already
    // has a real scan_metadata row (a genuine prior walk of THAT folder,
    // not just its root) returns its cached rom_entries rows directly
    // instead of re-walking the filesystem. A folder scanned for the
    // first time ever still gets the real, full walk below, so the first
    // launch after adding a new ROMs root behaves exactly as before --
    // only *repeat* scans of an already-known folder get faster.
    override suspend fun scan(): List<LibraryEntry> {
        val romsRoots = GamesRoots.current(context)
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        val scannedFolders = dao.getScannedSystemFolders(romsRoots.map { it.absolutePath })
            .map { it.romsRoot to it.systemFolderId }.toSet()
        val cached = dao.getEntries(romsRoots.map { it.absolutePath }).map { it.toLibraryEntry() }.withMetadata()
        val fresh = scanRootsFresh(romsRoots, systemsById, scannedFolders)
        return cached + fresh
    }

    // Real, reported UX request this answers: the Games screen used to
    // show nothing but a spinner until every root's every system folder
    // finished, even though most individual folders (nes/gba/psx, a few
    // hundred files) are fast -- a real device's one pathologically large
    // (and, separately, hung) folder (a real "j2me" directory) held the
    // whole screen hostage behind it. Cached rows emit immediately; each
    // fresh system folder then emits its own entries AND writes its own
    // cache row the moment it finishes, independently of every other
    // folder still running -- a fast folder never waits on a slow or
    // stuck one, for the UI *or* for persistence.
    override fun scanProgressive(): Flow<List<LibraryEntry>> = channelFlow {
        val romsRoots = GamesRoots.current(context)
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        val scannedFolders = dao.getScannedSystemFolders(romsRoots.map { it.absolutePath })
            .map { it.romsRoot to it.systemFolderId }.toSet()
        val cached = dao.getEntries(romsRoots.map { it.absolutePath }).map { it.toLibraryEntry() }.withMetadata()
        streamRootsProgressively(cached, romsRoots, systemsById, scannedFolders)
    }

    /**
     * Real, explicit "my ROMs changed, look again" action -- forces a full
     * filesystem walk of every configured root regardless of cache state,
     * replacing whatever was previously cached for each one. Wired to a
     * real, user-facing "Rescan library" action in shell-gamepad's
     * SettingsSection -- previously the only way to force a fresh scan
     * was clearing app data by hand over adb, not something a real user
     * could ever do.
     *
     * Real bug this fixes, reported directly: this used to clear every
     * root's cache *before* scanning and seed the live stream with an
     * empty list -- a real user's entire, already-known library visibly
     * disappeared the instant they pressed "Rescan," staying blank for
     * however long the fresh walk took (minutes, on a real device's large
     * ROM collection), then slowly reappearing. A "look again" action
     * should never make a user's existing library vanish. Each system
     * folder's old cached rows now stay fully visible until THAT folder's
     * own fresh walk actually finishes, at which point just its own slice
     * is swapped for the fresh set and persisted -- never a gap where
     * nothing is shown for something that was already known, and (unlike
     * the earlier per-root version of this same guarantee) one stuck
     * folder can no longer block every sibling folder's fresh results
     * from being saved too.
     */
    override fun rescanProgressive(): Flow<List<LibraryEntry>> = channelFlow {
        val romsRoots = GamesRoots.current(context)
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        val cachedByRoot = romsRoots.associate { root ->
            root.absolutePath to dao.getEntries(listOf(root.absolutePath)).map { it.toLibraryEntry() }.withMetadata()
        }
        streamRootsRescan(cachedByRoot, systemsById)
    }

    private suspend fun ProducerScope<List<LibraryEntry>>.streamRootsRescan(
        cachedByRoot: Map<String, List<LibraryEntry>>,
        systemsById: Map<String, ConsoleSystemDef>,
    ) {
        val accumulatedByRoot = Collections.synchronizedMap(cachedByRoot.mapValues { it.value.toMutableList() }.toMutableMap())
        // Compound read-modify-write on one root's list (below) needs its
        // own lock -- synchronizedMap only guards the map's own structure,
        // not a get-then-replace sequence against concurrent sibling
        // folders of the *same* root finishing at the same time.
        val accumulatedLock = Any()
        send(accumulatedByRoot.values.flatten())
        coroutineScope {
            cachedByRoot.keys.forEach { rootPath ->
                val root = File(rootPath)
                val systemFolders = (root.listFiles() ?: emptyArray()).filter { it.isDirectory }
                    .mapNotNull { systemFolder ->
                        SystemOverridePrefs.resolveForFolder(context, systemFolder.absolutePath, systemFolder.name, systemsById)
                            ?.let { systemFolder to it }
                    }
                systemFolders.forEach { (systemFolder, system) ->
                    coroutineLaunch {
                        try {
                            val freshEntries = scanSystemFolder(systemFolder, system)
                            synchronized(accumulatedLock) {
                                val folderList = accumulatedByRoot.getOrPut(rootPath) { mutableListOf() }
                                folderList.removeAll { it.systemId == system.id }
                                folderList += freshEntries
                            }
                            send(accumulatedByRoot.values.flatten())
                            dao.clearSystemFolder(rootPath, system.id)
                            dao.insertEntries(freshEntries.map { it.toRomEntity(rootPath, system.id) })
                            dao.markScanned(ScanMetadataEntity(rootPath, system.id, System.currentTimeMillis()))
                        } catch (t: Throwable) {
                            android.util.Log.e("droidtop.ConsoleRomProvider", "rescan folder=${systemFolder.absolutePath} FAILED", t)
                        }
                    }
                }
            }
        }
    }

    private suspend fun ProducerScope<List<LibraryEntry>>.streamRootsProgressively(
        cached: List<LibraryEntry>,
        romsRoots: List<File>,
        systemsById: Map<String, ConsoleSystemDef>,
        scannedFolders: Set<Pair<String, String>>,
    ) {
        val accumulated = Collections.synchronizedList(cached.toMutableList())
        send(accumulated.toList())
        coroutineScope {
            romsRoots.forEach { root ->
                val systemFolders = (root.listFiles() ?: emptyArray()).filter { it.isDirectory }
                    .mapNotNull { systemFolder ->
                        SystemOverridePrefs.resolveForFolder(context, systemFolder.absolutePath, systemFolder.name, systemsById)
                            ?.let { systemFolder to it }
                    }
                    // Already has a real scan_metadata row for THIS folder
                    // -- its rows are already in `cached`, skip re-walking.
                    .filter { (_, system) -> (root.absolutePath to system.id) !in scannedFolders }
                systemFolders.forEach { (systemFolder, system) ->
                    coroutineLaunch {
                        try {
                            val folderEntries = scanSystemFolder(systemFolder, system)
                            accumulated += folderEntries
                            send(accumulated.toList())
                            dao.clearSystemFolder(root.absolutePath, system.id)
                            dao.insertEntries(folderEntries.map { it.toRomEntity(root.absolutePath, system.id) })
                            dao.markScanned(ScanMetadataEntity(root.absolutePath, system.id, System.currentTimeMillis()))
                        } catch (t: Throwable) {
                            android.util.Log.e("droidtop.ConsoleRomProvider", "scan folder=${systemFolder.absolutePath} FAILED", t)
                        }
                    }
                }
            }
        }
    }

    /**
     * Real, explicit "my ROMs changed, look again" action, non-streaming
     * variant -- kept alongside [rescanProgressive] for any real future
     * caller that just wants the final list, not a growing stream.
     */
    suspend fun rescan(): List<LibraryEntry> {
        val romsRoots = GamesRoots.current(context)
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        romsRoots.forEach { root ->
            dao.clearRoot(root.absolutePath)
            dao.clearScanMetadata(root.absolutePath)
        }
        return scanRootsFresh(romsRoots, systemsById)
    }

    private suspend fun scanRootsFresh(
        roots: List<File>,
        systemsById: Map<String, ConsoleSystemDef>,
        scannedFolders: Set<Pair<String, String>> = emptySet(),
    ): List<LibraryEntry> = coroutineScope {
        roots.flatMap { root ->
            (root.listFiles() ?: emptyArray()).filter { it.isDirectory }
                .mapNotNull { systemFolder ->
                    SystemOverridePrefs.resolveForFolder(context, systemFolder.absolutePath, systemFolder.name, systemsById)
                        ?.let { systemFolder to it }
                }
                .filter { (_, system) -> (root.absolutePath to system.id) !in scannedFolders }
                .map { (systemFolder, system) -> Triple(root, systemFolder, system) }
        }.map { (root, systemFolder, system) ->
            async {
                val entries = scanSystemFolder(systemFolder, system)
                dao.clearSystemFolder(root.absolutePath, system.id)
                dao.insertEntries(entries.map { it.toRomEntity(root.absolutePath, system.id) })
                dao.markScanned(ScanMetadataEntity(root.absolutePath, system.id, System.currentTimeMillis()))
                entries
            }
        }.awaitAll().flatten()
    }

    // Recursive, not just the system folder's immediate children -- lets a
    // large system be reorganized into subfolders (by first letter, by
    // collection, whatever) for real, purely as a filesystem organization
    // choice, without that reorganizing ever hiding files from droidtop:
    // every file under the system folder at any depth is still found and
    // still counted as belonging to that one system.
    private suspend fun scanSystemFolder(systemFolder: File, system: ConsoleSystemDef): List<LibraryEntry> = coroutineScope {
        // Real, deliberate design: detection (does this system show up at
        // all) is driven ENTIRELY by the ROMs folder itself -- a real
        // subfolder with real files is what "this system exists in my
        // library" means, full stop. Player availability is a launch-time
        // concern (see launch(), which errors clearly if nothing can run
        // the entry), not a visibility gate. An earlier version of this
        // function skipped scanning entirely when availablePlayers(...)
        // was empty, which silently hid a real, populated system folder
        // any time droidtop's own player detection didn't recognize an
        // installed emulator (a real, confirmed case: RetroArch's
        // aarch64-build package name wasn't checked at all until this same
        // pass -- see DefaultPlayers' own doc comment) -- the ROM folder
        // itself is the source of truth, not what droidtop happens to know
        // how to launch today.
        // The games root is systemFolder's own parent (<gamesRoot>/<systemId>/...),
        // same directory ES-DE's own `downloaded_media` sits alongside --
        // real per-game artwork when a user's existing ES-DE (or any other
        // scraper writing that same real layout) has already scraped it.
        // See EsDeArtwork's own doc comment for why droidtop reads this
        // rather than scraping itself.
        val gamesRoot = systemFolder.parentFile ?: systemFolder
        val allFiles = systemFolder.walkTopDown().filter { it.isFile }.toList()
        val romFiles = allFiles.filter { it.extension.lowercase() in system.extensions }
        // Real, genuine file detection beyond what either ES-DE or EmuDeck
        // actually do (both confirmed this session to be purely
        // folder+extension-based, no content/filename lookup at all --
        // SystemData::populateFolder's own real source, and EmuDeck's own
        // real roms/<system>/ layout, which uses the same ES-DE-derived
        // ids). A real Android ROM manager (Lemuroid, already vendored in
        // this repo) does this properly: a prioritized cascade -- embedded
        // disc serial/magic number first (SerialScanner, cheap,
        // header-only read, for the disc-image extensions it covers), then
        // a filename lookup against Lemuroid's own real, ~13MB community
        // ROM database (libretro-db.sqlite, already vendored, now bundled
        // as droidtop's own asset -- a single fast indexed query, no file
        // content read at all), before falling back to trusting the
        // folder. Full CRC32 hashing (Lemuroid's own strongest,
        // first-priority signal) is real, deferred follow-up work --
        // reading a multi-gigabyte disc image's entire content for a hash
        // needs real performance tuning this pass didn't have room for;
        // header-read serial detection and free filename lookup are the
        // safe, cheap wins taken here.
        //
        // Real bug this fixes, found via actual on-device testing: this
        // per-file cascade used to run as a sequential for-loop, meaning
        // one suspend DB round-trip awaited before the next file's even
        // started -- fine for a folder of a few hundred ROMs, but a real
        // device's "j2me" folder (18,128 files) took over ten minutes wall
        // clock and never finished before Library.scanKinds' own 15s
        // per-provider timeout gave up waiting, permanently blank-screening
        // Games. Concurrent per-file async (matching the same pattern
        // scanRootsFresh already uses per-system-folder, one level up)
        // lets Room's own executor and the filesystem overlap thousands of
        // independent lookups instead of paying their latency one at a
        // time.
        romFiles.map { romFile ->
            async {
                val effectiveSystemId = detectSystemIdFromContent(romFile)
                    ?: detectSystemIdFromFilename(romFile)
                    ?: system.id
                LibraryEntry(
                    id = romFile.absolutePath,
                    title = romFile.nameWithoutExtension,
                    kind = LibraryEntryKind.CONSOLE_ROM,
                    systemId = effectiveSystemId,
                    artworkUri = EsDeArtwork.resolve(gamesRoot, effectiveSystemId, romFile.nameWithoutExtension),
                    manualUri = EsDeArtwork.resolveManual(gamesRoot, effectiveSystemId, romFile.nameWithoutExtension),
                    videoUri = EsDeArtwork.resolveVideo(gamesRoot, effectiveSystemId, romFile.nameWithoutExtension),
                )
            }
        }.awaitAll().withMetadata()
    }

    /**
     * Merges in real, previously-scraped [GameMetadataEntity] rows (see
     * that class's own doc comment for why this lives in a separate
     * table from the filesystem-scan cache) for every entry -- applied
     * uniformly at every real point entries reach a caller (cached reads
     * in [scan]/[scanProgressive]/[rescanProgressive], and fresh scans
     * via [scanSystemFolder]) so a rescan of a folder never silently
     * drops metadata a user already waited on a real network scrape for.
     */
    private suspend fun List<LibraryEntry>.withMetadata(): List<LibraryEntry> {
        if (isEmpty()) return this
        val metadataById = dao.getGameMetadata(map { it.id }).associateBy { it.id }
        // Real reverse query for the badge "collection" slot -- separate
        // from metadataById since collection membership lives in its own
        // table, unrelated to whether a game has a game_metadata row at
        // all (see LibraryEntry.inCollection's own doc comment).
        val inAnyCollection = dao.getGameIdsInAnyCollection().toHashSet()
        return map { entry ->
            val meta = metadataById[entry.id]
            val entryWithCollection = if (entry.id in inAnyCollection) entry.copy(inCollection = true) else entry
            if (meta == null) return@map entryWithCollection
            entryWithCollection.copy(
                description = meta.description,
                developer = meta.developer,
                publisher = meta.publisher,
                genre = meta.genre,
                releaseDate = meta.releaseDate,
                rating = meta.rating,
                players = meta.players,
                favorite = meta.favorite,
                completed = meta.completed,
                kidGame = meta.kidGame,
                hidden = meta.hidden,
                broken = meta.broken,
                noGameCount = meta.noGameCount,
                noMultiScrape = meta.noMultiScrape,
                hideMetadata = meta.hideMetadata,
                controllerShortName = meta.controllerShortName,
                altEmulator = meta.altEmulator,
                launchScreen = meta.launchScreen,
                sortName = meta.sortName,
                collectionSortName = meta.collectionSortName,
            )
        }
    }

    /**
     * Real content-based system detection for the disc-image extensions
     * [SerialScanner] actually supports (iso/bin/pbp/3ds) -- returns null
     * (meaning "keep looking") for every other extension, and also null
     * if content detection genuinely found nothing (a real disc image
     * SerialScanner's magic numbers don't happen to cover, e.g. a
     * GameCube/Wii/generic PC .iso) or the detected [SystemID] has no
     * known real [ConsoleSystemDef] id to map to. Best-effort: any read
     * failure (a real but rare case -- a corrupt file, a permissions
     * issue) is caught and treated the same as "found nothing," never
     * fails the whole scan over one file.
     */
    private fun detectSystemIdFromContent(romFile: File): String? {
        val extension = romFile.extension.lowercase()
        if (extension !in setOf("iso", "bin", "pbp", "3ds")) return null
        // A PlayStation disc image is read as a filesystem first, not
        // scanned. PS1 and PS2 discs share the "PLAYSTATION" volume
        // identifier, so the magic numbers below cannot separate them and
        // used to call every PS2 disc a PS1 one -- which launched those
        // games through a PS1 emulator.
        //
        // This is the only PS2 signal droidtop has: the bundled libretro
        // database carries no ps2 rows (24 systems, all Lemuroid's), so
        // the filename lookup below can never identify one. Hence trying
        // .bin too, not just .iso -- it costs nothing when it does not
        // apply, since the reader self-checks for "CD001" and returns
        // null on a raw-sector image, falling through to the scanner
        // exactly as before.
        if (extension == "iso" || extension == "bin") {
            PlayStationDiscType.detect(romFile)?.toConsoleSystemId()?.let { return it }
        }
        val scanned = try {
            FileInputStream(romFile).use { stream ->
                SerialScanner.extractInfo(romFile.name, stream).systemID
            }
        } catch (t: Throwable) {
            null
        }
        // A disc image the scanner calls PSX is not evidence of PS1. The
        // scanner reaches that answer from the "PLAYSTATION" volume
        // identifier, which PS2 discs carry too, and it defaults to PSX
        // even when it learned nothing else -- so for a disc image that
        // verdict means "a PlayStation disc of some generation", not
        // "PS1". Reaching here means the SYSTEM.CNF read above failed to
        // say which, so droidtop reports unknown and lets the folder name
        // decide, rather than overriding a correct /Roms/ps2/ with a
        // guess. Confirmed necessary on-device: two PS2 discs that had
        // been identified correctly flipped back to psx after a
        // transient read failure, purely because of this default.
        //
        // The serial does not help: PS2 serials use the same prefixes
        // (SLUS, SCUS, ...) the PS1 list already matches.
        if (scanned == SystemID.PSX && (extension == "iso" || extension == "bin")) {
            android.util.Log.w(
                "droidtop.RomScan",
                "Could not read SYSTEM.CNF from ${romFile.name}; leaving the system to the folder name",
            )
            return null
        }
        return scanned?.toConsoleSystemId()
    }

    /**
     * Real filename lookup against Lemuroid's own real, bundled community
     * ROM database -- a genuinely useful signal for the systems
     * [SerialScanner] doesn't cover at all (cartridge-based ROMs --
     * NES/SNES/GBA/N64/...), and free: a single indexed SQLite query,
     * no file content read. Returns null (meaning "trust the folder") on
     * no match, a stored system this pass's own [toConsoleSystemId]
     * mapping doesn't cover, or any real DB error (best-effort, same
     * pattern as [detectSystemIdFromContent] -- one bad lookup never
     * fails the whole scan).
     */
    private suspend fun detectSystemIdFromFilename(romFile: File): String? {
        return try {
            val rom = libretroDao.findByFileName(romFile.name) ?: return null
            SystemID.entries.firstOrNull { it.dbname == rom.system }?.toConsoleSystemId()
        } catch (t: Throwable) {
            null
        }
    }

    override suspend fun launch(entry: LibraryEntry) {
        val romFile = File(entry.id)
        // Real fix: use the entry's own already-resolved systemId first --
        // scan() may have corrected it via real content detection
        // (detectSystemIdFromContent), which a folder-name-only re-lookup
        // here would silently throw away, launching a misfiled disc image
        // with the wrong system's player.
        val parentFolder = romFile.parentFile
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        val system = entry.systemId?.let { systemsById[it] }
            ?: SystemOverridePrefs.resolveForFolder(context, parentFolder?.absolutePath ?: "", parentFolder?.name ?: "", systemsById)
            ?: error("Couldn't resolve a console system for ${entry.id}")
        val player = resolvePlayer(context, system)
            ?: error(noEmulatorInstalledMessage(context, system))
        if (player.killPackageProcesses) killPackageProcessesBestEffort(player.packageName)
        // Beyond {file.path}/{file.uri}: the MAME4droid presets generated
        // from ES-DE's own es_systems.xml build a -rompath out of the
        // game's directory and the system folder, and software-list
        // launches pass the extensionless basename. Same placeholder
        // vocabulary the integrations already use for the system ones.
        //
        // {system.folder} is the ROM's parent directory: for the flat
        // <root>/<system>/<rom> layout droidtop scans, that IS the system
        // folder, and for a ROM in a subfolder it degrades to the game's
        // own directory -- which for a MAME -rompath is still a correct
        // search entry, just a narrower one.
        val parentPath = parentFolder?.absolutePath
        val placeholders = buildMap {
            put("{file.dir}", parentPath ?: "")
            put("{file.basename}", romFile.nameWithoutExtension)
            put(IntegrationPlaceholders.SYSTEM_ID, system.id)
            put(IntegrationPlaceholders.SYSTEM_NAME, system.displayName)
            parentPath?.let { put(IntegrationPlaceholders.SYSTEM_FOLDER, it) }
        }
        val intent = AmStartCommandToIntentConverter.toIntent(
            context,
            player.argumentsTemplate,
            romFile.absolutePath,
            placeholders,
        )
        LaunchDisplay.start(context, intent)
    }

    /**
     * Real, user-driven favorite toggle -- see [RomDao.setFavorite]'s own
     * doc comment for why this is a real upsert rather than
     * `upsertGameMetadata`, which would silently wipe any other real
     * scraped metadata this game already has. Returns the real, new
     * favorite state so callers can update their own held copy of the
     * entry without a full rescan.
     */
    suspend fun toggleFavorite(entryId: String): Boolean {
        val current = dao.getGameMetadata(listOf(entryId)).firstOrNull()?.favorite ?: false
        val next = !current
        dao.setFavorite(entryId, next)
        return next
    }

    /**
     * Real load-for-editing step -- [dev.droidtop.shell.gamepad]
     * `GameMetadataEditor`'s own "show current values" state. Returns a
     * real, default-valued [GameMetadataEntity] (matching real ES-DE's
     * own `MetaDataList`'s constructor behavior -- every field starts at
     * its documented default, not null/missing, when a game has no row
     * yet) rather than null, so the editor never has to special-case
     * "never edited before."
     */
    suspend fun getMetadataForEditing(entryId: String): GameMetadataEntity =
        dao.getGameMetadataSingle(entryId) ?: GameMetadataEntity(id = entryId)

    /**
     * Real save path for [dev.droidtop.shell.gamepad] `GameMetadataEditor`
     * -- a plain full upsert (unlike [toggleFavorite]'s single-column
     * `UPDATE`) since the editor legitimately holds and can change every
     * real field at once, same as real ES-DE's own `GuiMetaDataEd` saving
     * its whole in-memory `MetaDataList` back on exit.
     */
    suspend fun saveMetadata(metadata: GameMetadataEntity) {
        dao.upsertGameMetadata(metadata)
    }

    /** Real custom collections list, alphabetical -- droidtop's own equivalent of real ES-DE's `getCustomCollectionSystems`. */
    suspend fun getCollections(): List<CollectionEntity> = dao.getCollections()

    /**
     * Real collection creation -- droidtop's own equivalent of real
     * ES-DE's `addNewCustomCollection`. [id] is a fresh UUID, not the
     * user-facing [name] -- see [CollectionEntity]'s own doc comment for
     * why.
     */
    suspend fun createCollection(name: String): CollectionEntity {
        val collection = CollectionEntity(id = java.util.UUID.randomUUID().toString(), name = name)
        dao.upsertCollection(collection)
        return collection
    }

    suspend fun renameCollection(id: String, newName: String) {
        dao.upsertCollection(CollectionEntity(id = id, name = newName))
    }

    /** Real deletion -- droidtop's own equivalent of real ES-DE's `deleteCustomCollection`, including its own membership rows. */
    suspend fun deleteCollection(id: String) {
        dao.deleteCollectionMembers(id)
        dao.deleteCollection(id)
    }

    /** Real add/remove membership toggle -- droidtop's own equivalent of real ES-DE's `toggleGameInCollection`. Returns the real new membership state. */
    suspend fun toggleCollectionMembership(collectionId: String, gameId: String): Boolean =
        dao.toggleCollectionMember(collectionId, gameId)

    suspend fun isCollectionMember(collectionId: String, gameId: String): Boolean =
        dao.isCollectionMember(collectionId, gameId)

    /** Real collectionId -> member gameIds map, for the games-list read path -- see [dev.droidtop.shell.gamepad]'s own `GameGroup.Collection` doc comment. */
    suspend fun getCollectionMembership(): Map<String, List<String>> =
        dao.getAllCollectionMembers().groupBy({ it.collectionId }, { it.gameId })

    // `ActivityManager.forceStopPackage()` needs the signature-level
    // FORCE_STOP_PACKAGES permission -- not grantable to a normal app, so
    // this real Daijishō-preset flag (several of KnownPlayers' real
    // entries, e.g. DuckStation, set it -- a workaround for emulators that
    // don't reset their own state cleanly on a repeat launch) can only
    // actually do anything on a rooted device. Best-effort and silent on
    // failure (debug-level log only) rather than erroring the whole
    // launch over a real, expected no-root case.
    private fun killPackageProcessesBestEffort(packageName: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $packageName")).waitFor()
        } catch (t: Throwable) {
            android.util.Log.d("droidtop.ConsoleRomProvider", "Couldn't force-stop $packageName (likely no root)", t)
        }
    }
}

private fun LibraryEntry.toRomEntity(romsRoot: String, systemFolderId: String): RomEntity = RomEntity(
    id = id,
    title = title,
    systemId = systemId ?: "",
    artworkUri = artworkUri,
    manualUri = manualUri,
    videoUri = videoUri,
    romsRoot = romsRoot,
    systemFolderId = systemFolderId,
)

private fun RomEntity.toLibraryEntry(): LibraryEntry = LibraryEntry(
    id = id,
    title = title,
    kind = LibraryEntryKind.CONSOLE_ROM,
    systemId = systemId,
    artworkUri = artworkUri,
    manualUri = manualUri,
    videoUri = videoUri,
)
