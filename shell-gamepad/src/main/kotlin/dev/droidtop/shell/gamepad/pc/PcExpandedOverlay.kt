package dev.droidtop.shell.gamepad.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.PcRunnerOptions
import dev.droidtop.library.ResolvedRunner
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.ShellChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The PC group's own "expanded view" regions, layered OVER the active
 * theme's own gamelist canvas rather than shrinking it (docs/SPEC.md 7i,
 * owner direction 2026-09-26: "it's an EXPANDED view... PC-specific
 * organisation: the store/source and engine filters, and install
 * state... richer information per game: runner, source/store, versions,
 * update state, ProtonDB, play time"). The theme still draws every
 * standard element at its own position and size; these two strips are
 * droidtop's own chrome, in droidtop's own palette, drawn where the ES-DE
 * element schema has no slot for them at all -- filters and per-game
 * runner/source facts are not ES-DE concepts a theme could ever declare.
 *
 * A [BoxScope] extension, not a plain composable: both strips anchor to
 * the SAME full-screen Box the theme's own canvas fills, with
 * [Modifier.align], rather than shrinking it -- the same lesson the
 * shell's help row already learned (docs/SPEC.md 7j). Both carry their
 * own semi-transparent backing so they read against any theme's own art.
 */
@Composable
internal fun BoxScope.PcExpandedOverlay(
    entries: List<LibraryEntry>,
    focused: LibraryEntry?,
    sources: Set<String>,
    engines: Set<String>,
    installedOnly: Boolean,
    onSourcesChanged: (Set<String>) -> Unit,
    onEnginesChanged: (Set<String>) -> Unit,
    onInstalledOnlyChanged: (Boolean) -> Unit,
) {
    val window = LocalShellWindow.current
    val allSources = entries.map { it.sourceLabel() }.distinct().sorted()
    val allEngines = entries.mapNotNull { it.engineLabel() }.distinct().sorted()

    // PC-specific organisation: source/store, engine and install state
    // (owner direction 2026-09-26) -- the same three facts PcSurface's
    // own retired chip row filtered on, now filtering the SAME themed
    // gamelist instead of a fixed grid of its own.
    if (allSources.size > 1 || allEngines.isNotEmpty()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(MenuTokens.Scrim, RoundedCornerShape(bottomEnd = 12.dp, bottomStart = 12.dp))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = window.edgePadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ShellChip("Installed", on = installedOnly, onClick = { onInstalledOnlyChanged(!installedOnly) })
            allSources.forEach { source ->
                ShellChip(source, on = source in sources, onClick = { onSourcesChanged(sources.toggled(source)) })
            }
            allEngines.forEach { engine ->
                ShellChip(engine, on = engine in engines, onClick = { onEnginesChanged(engines.toggled(engine)) })
            }
        }
    }

    // Richer per-game information (owner direction 2026-09-26): runner,
    // source, play time -- none of it an ES-DE metadata field, so none of
    // it can be a theme element. Resolved for the ONE focused game, off
    // the main thread, the same cost PcGameMenu's own "Runs with" row
    // always paid for one open game and never for a whole list.
    if (focused != null && !focused.missing) {
        val context = LocalContext.current
        val runner by produceState<ResolvedRunner?>(null, focused.id) {
            value = null
            value = withContext(Dispatchers.IO) {
                val runners = PcRunnerOptions.forEntry(context, focused)
                PcRunnerOptions.resolvedFor(context, focused, runners)
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(window.edgePadding)
                .background(MenuTokens.Scrim, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                runner?.let { "${it.label} — ${it.reason}" } ?: "Working out what can run this…",
                color = MenuTokens.OnSurface,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                buildString {
                    append(focused.sourceLabel())
                    if (focused.playtimeSeconds > 0) append(" — played ${focused.playtimeSeconds / 60} min")
                    focused.availableUpdate?.let { append(" — update available") }
                },
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun Set<String>.toggled(value: String): Set<String> = if (value in this) this - value else this + value
