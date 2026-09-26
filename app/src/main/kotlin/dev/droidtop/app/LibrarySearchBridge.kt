package dev.droidtop.app

import android.content.Context
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.search.LibrarySearch
import dev.droidtop.library.search.LibrarySearchEntry

/**
 * Registers droidtop's own real [LibrarySearch.Source], so the app-drawer/
 * QSB search in `:shell-default` (which cannot depend on `:library-core`,
 * see [LibrarySearch]'s own doc comment) can show games alongside apps
 * (docs/SPEC.md, Launcher mode, "App-drawer/QSB search over droidtop's own
 * library"). Reads the same in-RAM index every other surface reads
 * ([LibraryCore.library]'s `backgroundScanState`) -- never a folder walk
 * of its own, and this filter runs on every keystroke, so it must stay
 * exactly that: a plain filter over a snapshot already in memory.
 */
object LibrarySearchBridge {
    /** Never surface more results than a search row list is meant to hold. */
    private const val MAX_RESULTS = 5

    fun install(context: Context) {
        val app = context.applicationContext
        LibrarySearch.source = LibrarySearch.Source { query ->
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@Source emptyList()
            val entries = LibraryCore.library(app).backgroundScanState(LibraryKinds.GAMES).value
                ?: return@Source emptyList()
            entries.asSequence()
                .filter { !it.hidden && !it.missing }
                .filter { it.title.contains(trimmed, ignoreCase = true) }
                .take(MAX_RESULTS)
                .map { entry ->
                    LibrarySearchEntry(
                        id = entry.id,
                        title = GameNaming.displayName(entry.title),
                        artworkUri = entry.iconUri ?: entry.artworkUri,
                        launchIntent = GameLaunchActivity.intentFor(app, entry.id),
                    )
                }
                .toList()
        }
    }
}
