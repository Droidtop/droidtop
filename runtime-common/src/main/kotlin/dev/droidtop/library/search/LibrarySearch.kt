package dev.droidtop.library.search

import android.content.Intent

/**
 * One droidtop library entry (a game) shown as an app-drawer/QSB search
 * result, alongside ordinary app results. Only what a search row needs to
 * render and launch -- never the full `LibraryEntry` -- so this stays a
 * plain data holder shell-default (the search UI) can bind without
 * knowing anything about the library model behind it.
 */
data class LibrarySearchEntry(
    val id: String,
    val title: CharSequence,
    val artworkUri: String?,
    val launchIntent: Intent,
)

/**
 * The seam a library-aware search crosses: `:shell-default` (the launcher
 * and its app-drawer/QSB search, `DefaultAppSearchAlgorithm`) has no
 * dependency on `:library-core` (the game library) -- `:library-core`
 * already depends on `:shell-default` for icon-cache reuse
 * (`NativeAppProvider`), and the reverse would be a real circular module
 * dependency (see `library-core/build.gradle.kts`'s own comment on this).
 * `:runtime-common` has no dependency on anything else in the repo (see
 * `:app`'s own build.gradle.kts comment on `GESTURE_DOUBLE_TAP_ACTION`'s
 * neighbourhood), so it is where both sides meet: `:app`'s
 * `DroidtopApplication.onCreate` registers the real implementation, backed
 * by the same `Library`/`LibraryCore` instance every other surface (the
 * Games grid, the "Continue playing" widget) reads -- never a second
 * index or a folder walk of its own.
 */
object LibrarySearch {
    fun interface Source {
        /**
         * Matches [query] against the library's already-scanned, in-RAM
         * index only (docs/SPEC.md 7g: no per-game disk lookups here) --
         * called on every keystroke, so it must stay a plain filter over
         * a snapshot already in memory, never a scan.
         */
        fun search(query: String): List<LibrarySearchEntry>
    }

    @Volatile
    var source: Source? = null
}
