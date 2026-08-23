package dev.droidtop.library.consoles

import android.content.Context
import dev.droidtop.library.EsDeArtwork
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryProvider
import dev.droidtop.library.romdetect.LibretroDatabase
import dev.droidtop.library.romdetect.SerialScanner
import dev.droidtop.library.romdetect.SystemID
import dev.droidtop.library.romdetect.toConsoleSystemId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val known = KnownPlayers.forSystem(system.id).map { it.player }
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

private val SYSTEMS_BY_ID: Map<String, ConsoleSystemDef> =
    ES_DE_CONSOLE_SYSTEMS.associateBy { it.id }

/** Resolves a ROMs subfolder name to a known [ConsoleSystemDef], checking [SYSTEM_ID_ALIASES] first. */
internal fun resolveSystem(folderName: String): ConsoleSystemDef? {
    val id = folderName.lowercase()
    return SYSTEMS_BY_ID[SYSTEM_ID_ALIASES[id] ?: id]
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
    private val romsRoots: List<File>,
) : LibraryProvider {
    override val kinds: Set<LibraryEntryKind> = setOf(LibraryEntryKind.CONSOLE_ROM)

    private val dao by lazy { RomDatabase.get(context).romDao() }
    private val libretroDao by lazy { LibretroDatabase.get(context).gameDao() }

    // Real bug this fixes, reported directly: a huge single system folder
    // (a real device's "j2me" folder had 18,126 files) made the *entire*
    // scan slow, since every system was scanned sequentially in one
    // coroutine -- one huge folder blocked every other system, fast or
    // slow, from ever returning. Each system folder now scans as its own
    // coroutine, so j2me being slow no longer holds up nes/gba/psx/etc,
    // which return as soon as their own (much smaller) folders are read.
    //
    // Real persistent cache (RomDatabase): a root that already has a real
    // scan_metadata row (a genuine prior full walk, not just "someone
    // asked once") returns its cached rom_entries rows directly instead
    // of re-walking the filesystem. A root scanned for the first time
    // ever still gets the real, full walk below, so the first launch
    // after adding a new ROMs root behaves exactly as before -- only
    // *repeat* scans of an already-known root get faster.
    override suspend fun scan(): List<LibraryEntry> {
        val scannedRootPaths = dao.getScannedRoots(romsRoots.map { it.absolutePath })
        val unscannedRoots = romsRoots.filter { it.absolutePath !in scannedRootPaths }
        val cached = dao.getEntries(scannedRootPaths).map { it.toLibraryEntry() }
        val fresh = scanRootsFresh(unscannedRoots)
        return cached + fresh
    }

    /**
     * Real, explicit "my ROMs changed, look again" action -- forces a full
     * filesystem walk of every configured root regardless of cache state,
     * replacing whatever was previously cached for each one. Not called
     * automatically anywhere; [dev.droidtop.shell.gamepad]'s own settings
     * screen is the real place to expose this as a user-triggered action
     * (not wired up in this pass -- the cache-population half of this
     * feature is the part that mattered most, an explicit UI trigger is
     * real, separate follow-up work).
     */
    suspend fun rescan(): List<LibraryEntry> {
        romsRoots.forEach { root ->
            dao.clearRoot(root.absolutePath)
            dao.clearScanMetadata(root.absolutePath)
        }
        return scanRootsFresh(romsRoots)
    }

    private suspend fun scanRootsFresh(roots: List<File>): List<LibraryEntry> = coroutineScope {
        roots.map { root ->
            async {
                val entries = (root.listFiles() ?: emptyArray()).filter { it.isDirectory }
                    .mapNotNull { systemFolder ->
                        SystemOverridePrefs.resolveForFolder(context, systemFolder.absolutePath, systemFolder.name)
                            ?.let { systemFolder to it }
                    }
                    .map { (systemFolder, system) -> async { scanSystemFolder(systemFolder, system) } }
                    .awaitAll()
                    .flatten()
                dao.clearRoot(root.absolutePath)
                dao.insertEntries(entries.map { it.toRomEntity(root.absolutePath) })
                dao.markScanned(ScanMetadataEntity(root.absolutePath, System.currentTimeMillis()))
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
        val romFiles = systemFolder.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in system.extensions }
            .toList()
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
                )
            }
        }.awaitAll()
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
        return try {
            FileInputStream(romFile).use { stream ->
                SerialScanner.extractInfo(romFile.name, stream).systemID?.toConsoleSystemId()
            }
        } catch (t: Throwable) {
            null
        }
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
        val system = entry.systemId?.let { SYSTEMS_BY_ID[it] }
            ?: SystemOverridePrefs.resolveForFolder(context, parentFolder?.absolutePath ?: "", parentFolder?.name ?: "")
            ?: error("Couldn't resolve a console system for ${entry.id}")
        val player = resolvePlayer(context, system)
            ?: error("No installed Player available for system ${system.id}")
        if (player.killPackageProcesses) killPackageProcessesBestEffort(player.packageName)
        val intent = AmStartCommandToIntentConverter.toIntent(player.argumentsTemplate, romFile.absolutePath)
        context.startActivity(intent)
    }

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

private fun LibraryEntry.toRomEntity(romsRoot: String): RomEntity = RomEntity(
    id = id,
    title = title,
    systemId = systemId ?: "",
    artworkUri = artworkUri,
    romsRoot = romsRoot,
)

private fun RomEntity.toLibraryEntry(): LibraryEntry = LibraryEntry(
    id = id,
    title = title,
    kind = LibraryEntryKind.CONSOLE_ROM,
    systemId = systemId,
    artworkUri = artworkUri,
)
