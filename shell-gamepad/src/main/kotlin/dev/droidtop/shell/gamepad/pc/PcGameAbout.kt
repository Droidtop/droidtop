package dev.droidtop.shell.gamepad.pc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidtop.library.GameLink
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.scraper.FieldSources
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.selectionFrame

/**
 * "About this game": the flavour the scrape brought (docs/SPEC.md 7h),
 * on the game's own screen, where players look. Nothing here is drawn
 * when nothing was scraped: an empty "About" with placeholder dashes
 * would say there is something to read when there is not.
 *
 * The description is a stop of its own for the pad (a long text between
 * two buttons would otherwise scroll past unread), and A on it shows the
 * rest. Each link is a row that opens it. The last line says where each
 * field came from, in the words the scrape recorded.
 */
internal fun LazyListScope.pcAboutItems(
    entry: LibraryEntry,
    onHint: (String?) -> Unit,
    onOpenLink: (GameLink) -> Unit,
) {
    val facts = aboutFacts(entry)
    val hasAnything = entry.description != null || facts.isNotEmpty() || entry.links.isNotEmpty()
    if (!hasAnything) return

    item(key = "about:title") {
        Text(
            "About this game",
            color = MenuTokens.OnSurfaceMuted,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
    entry.description?.let { description ->
        item(key = "about:description") { DescriptionCard(description, onHint) }
    }
    if (facts.isNotEmpty()) {
        item(key = "about:facts") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                facts.forEach { (label, value) ->
                    Row {
                        Text(
                            label,
                            color = MenuTokens.OnSurfaceMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(120.dp),
                        )
                        Text(value, color = MenuTokens.Value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    if (entry.links.isNotEmpty()) {
        items(entry.links, key = { "about:link:" + it.url }) { link ->
            DetailRow(title = link.label, detail = link.url, enabled = true, onSelect = { onOpenLink(link) })
        }
    }
    sourcesLine(entry)?.let { line ->
        item(key = "about:sources") {
            Text(
                line,
                color = MenuTokens.OnSurfaceDisabled,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/** The labelled facts, in the order a store page lists them; only the ones that exist. */
internal fun aboutFacts(entry: LibraryEntry): List<Pair<String, String>> = listOfNotNull(
    entry.developer?.let { "Developer" to it },
    entry.publisher?.let { "Publisher" to it },
    entry.releaseDate?.let { formatReleaseDate(it) }?.let { "Released" to it },
    entry.genre?.let { "Genre" to it },
    entry.series?.let { "Series" to it },
    // ES-DE's 0-1 rating, shown on the five-star scale ES-DE draws it on.
    entry.rating?.let { "Rating" to String.format(java.util.Locale.US, "%.1f / 5", it * 5) },
)

/**
 * ES-DE's `YYYYMMDDT000000` as a date a person reads, in their locale;
 * null for anything that is not a full date (nothing here widens a year
 * into a day).
 */
internal fun formatReleaseDate(raw: String): String? {
    val parsed = runCatching {
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply {
            isLenient = false
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse(raw.take(8))
    }.getOrNull() ?: return null
    return java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.format(parsed)
}

/**
 * "Description from IGDB. Cover and hero art from SteamGridDB." -- each
 * source once, with the fields it gave, in the order the fields are
 * listed. Null when nothing recorded a source.
 */
internal fun sourcesLine(entry: LibraryEntry): String? {
    if (entry.fieldSources.isEmpty()) return null
    val order = FieldSources.LABELS.keys.toList()
    val bySource = entry.fieldSources.entries
        .sortedBy { order.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        .groupBy({ it.value }, { FieldSources.LABELS[it.key] ?: it.key })
    return bySource.entries.joinToString(" ") { (source, fields) ->
        val list = fields.mapIndexed { i, field -> if (i == 0) field else field.lowercase() }
        val joined = if (list.size == 1) list.single() else list.dropLast(1).joinToString(", ") + " and " + list.last()
        if (source == FieldSources.EDITED) "$joined edited by you." else "$joined from $source."
    }
}

@Composable
private fun DescriptionCard(description: String, onHint: (String?) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var expanded by remember(description) { mutableStateOf(false) }
    fun hint() = onHint(if (focused) (if (expanded) "Show less" else "Read more") else null)
    fun toggle() {
        expanded = !expanded
        hint()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Ahead of the focus target, as in DetailRow.
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && GamepadKeyMap.actionFor(event.key) == GamepadAction.A) {
                    toggle()
                    true
                } else {
                    false
                }
            }
            .onFocusChanged {
                focused = it.isFocused
                hint()
            }
            .focusable()
            .clickable { toggle() }
            .selectionFrame(focused, RoundedCornerShape(10.dp), rest = MenuTokens.CardInset)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            description,
            color = MenuTokens.OnSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val DESCRIPTION_LINES = 6
