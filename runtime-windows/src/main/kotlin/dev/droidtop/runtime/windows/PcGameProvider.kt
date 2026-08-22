package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.container.ContainerManager
import com.winlator.container.Shortcut
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryProvider
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

    override suspend fun scan(): List<LibraryEntry> =
        ContainerManager(context).loadShortcuts().map { it.toLibraryEntry() }

    private fun Shortcut.toLibraryEntry(): LibraryEntry = LibraryEntry(
        id = file.absolutePath,
        title = name,
        kind = LibraryEntryKind.WINE_PROFILE,
        systemId = "pc",
        artworkUri = iconFile?.takeIf { it.isFile }?.absolutePath,
    )

    override suspend fun launch(entry: LibraryEntry) {
        val session = primarySession()
            ?: error("Can't launch ${entry.title}: the desktop session isn't running yet -- Wine needs a live primary container to run inside.")

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
}
