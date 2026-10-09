package dev.droidtop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.settings.CompanionHomeLayout
import dev.droidtop.library.settings.CompanionHomeSection

/**
 * The companion Home's two game rails, one mechanism ([CompanionRail]): Continue playing (the games last
 * played, newest first) and Recently added (the games the library saw most recently). The rails are the
 * companion's one interactive element that earns the touchscreen it sits on: "tap the game I was playing
 * yesterday" is the most common launcher action, so it is glanceable and one tap deep (docs/SPEC.md
 * section 4d, "The companion's tabs").
 *
 * Data comes from [CompanionState.homeActivity] -- the list the shell's Home shelves are built from, with
 * [CompanionState.libraryEntries] until it is published -- published by whatever drives the shell; the companion never runs its own scan. A tap goes through
 * [CompanionState.onLaunchEntry], which is the ordinary Library.launch path (launch-screen memory and the
 * chooser included), not a second launch mechanism.
 */
@Composable
internal fun CompanionRecents(layout: CompanionHomeLayout) {
    val entries = railEntries()
    val recents = remember(entries) { companionRecents(entries) }
    CompanionRail(CompanionHomeSection.CONTINUE, layout, recents)
}

@Composable
internal fun CompanionRecentlyAdded(layout: CompanionHomeLayout) {
    val entries = railEntries()
    val added = remember(entries) { companionRecentlyAdded(entries) }
    CompanionRail(CompanionHomeSection.RECENTLY_ADDED, layout, added)
}

/** What the rails draw from: the shell Home's own activity list, else the scanned games until it is published. */
@Composable
private fun railEntries(): List<LibraryEntry> {
    val home by CompanionState.homeActivity.collectAsState()
    val scanned by CompanionState.libraryEntries.collectAsState()
    return home.ifEmpty { scanned }
}

/** The games last played, newest first, one card per game, at most [MAX_RECENTS]. Pure. */
internal fun companionRecents(entries: List<LibraryEntry>): List<LibraryEntry> =
    entries.filter { it.lastPlayedEpochMs != null }
        .sortedByDescending { it.lastPlayedEpochMs }
        .distinctGames()
        .take(MAX_RECENTS)

/**
 * The games the library saw most recently, newest first (an installed app falls back to its install time,
 * the same rule as Home's own Recently added shelf), at most [MAX_RECENTS]. Rows with no timestamp are left
 * out. Pure.
 */
internal fun companionRecentlyAdded(entries: List<LibraryEntry>): List<LibraryEntry> =
    entries.filter { addedEpochMs(it) > 0L }
        .sortedByDescending { addedEpochMs(it) }
        .distinctGames()
        .take(MAX_RECENTS)

private fun addedEpochMs(entry: LibraryEntry): Long =
    entry.firstSeenEpochMs.takeIf { it > 0L } ?: entry.appFacts?.firstInstalledEpochMs ?: 0L

// Real, reported bug (rig, p1-dt-companion-text-overlap): the same game showed twice in the rail. A rescan can
// hand back two LibraryEntry ids for one game while its id is settling (the exact case
// PlayHistoryDatabase.moveTo exists to reconcile once it does), so a rail -- sorted by recency, shown to the
// user directly -- dedupes defensively: first by id (a literal duplicate), then by title+system (two ids,
// one game), keeping the first of each pair since the list is already in its order.
private fun List<LibraryEntry>.distinctGames(): List<LibraryEntry> =
    distinctBy { it.id }.distinctBy { it.title.trim().lowercase() to it.systemId }

/**
 * One rail: the section heading over a row of capsules that scrolls sideways inside the page's one vertical
 * scroll. Capsules are small (80dp, 2:3) so a rail and the heading of the next section share the first
 * screen. Every capsule is reachable by a sideways swipe.
 */
@Composable
private fun CompanionRail(section: CompanionHomeSection, layout: CompanionHomeLayout, games: List<LibraryEntry>) {
    if (games.isEmpty()) return
    CompanionHomeSectionFrame(section, layout, summary = games.first().title) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(games, key = { it.id }) { entry ->
                RailCapsule(entry)
            }
        }
    }
}

@Composable
private fun RailCapsule(entry: LibraryEntry) {
    CompanionTile(onClick = { CompanionState.onLaunchEntry?.invoke(entry) }, shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.width(RAIL_CAPSULE_WIDTH)) {
            CompanionCapsuleArt(entry, RAIL_CAPSULE_WIDTH)
            Text(
                entry.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
        }
    }
}

private val RAIL_CAPSULE_WIDTH = 80.dp

// One rail row: enough for "what was I playing this week", few enough
// to stay glanceable next to the widgets below it.
private const val MAX_RECENTS = 10
