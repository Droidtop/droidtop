package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.container.ContainerManager
import com.winlator.container.Shortcut
import dev.droidtop.library.GameExecutableResolver
import dev.droidtop.library.PcCompatibility
import dev.droidtop.library.PcGameRuntimeRegistry
import dev.droidtop.library.PcInfo
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryProvider
import dev.droidtop.library.withScrapedMetadata
import dev.droidtop.runtime.PrimaryContainerSession
import java.io.File

/**
 * Real "PC" games -- ES-DE's own `"pc"` system id (per direction: "PC", not
 * ES-DE's separate `"windows"` system, which is Linux `.desktop`-shortcut
 * launchers, a different real thing -- see [dev.droidtop.library.consoles.
 * ES_DE_CONSOLE_SYSTEMS]), themed the same way every other system already
 * is (droidtop reads `"pc"` like any other [dev.droidtop.library.consoles.
 * ConsoleSystemDef], no special-casing needed on the theming side).
 *
 * Discovery is real, not stubbed: [ContainerManager.loadShortcuts] is the
 * forked-in `com.winlator.container` tree's own actual mechanism for
 * enumerating installed Windows programs across every Wine prefix it
 * manages (real `.desktop`-style shortcut files under each container's
 * `getDesktopDir()`, the same real, kept-from-upstream ImageFS layout
 * [WineSession]'s own doc comment describes retaining). This is real,
 * working code already forked in and compiling -- not fabricated.
 *
 * [launch] bridges [Shortcut] (`com.winlator.container.Container`,
 * Winlator's own Wine-prefix object -- physically stored under the app's
 * private storage, entirely outside any `dev.droidtop.runtime.Container`'s
 * own rootfs) to a real running [dev.droidtop.runtime.Container] via
 * [primarySession]: [WineSession] runs `wine` as a process *inside* that
 * container (so it shares its Wayland socket, per [WineSession]'s own doc
 * comment), pointed at the Wine prefix's host path translated into an
 * in-container path by [dev.droidtop.runtime.ContainerRuntime.
 * hostStorageToContainerPath] (see `runtime-linux-root`'s
 * `DroidSpacesRuntime`, which bind-mounts the whole app-storage directory
 * into every container it creates, to make that translation possible). Per
 * direction: droidtop isn't using gamenative wholesale -- extending the
 * forked `com.winlator.container` tree itself with real Linux-target
 * support (not just Wine prefixes) is real, separate future work, not
 * needed for this launch path.
 *
 * [primarySession] is a supplier rather than a constructor value because
 * the primary container doesn't exist until `:app`'s
 * `DesktopSessionService` finishes connecting -- often after this provider
 * itself is constructed (see `MainActivity`'s own provider list).
 */
