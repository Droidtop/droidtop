package dev.droidtop.library.consoles

import android.content.Context
import dev.droidtop.library.PathIndexing
import dev.droidtop.library.disambiguateTitles
import dev.droidtop.library.LaunchDisplay
import dev.droidtop.library.EsDeArtwork
import dev.droidtop.library.GameMediaLocator
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.ScanBudget
import dev.droidtop.library.ScanLog
import dev.droidtop.library.ScanSkips
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryProvider
import dev.droidtop.library.QuitResult
import dev.droidtop.library.toQuitResult
import dev.droidtop.library.PartRef
import dev.droidtop.library.ScanStep
import dev.droidtop.library.withScrapedMetadata
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch as coroutineLaunch
import java.io.File
import java.io.FileInputStream

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

/**
 * Which player actually launches a game, most specific choice first: the
 * GAME's own setting, then the SYSTEM's, then the person's global default
 * emulator, then the first installed candidate. The order itself lives in
 * [EmulatorResolution], the one place that decides it, so the screens that
 * show the choice and this launch cannot disagree; [resolveEmulator] also
 * says which level answered.
 *
 * [altEmulator] is real ES-DE's own per-game concept (MetaData.cpp's
 * `altemulator`), matched against both the player id and its label,
 * because a person editing that field by hand types the name they see
 * ("RetroArch"), not an internal id. An alternative naming nothing
 * installed falls through to the next level rather than failing the
 * launch: the game still starts, just not with a player that isn't there.
 */
fun resolvePlayer(context: Context, system: ConsoleSystemDef, altEmulator: String? = null): Player.AmStart? =
    resolveEmulator(context, system, altEmulator)?.player

/**
 * The launch Intent for [romFile] under [player]: the template's own
 * placeholders plus the ones the system supplies. The one place an
 * emulator launch is built, for a real launch and for the emulator test.
 */
fun buildLaunchIntent(context: Context, system: ConsoleSystemDef, player: Player.AmStart, romFile: File): android.content.Intent {
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
    val parentPath = romFile.parentFile?.absolutePath
    val placeholders = buildMap {
        put("{file.dir}", parentPath ?: "")
        put("{file.basename}", romFile.nameWithoutExtension)
        put(IntegrationPlaceholders.SYSTEM_ID, system.id)
        put(IntegrationPlaceholders.SYSTEM_NAME, system.displayName)
        parentPath?.let { put(IntegrationPlaceholders.SYSTEM_FOLDER, it) }
    }
    return AmStartCommandToIntentConverter.toIntent(
        context,
        launchTemplateFor(player, player.storagePathTemplate != null && emulatorReadsStoragePaths(context, player.packageName)),
        romFile.absolutePath,
        placeholders,
    )
}

// The `-e`/`--es` string-extra flags from AmStartCommandToIntentConverter's
// own grammar -- only a LIBRETRO that follows one of these is the core
// extra, never a same-named value of some other extra.
private val STRING_EXTRA_FLAGS = setOf("-e", "--es")

// RetroArch core `.so` naming, most specific first: buildbot, the
// players database's real entries and [DefaultPlayers.retroArch] use
// `<core>_libretro_android.so`, while the old Daijishō-wiki shape and
// hand-typed custom players may use `<core>_android.so` -- both reduce
// to the same core id.
private val LIBRETRO_CORE_SO_SUFFIXES = listOf("_libretro_android.so", "_android.so")

/**
 * The libretro core a player choice for a system actually means -- the
 * `core` value [dev.droidtop.library.integrations.PluginEventBus] puts
 * in the `default_player_changed` payload (docs/SPEC.md 12a), which is
 * what a manager plugin uses to ensure the right core is downloaded.
 *
 * The chosen entry's OWN core first: a [Player.AmStart] carrying
 * RetroArch's documented `LIBRETRO` extra names the exact core it will
 * launch with, and that is not always the system-level default -- the
 * real case is psx, whose configured core is `mednafen_psx`
 * (platforms-database.json) while the players database's six real
 * RetroArch entries each name theirs in the template, so choosing
 * "Retroarch - beetle psx hw" launches `mednafen_psx_hw`, and an event
 * reporting the system core would make a manager plugin ensure the
 * wrong one. Read out with the same tokenizer that builds the launch
 * Intent, so quoting and the `-e`/`--es` shapes stay one mechanism --
 * and a template the tokenizer rejects (an unterminated quote, a
 * `{file.inject:...}` with no game) degrades to the fallback instead of
 * breaking the player-choice write path.
 *
 * [systemConfiguredCore] -- the per-system core setting,
 * [ConsoleSystemDef.retroArchCore] -- is that fallback, for entries
 * that name no core of their own: [DefaultPlayers.retroArch]'s
 * generated entry embeds exactly that value in its template, and a
 * standalone emulator carries no LIBRETRO extra at all.
 */
