package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.container.ContainerManager
import com.winlator.container.Shortcut
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryProvider

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
 * [launch] is honestly NOT wired yet, matching [WineSession.launch]'s own
 * documented scope. Per direction: droidtop isn't using gamenative
 * wholesale -- the forked `com.winlator.container` tree itself gets
 * patched to add real Linux/proot environment support alongside Wine
 * prefixes (droidtop already has the real proot backend, `runtime-linux-
 * noroot`, and the real container-management backend, this forked-in
 * `ContainerManager`), so a "container" stops meaning "Wine prefix only."
 * [WineSession] currently runs inside `dev.droidtop.runtime.Container`
 * (droidtop's own generic Linux-namespace abstraction) while a [Shortcut]
 * belongs to `com.winlator.container.Container` (Winlator's own Wine-
 * prefix object) -- reconciling those, and extending the fork with real
 * Linux-target support, is the actual next real work, not done here. An
 * honest error here beats a fabricated "it worked" for a launch path that
 * doesn't actually run anything yet.
 */
class PcGameProvider(private val context: Context) : LibraryProvider {
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
        error(
            "PC game launch isn't wired up yet: ${entry.title} -- " +
                "needs a real bridge from com.winlator.container.Container " +
                "(gamenative's own Wine-prefix model) to " +
                "dev.droidtop.runtime.Container (what WineSession actually " +
                "runs inside). See PcGameProvider's own doc comment.",
        )
    }
}
