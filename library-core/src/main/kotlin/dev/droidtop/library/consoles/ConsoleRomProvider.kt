package dev.droidtop.library.consoles

import android.content.Context
import dev.droidtop.library.EsDeArtwork
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * Real, installed apps only -- a [KnownPlayers]/[DefaultPlayers] preset
 * existing doesn't mean the emulator is actually on this device (the whole
 * point of pulling in Daijishō's real, comprehensive preset list is
 * covering "whatever's actually installed," not assuming any one is).
 */
private fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
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
 * Folder-name mismatches actually confirmed between a real ROMs
 * collection (checked against a real test device this session) and
 * ES-DE's own canonical system ids -- not a guessed/exhaustive list, just
 * the real cases seen so far. Add more as they come up.
 */
private val SYSTEM_ID_ALIASES: Map<String, String> = mapOf(
    "ps1" to "psx",
    "3ds" to "n3ds",
    "nsw" to "switch",
    // Confirmed against a real ROMs collection this session (SD card,
    // /storage/<id>/Roms) -- these three folder names were silently
    // dropping their entire system (resolveSystem returned null, so
    // the whole folder never got scanned at all, not even as an
    // "unavailable player" skip) because no alias existed for them.
    "appleii" to "apple2",
    "palmos" to "palm",
    "psv" to "psvita",
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
    private fun scanSystemFolder(systemFolder: File, system: ConsoleSystemDef): List<LibraryEntry> {
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
        return systemFolder.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in system.extensions }
            .map { romFile ->
                LibraryEntry(
                    id = romFile.absolutePath,
                    title = romFile.nameWithoutExtension,
                    kind = LibraryEntryKind.CONSOLE_ROM,
                    systemId = system.id,
                    artworkUri = EsDeArtwork.resolve(gamesRoot, system.id, romFile.nameWithoutExtension),
                )
            }
            .toList()
    }

    override suspend fun launch(entry: LibraryEntry) {
        val romFile = File(entry.id)
        val parentFolder = romFile.parentFile
        val system = SystemOverridePrefs.resolveForFolder(context, parentFolder?.absolutePath ?: "", parentFolder?.name ?: "")
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