fun libretroCoreId(player: Player, systemConfiguredCore: String?): String? {
    val template = (player as? Player.AmStart)?.argumentsTemplate ?: return systemConfiguredCore
    val tokens = runCatching { AmStartCommandToIntentConverter.tokenize(template, null, null) }.getOrNull()
        ?: return systemConfiguredCore
    for (i in 1 until tokens.size) {
        if (tokens[i] != "LIBRETRO" || tokens[i - 1] !in STRING_EXTRA_FLAGS || i + 1 >= tokens.size) continue
        val so = File(tokens[i + 1]).name
        LIBRETRO_CORE_SO_SUFFIXES.firstOrNull { so.endsWith(it) }?.let { return so.removeSuffix(it) }
    }
    return systemConfiguredCore
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
/**
 * A launch that failed for a reason the user can actually fix, carrying
 * enough to offer that fix at the point of failure. A plain message
 * would leave the shell parsing English to decide what to show.
 */
class NoEmulatorInstalled(
    val systemId: String,
    val systemName: String,
    val suggestions: List<String>,
    message: String,
) : IllegalStateException(message)

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
 * How many folders below a games root a ROM system folder can sit.
 *
 * ES-DE's layout is `<root>/<systemId>/<rom>`; droidtop allows one
 * container level above it, because a real library keeps its systems in
 * `<root>/roms/<systemId>`. Nothing deeper is a system folder, whatever it
 * is named -- shared with [dev.droidtop.library.GameEngineDetector]'s walk
 * so the two walks cannot disagree about where systems live.
 */
internal const val MAX_SYSTEM_SEARCH_DEPTH = 2

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
        ?.takeIf { it.canResolveFromFolder() }
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
    // Where this provider's game records are written and read from
    // (docs/SPEC.md 7g, step 2) -- see [dev.droidtop.library.GameEngineDetector]'s
    // EngineGameProvider constructor for the same convention.
    private val records: dev.droidtop.library.GameRecordStore = dev.droidtop.library.NoOpGameRecordStore,
) : LibraryProvider, dev.droidtop.library.EntryFactsOwner {
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
        return scanRootsFresh(romsRoots, systemsById)
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
    override fun scanProgressive(): Flow<ScanStep> = channelFlow {
        val romsRoots = GamesRoots.current(context)
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        streamRootsProgressively(romsRoots, systemsById, emptySet())
    }

    /**
     * The cache's own rows, published as the parts they were cached
     * under -- one [ScanStep.Segment] per system folder of each root,
     * which is the same unit the fresh walk emits and the same unit
     * [RomDatabase] clears and rewrites. Sending them as one flat list
     * would say "this is the whole provider", and a folder the walk then
     * refreshed would replace all of it.
     */
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
    override fun rescanProgressive(): Flow<ScanStep> = channelFlow {
        val romsRoots = GamesRoots.current(context)
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        streamRootsProgressively(romsRoots, systemsById, emptySet())
    }

    /**
     * The slow rebuild pass (docs/SPEC.md 7g, step 4): the rescan's walk,
     * but only of the systems whose change stamp (see [unitStamp]) moved
     * since the index took it, with [pauseMs] after each system walked.
     * Nothing from the cache is sent -- the index already holds it -- and
     * a root that is not mounted right now is left as it is rather than
     * walked as empty, which would mark every game on it missing.
     *
     * This used to be the whole rescan, every round: every system folder
     * re-walked, headers re-read and media re-resolved, 5 s after every
     * start and every 30 minutes after that, for a library that had not
     * changed.
     */
    override fun slowRebuildProgressive(knownMtimes: Map<PartRef, Long>, pauseMs: Long): Flow<ScanStep> = channelFlow {
        val romsRoots = GamesRoots.current(context).filter { root ->
            root.isDirectory.also { mounted ->
                if (!mounted) ScanLog.write("roms root ${root.absolutePath}: not mounted right now, left as-is")
            }
        }
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        streamRootsProgressively(romsRoots, systemsById, scannedFolders = emptySet(), known = knownMtimes, pauseMs = pauseMs)
    }

    /**
     * A system's change stamp: one number over the modification times of
     * every folder its ROMs sit in -- the system's own folders, and the
     * parent folder of each ROM in [romPaths] (a system can be sorted
     * into subfolders, see [scanSystemFolder]). A folder's time moves
     * when an entry is added to or removed from it, so a ROM added,
     * removed or renamed in any of them, or a subfolder added to one,
     * moves the stamp. What it does not see is a ROM dropped into a
     * subfolder that held no ROM before; "Rescan library" is the answer
     * to that.
     *
     * 0 means unknown, which the slow pass walks: a folder that is gone,
     * or one whose time is not older than [takenBefore] -- at walk time
     * the moment the walk started, so a folder that changed while it was
     * being read is walked again next round rather than stamped as seen.
     *
     * One `stat` per folder, and a system's ROMs usually sit in one or
     * two, so a round over an unchanged library costs a handful of calls
     * per system.
     */
    private fun unitStamp(unit: SystemUnit, romPaths: Collection<String>, takenBefore: Long): Long {
        val folders = sortedSetOf<String>()
        unit.folders.mapTo(folders) { it.absolutePath }
        romPaths.mapNotNullTo(folders) { File(it).parent }
        var stamp = 17L
        for (folder in folders) {
            val mtime = File(folder).lastModified()
            if (mtime == 0L || mtime >= takenBefore) return 0L
            stamp = 31 * (31 * stamp + folder.hashCode()) + mtime
        }
        return if (stamp == 0L) 1L else stamp
    }

    private suspend fun ProducerScope<ScanStep>.streamRootsProgressively(
        romsRoots: List<File>,
        systemsById: Map<String, ConsoleSystemDef>,
        scannedFolders: Set<Pair<String, String>> = emptySet(),
        known: Map<PartRef, Long>? = null,
        pauseMs: Long = 0L,
    ) {
        coroutineScope {
            romsRoots.forEach { root ->
                coroutineLaunch {
                    val rootStartedAt = System.currentTimeMillis()
                    val rootGames = java.util.concurrent.atomic.AtomicInteger(0)
                    val systemScan = systemUnitsUnder(root, systemsById)
                    val units = systemScan.units
                    coroutineScope {
                        units.forEach { unit ->
                            val system = unit.system
                            coroutineLaunch {
                                try {
                                    val walkStartedAt = System.currentTimeMillis()
                                    val folderEntries = scanSystemUnit(unit)
                                    writeRomRecords(folderEntries, root, system.id)
                                    rootGames.addAndGet(folderEntries.size)
                                    send(
                                        ScanStep.Segment(
                                            key = system.id,
                                            root = root.absolutePath,
                                            entries = folderEntries,
                                            folderMtime = unitStamp(unit, folderEntries.map { it.id }, walkStartedAt),
                                        ),
                                    )
                                    if (pauseMs > 0) delay(pauseMs)
                                } catch (t: kotlinx.coroutines.CancellationException) {
                                    throw t
                                } catch (t: Throwable) {
                                    android.util.Log.e(
                                        "droidtop.ConsoleRomProvider",
                                        "scan system=${system.id} under ${root.absolutePath} FAILED",
                                        t,
                                    )
                                }
                            }
                        }
                    }
                    send(ScanStep.RootDone(root.absolutePath, systemScan.units.map { it.system.id }))
                    logRootSummary(root, systemScan, rootGames.get(), rootStartedAt)
                }
            }
        }
    }

    /** One line per root, whatever happened under it (see [ScanLog]). */
    private fun logRootSummary(root: File, systemScan: SystemScan, games: Int, startedAt: Long) {
        ScanLog.write(
            label = "roms root ${root.absolutePath}",
            games = games,
            skipped = systemScan.skipped,
            durationMs = System.currentTimeMillis() - startedAt,
            note = systemScan.units.joinToString(", ") { unit ->
                unit.system.id + if (unit.folders.size > 1) " (${unit.folders.size} folders)" else ""
            }.ifEmpty { "no console systems found" },
        )
    }

    private suspend fun scanRootsFresh(
        roots: List<File>,
        systemsById: Map<String, ConsoleSystemDef>,
    ): List<LibraryEntry> = coroutineScope {
        roots.flatMap { root ->
            systemUnitsUnder(root, systemsById).units
                .map { unit -> root to unit }
        }.map { (root, unit) ->
            async {
                val entries = scanSystemUnit(unit)
                writeRomRecords(entries, root, unit.system.id)
                entries
            }
        }.awaitAll().flatten()
    }


    /**
     * What a reported path is, to the ROM walk (docs/SPEC.md 7g, "Targeted
     * indexing"): a ROM file inside one of the roots' system folders, or a
     * folder inside one, whose own ROMs are looked at and no others. The
     * system is decided exactly as the walk decides it ([SystemFolders.systemFolderFor]),
     * the file by the same extension and add-on-folder rules
     * ([RomScanWalk]), the entry by the same code ([romEntries]); a path
     * that is not in a system folder, or a system folder itself (which is a
     * walk, not a report), is not this provider's.
     */
    override suspend fun indexPath(path: File): List<PathIndexing> {
        if (!path.exists()) return emptyList()
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        val place = SystemFolders.systemFolderFor(context, GamesRoots.current(context), path, systemsById) ?: return emptyList()
        val (root, systemFolder, system) = place
        if (path.path == systemFolder.path) return emptyList()
        val files = if (path.isFile) {
            val blocked = generateSequence(path.parentFile) { it.parentFile }
                .takeWhile { it.path.length >= systemFolder.path.length }
                .any { RomScanWalk.skipReason(it, systemFolder) != null }
            listOf(path).filter { !blocked && it.extension.lowercase() in system.extensions }
        } else {
            RomScanWalk.walk(path, system.extensions) { ScanBudget.start(ScanBudget.DEFAULT_FOLDER_BUDGET_MS) }.files
        }
        val entries = romEntries(files, systemFolder, system)
        writeRomRecords(entries, root, system.id)
        return listOf(PathIndexing(key = system.id, root = root.absolutePath, entries = entries, under = path.path))
    }

    /**
     * One system under one root: the system, and every folder under that
     * root holding its ROMs. A list of folders rather than one, because a
     * whole-library root can hold `ps2/` beside `roms/ps2/`, and the scan
     * cache is keyed on (root, system id) -- one unit of work per system
     * keeps that key honest instead of letting the second folder's rows
     * clear the first's.
     */
    private data class SystemUnit(val system: ConsoleSystemDef, val folders: List<File>)

    /** [systemUnitsUnder]'s result: the units of work, and what was refused. */
    private data class SystemScan(val units: List<SystemUnit>, val skipped: ScanSkips)

    /**
     * Every console system under [root], with the folders holding its
     * ROMs, plus counts of what was refused and why. The folders come
     * from [SystemFolders], the one walk every caller shares.
     */
    private fun systemUnitsUnder(root: File, systemsById: Map<String, ConsoleSystemDef>): SystemScan {
        val scan = SystemFolders.under(context, root, systemsById)
        val units = scan.found
            .groupBy { it.second.id }
            .map { (_, pairs) -> SystemUnit(pairs.first().second, pairs.map { it.first }) }
        return SystemScan(units, scan.skipped)
    }

    /** Every ROM of [unit]'s system under [unit]'s folders, each folder budgeted on its own. */
    private suspend fun scanSystemUnit(unit: SystemUnit): List<LibraryEntry> =
        unit.folders.flatMap { folder -> scanSystemFolder(folder, unit.system) }

    // Recursive, not just the system folder's immediate children -- lets a
    // large system be reorganized into subfolders (by first letter, by
    // collection, whatever) for real, purely as a filesystem organization
    // choice, without that reorganizing ever hiding files from droidtop:
    // every file under the system folder at any depth is still found and
    // still counted as belonging to that one system.
    private suspend fun scanSystemFolder(
        systemFolder: File,
        system: ConsoleSystemDef,
        folderBudgetMs: Long = ScanBudget.DEFAULT_FOLDER_BUDGET_MS,
    ): List<LibraryEntry> = coroutineScope {
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
        // Not a plain walkTopDown any more: a directory of DLC, updates
        // or BIOS images carries the system's own ROM extension and used
        // to become one library entry per file (a real case on this
        // device: one Rune Factory 5 DLC directory became twelve games,
        // each with its own metadata row and cover). RomScanWalk owns
        // that rule and its reasoning, and the scraper walks through the
        // same function so the two can never disagree about what a game
        // is.
        // One budget per system folder, and the log line below says what
        // this folder did in ONE line. Both replace a whole-provider
        // timeout and a line per skipped directory -- see ScanBudget and
        // ScanLog for the rig evidence behind each.
        val startedAt = System.currentTimeMillis()
        val romScan = RomScanWalk.walk(systemFolder, system.extensions) { ScanBudget.start(folderBudgetMs) }
        val romFiles = romScan.files
        // Real, genuine file detection beyond what either ES-DE or EmuDeck
        // actually do (both confirmed this session to be purely
        // folder+extension-based, no content/filename lookup at all --
        // SystemData::populateFolder's own real source, and EmuDeck's own
        // real roms/<system>/ layout, which uses the same ES-DE-derived
        // ids). A real Android ROM manager (Lemuroid, whose detection
        // code is forked in under romdetect/) does this properly: a
        // prioritized cascade -- embedded
        // disc serial/magic number first (SerialScanner, cheap,
        // header-only read, for the disc-image extensions it covers), then
        // a filename lookup against Lemuroid's own real, ~13MB community
        // ROM database (libretro-db.sqlite, bundled
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
        val entries = romEntries(romFiles, systemFolder, system)
        ScanLog.write(
            label = "rom folder ${systemFolder.absolutePath}",
            games = entries.size,
            skipped = ScanSkips.of(romScan.skipped),
            durationMs = System.currentTimeMillis() - startedAt,
            note = romScan.stoppedAt?.let { "stopped in ${it.absolutePath}" },
        )
        entries
    }

    /**
     * The library entries of [romFiles], which are files of [system] under
     * [systemFolder]: the one place a ROM file becomes an entry, for a
     * system folder's walk and for a single reported file
     * ([indexPath]) alike.
     */
    private suspend fun romEntries(
        romFiles: List<File>,
        systemFolder: File,
        system: ConsoleSystemDef,
    ): List<LibraryEntry> = coroutineScope {
        // The games root is systemFolder's own parent (<gamesRoot>/<systemId>/...),
        // same directory ES-DE's own `downloaded_media` sits alongside --
        // real per-game artwork when a user's existing ES-DE (or any other
        // scraper writing that same real layout) has already scraped it.
        // See EsDeArtwork's own doc comment for why droidtop reads this
        // rather than scraping itself.
        val gamesRoot = systemFolder.parentFile ?: systemFolder
        romFiles.map { romFile ->
            async {
                val effectiveSystemId = detectSystemIdFromContent(romFile)
                    ?: detectSystemIdFromFilename(romFile)
                    ?: system.id
                LibraryEntry(
                    id = romFile.absolutePath,
                    title = dev.droidtop.library.GameNaming.displayName(romFile.nameWithoutExtension),
                    kind = LibraryEntryKind.CONSOLE_ROM,
                    systemId = effectiveSystemId,
                    // Three media lookups per ROM, each answered from
                    // one listing per media folder (see EsDeArtwork), so
                    // an 18,000-file folder lists its dozen media
                    // folders once instead of stat-ing a million names.
                    artworkUri = EsDeArtwork.resolve(gamesRoot, effectiveSystemId, romFile.nameWithoutExtension),
                    manualUri = EsDeArtwork.resolveManual(gamesRoot, effectiveSystemId, romFile.nameWithoutExtension),
                    videoUri = EsDeArtwork.resolveVideo(gamesRoot, effectiveSystemId, romFile.nameWithoutExtension),
                    // Three strings already in hand -- no extra scan-time
                    // filesystem work at all. See GameMediaLocator.
                    mediaLocator = GameMediaLocator(
                        gamesRoot.absolutePath,
                        effectiveSystemId,
                        romFile.nameWithoutExtension,
                    ),
                )
            }
        }.awaitAll()
            // Four rows all reading "LIBRARY" is what a flat filename
            // title does to an engine that names every game's entry file
            // the same thing. See disambiguateTitles.
            .disambiguateTitles(systemFolder)
            .withMetadata()
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
        // Real reverse query for the badge "collection" slot -- separate
        // from the metadata merge since collection membership lives in
        // its own table, unrelated to whether a game has a game_metadata
        // row at all (see LibraryEntry.inCollection's own doc comment).
        val inAnyCollection = dao.getGameIdsInAnyCollection().toHashSet()
        return withScrapedMetadata(dao).map { entry ->
            if (entry.id in inAnyCollection) entry.copy(inCollection = true) else entry
        }
    }

    /**
     * The record write side of a ROM scan (docs/SPEC.md 7g, step 2).
     * [entry.systemId]/[entry.altEmulator] already sit directly on
     * [LibraryEntry] (they always did -- a ROM's launch never had the
     * quadratic re-detection cost engine games had), so this exists to
     * give a single-game view a record to read, and to keep every kind
     * of game backed by the same one-file-per-game mechanism rather than
     * ROMs being a second, unwritten case.
     */
    private fun writeRomRecords(entries: List<LibraryEntry>, root: File, systemId: String) {
        for (entry in entries) {
            records.put(
                dev.droidtop.library.GameRecord(
                    entry = entry,
                    provider = indexKey,
                    root = root.absolutePath,
                    part = systemId,
                    launch = dev.droidtop.library.LaunchFacts.Rom(
                        file = entry.id,
                        systemId = entry.systemId ?: systemId,
                        altEmulator = entry.altEmulator,
                    ),
                ),
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
        // The record's own launch facts before falling back to folder
        // re-detection (docs/SPEC.md 7g, step 2) -- entry.systemId is
        // usually already set (scan() always fills it), so this mostly
        // matters for a single-game view that has only the record, not a
        // full LibraryEntry from a fresh scan.
        val system = entry.systemId?.let { systemsById[it] }
            ?: (records.get(entry.id)?.launch as? dev.droidtop.library.LaunchFacts.Rom)?.systemId?.let { systemsById[it] }
            ?: run {
                ScanLog.write("record: ${entry.id} has no ROM launch facts; resolving its system from the folder")
                SystemOverridePrefs.resolveForFolder(context, parentFolder?.absolutePath ?: "", parentFolder?.name ?: "", systemsById)
            }
            ?: error("Couldn't resolve a console system for ${entry.id}")
        val player = resolvePlayer(context, system, entry.altEmulator)
            ?: throw NoEmulatorInstalled(
                systemId = system.id,
                systemName = system.displayName,
                suggestions = usableEmulatorNames(KnownPlayers.forSystem(context, system.id).map { it.label }),
                message = noEmulatorInstalledMessage(context, system),
            )
        // RetroArch sits black, with no error, when the core it is handed is missing
        // (Droidtop/tracker#271): when droidtop can see that, it installs the core first.
        RetroArchCores.needFor(player.packageName, player.argumentsTemplate)?.let { need ->
            val missing = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                RetroArchCores.state(need) == RetroArchCores.State.MISSING
            }
            if (missing) {
                val outcome = RetroArchCores.ensure(context, need)
                if (outcome is RetroArchCores.Outcome.Failed) error(outcome.line)
            }
        }
        val intent = when (val prepared = prepareLaunch(context, system, player, romFile)) {
            is PreparedLaunch.Ready -> prepared.intent
            is PreparedLaunch.Blocked -> error(prepared.reason)
        }
        if (player.killPackageProcesses) killPackageProcessesBestEffort(player.packageName)
        try {
            LaunchDisplay.start(context, intent)
        } catch (e: android.content.ActivityNotFoundException) {
            throw IllegalStateException(explainLaunchFailure(e, player.name), e)
        } catch (e: SecurityException) {
            throw IllegalStateException(explainLaunchFailure(e, player.name), e)
        }
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
     *
     * Every field the person changed is recorded as theirs
     * ([dev.droidtop.library.scraper.FieldSources.EDITED]), which is what
     * keeps a later scrape from writing over it (docs/SPEC.md 7h).
     */
    suspend fun saveMetadata(metadata: GameMetadataEntity) {
        val before = dao.getGameMetadataSingle(metadata.id)
        dao.upsertGameMetadata(
            metadata.copy(fieldSources = dev.droidtop.library.scraper.FieldSources.afterEdit(before, metadata)),
        )
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

    /** A collection of the person's own with the games [sourceId] holds now; null when [sourceId] is not a collection. */
    suspend fun copyCollection(sourceId: String): CollectionEntity? {
        val source = dao.getCollections().firstOrNull { it.id == sourceId } ?: return null
        val copy = CollectionEntity(id = java.util.UUID.randomUUID().toString(), name = dev.droidtop.library.stores.StoreCollections.copyName(source.name))
        dao.copyCollection(sourceId, copy)
        return copy
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

    /**
     * The metadata rows and collection memberships this provider's
     * database holds are keyed by entry id, and the whole library's
     * metadata lives in it -- not only ROMs' -- so folding a missing
     * game into the game that replaced it moves them here, for whatever
     * kind of game it was (docs/SPEC.md 7g,
     * [dev.droidtop.library.EntryFactsOwner]). Two present games made one
     * copy the row instead ([keepSource]), since both folders stay.
     */
    override suspend fun moveEntryFacts(fromId: String, toId: String, keepSource: Boolean) {
        dao.moveGameFacts(fromId, toId, keepSource)
    }

    /**
     * [LibraryProvider.quit] for ROM entries (Droidtop/tracker#82): the
     * exact same system/player resolution [launch] runs, then the task
     * manager's one close path for the player's package
     * ([dev.droidtop.runtime.tasks.TaskManager.close], docs/SPEC.md "The
     * task manager"). Returns [QuitResult.Unresolvable] (never attempted, not
     * "failed") when the entry's system or player can't be resolved at
     * all, e.g. it was uninstalled since launch.
     *
     * An earlier version also called `getAppTasks().finishAndRemoveTask()`
     * here, which only lists tasks whose root activity is droidtop's own, so
     * it never found a third-party emulator's task (tracker#245). Without a
     * privileged helper the result is an honest [QuitResult.NotEnded] that
     * says what to enable.
     */
    override suspend fun quit(entry: LibraryEntry): QuitResult {
        val romFile = File(entry.id)
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        val system = entry.systemId?.let { systemsById[it] }
            ?: run {
                val parentFolder = romFile.parentFile
                SystemOverridePrefs.resolveForFolder(context, parentFolder?.absolutePath ?: "", parentFolder?.name ?: "", systemsById)
            }
            ?: return QuitResult.Unresolvable("Couldn't resolve a console system for ${entry.id}; the game may have been uninstalled")
        val player = resolvePlayer(context, system, entry.altEmulator)
            ?: return QuitResult.Unresolvable("No emulator is installed for ${system.displayName}, so ${entry.title} can't be ended")
        return dev.droidtop.runtime.tasks.TaskManager.close(context, player.packageName).toQuitResult()
    }

    // Players with this Daijishō-preset flag (DuckStation among them) do
    // not reset their own state cleanly on a repeat launch, so a leftover
    // process is ended first. `killBackgroundProcesses` is the non-root
    // form: it ends the package's processes only while they are in the
    // background, which they are here, because droidtop is in front
    // launching. Android 14+ restricts it to the caller's own processes
    // for apps targeting 34, so there it does nothing and the emulator is
    // simply relaunched as it is (docs/SPEC.md 7i). Never fails a launch.
    private fun killPackageProcessesBestEffort(packageName: String) {
        try {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                .killBackgroundProcesses(packageName)
        } catch (t: Throwable) {
            android.util.Log.d("droidtop.ConsoleRomProvider", "Couldn't end $packageName's background processes", t)
        }
    }
}

