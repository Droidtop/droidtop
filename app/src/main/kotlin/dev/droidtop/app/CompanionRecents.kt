package dev.droidtop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.LibraryEntry

/**
 * Continue-playing rail: the companion's one interactive element that
 * earns the touchscreen it sits on. The lower panel is a touch surface
 * the user's thumbs already rest near, and "tap the game I was playing
 * yesterday" is the single most common launcher action -- so it lives
 * here, glanceable and one tap deep, instead of only behind gamepad
 * navigation on the other screen (docs/SPEC.md section 4d).
 *
 * Data comes from [CompanionState.libraryEntries] -- the same feed the
 * idle rotation uses, published by whatever drives the shell; the
 * companion never runs its own scan. A tap goes through
 * [CompanionState.onLaunchEntry], which is the ordinary Library.launch
 * path (launch-screen memory and the chooser included), not a second
 * launch mechanism.
 */
@Composable
internal fun CompanionRecents() {
    val entries by CompanionState.libraryEntries.collectAsState()
    val recents = remember(entries) {
        entries.filter { it.lastPlayedEpochMs != null }
            .sortedByDescending { it.lastPlayedEpochMs }
            .take(MAX_RECENTS)
    }
    if (recents.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "Continue playing",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 6.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(recents, key = { it.id }) { entry ->
                RecentCard(entry)
            }
        }
    }
}

@Composable
private fun RecentCard(entry: LibraryEntry) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable { CompanionState.onLaunchEntry?.invoke(entry) }
            .padding(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        ) {
            if (!entry.artworkUri.isNullOrBlank()) {
                AsyncImage(
                    model = entry.artworkUri,
                    contentDescription = entry.title,
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // No art scraped: the title carries the card, centred in
                // the same frame -- never an empty rectangle.
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.padding(8.dp).align(androidx.compose.ui.Alignment.Center),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            entry.title,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// One rail row: enough for "what was I playing this week", few enough
// to stay glanceable next to the widgets below it.
private const val MAX_RECENTS = 10