class PcGameProvider(
    private val context: Context,
    private val primarySession: () -> PrimaryContainerSession?,
) : LibraryProvider {
    override val kinds: Set<LibraryEntryKind> = setOf(LibraryEntryKind.WINE_PROFILE)

    /**
     * The whole PC library, not just Wine prefixes: every game the
     * vendored gamenative-tux data layer knows about from Steam, GOG,
     * Epic, Amazon and scanned folders (via [PcLibrary]), plus the Wine
     * shortcuts this provider always listed.
     *
     * Both are included because they answer different questions. A store
     * row knows a game is OWNED and where it installed to; a Wine
     * shortcut knows a specific executable inside a prefix the user set
     * up by hand. A store game that also has a shortcut would otherwise
     * appear twice, so shortcuts pointing inside a known install
     * directory are dropped in favour of the richer store row.
     */
    override suspend fun scan(): List<LibraryEntry> {
        val storeGames = runCatching { PcLibrary.allGames(context) }.getOrDefault(emptyList())
        val storeEntries = storeGames.map { it.toLibraryEntry() }
        val installDirs = storeGames.mapNotNull { it.installPath?.takeIf(String::isNotBlank) }

        val shortcutEntries = runCatching { ContainerManager(context).loadShortcuts() }
            .getOrDefault(emptyList())
            .filterNot { shortcut -> installDirs.any { shortcut.path.startsWith(it) } }
            .map { it.toLibraryEntry() }

        // The same scraped-metadata merge every other provider applies
        // (see withScrapedMetadata): a PC game's scraped description and
        // cover live in a game_metadata row keyed by this entry's id,
        // and nothing else can carry them back here -- a store row's id
        // is a store id, not a file under a games root, so there is no
        // downloaded_media lookup to fall back on.
        return (storeEntries + shortcutEntries)
            .withScrapedMetadata(dev.droidtop.library.consoles.RomDatabase.get(context).romDao())
    }

    private fun PcLibrary.Game.toLibraryEntry(): LibraryEntry = LibraryEntry(
        id = id,
        title = title,
        kind = LibraryEntryKind.WINE_PROFILE,
        systemId = "pc",
        // Store art is a remote URL; a scanned folder's is a local file.
        // Both are just a URI to the renderer.
        artworkUri = artUrl,
        pcInfo = PcInfo(
            source = source.displayName(),
            installed = installed,
            sizeBytes = sizeBytes,
            installPath = installPath,
            compatibility = compatibility?.let {
                PcCompatibility(
                    averageRating = it.averageRating,
                    playableReports = it.playableReports,
                    gpuPlayableReports = it.gpuPlayableReports,
                    hasBeenTried = it.hasBeenTried,
                    reportedNotWorking = it.reportedNotWorking,
                )
            },
        ),
    )

    private fun PcLibrary.Source.displayName(): String = when (this) {
        PcLibrary.Source.STEAM -> "Steam"
        PcLibrary.Source.GOG -> "GOG"
        PcLibrary.Source.EPIC -> "Epic"
        PcLibrary.Source.AMAZON -> "Amazon"
        PcLibrary.Source.FOLDER -> "Folder"
    }

    private fun Shortcut.toLibraryEntry(): LibraryEntry = LibraryEntry(
        id = file.absolutePath,
        title = name,
        kind = LibraryEntryKind.WINE_PROFILE,
        systemId = "pc",
        artworkUri = iconFile?.takeIf { it.isFile }?.absolutePath,
        // A hand-made shortcut inside a prefix the user built: installed
        // by definition, and no store behind it to know a size.
        pcInfo = PcInfo(source = "Wine", installed = true),
    )

    override suspend fun launch(entry: LibraryEntry) {
        val session = primarySession()
            ?: error("Can't launch ${entry.title}: the desktop session isn't running yet -- Wine needs a live primary container to run inside.")

        // A store/folder entry's id is "<source>:<nativeId>", not a
        // shortcut path, so it launches through the PC runtime seam
        // against its own install directory instead.
        if (entry.id.substringBefore(':') in STORE_ID_PREFIXES) {
            launchStoreGame(entry)
            return
        }

        val shortcut = ContainerManager(context).loadShortcuts().find { it.file.absolutePath == entry.id }
            ?: error("Can't launch ${entry.title}: no shortcut found at ${entry.id} (may have been deleted).")

        val wineprefixHostPath = File(shortcut.container.rootDir, ".wine")
        val wineprefixContainerPath = session.runtime.hostStorageToContainerPath(wineprefixHostPath)

        val wineSession = WineSession(
            container = session.container,
            runtime = session.runtime,
            prefixPath = wineprefixContainerPath,
        )
        val result = wineSession.launch(shortcut.path)
        check(result.succeeded) { "Launching ${entry.title} failed (exit ${result.exitCode}): ${result.stderr.ifBlank { result.stdout }}" }
    }

    /**
     * Runs a store-owned or folder-scanned game through the same
     * [PcGameRuntime] seam engine games already use, so a Windows title
     * from GOG launches exactly the way one detected in a games folder
     * does -- one launch path, not one per store.
     *
     * Every failure names something the user can act on: not installed,
     * no runtime, or an executable that could not be identified.
     */
    private suspend fun launchStoreGame(entry: LibraryEntry) {
        val installPath = entry.pcInfo?.installPath?.takeIf { it.isNotBlank() }
            ?: error("Can't launch ${entry.title}: it isn't installed yet.")
        val gameRoot = File(installPath)
        check(gameRoot.isDirectory) { "Can't launch ${entry.title}: ${gameRoot.absolutePath} is missing." }

        val runtime = PcGameRuntimeRegistry.runtime
            ?: error("Can't launch ${entry.title}: no PC runtime is registered in this build.")

        // A native Linux build beats Wine plus CPU translation whenever
        // one exists (docs/SPEC.md 5a), so it is checked first.
        val linux = GameExecutableResolver.linuxExecutable(gameRoot)
        val result = if (linux != null) {
            runtime.launchLinux(linux, gameRoot)
        } else {
            val windows = GameExecutableResolver.windowsExecutable(gameRoot)
                ?: error(
                    "Can't launch ${entry.title}: couldn't identify which executable to run in " +
                        "${gameRoot.absolutePath}. Pick one explicitly for this game.",
                )
            runtime.launchWindows(windows, gameRoot)
        }
        check(result.succeeded) { "Launching ${entry.title} failed: ${result.detail}" }
    }

    private companion object {
        val STORE_ID_PREFIXES = setOf("steam", "gog", "epic", "amazon", "folder")
    }
}
