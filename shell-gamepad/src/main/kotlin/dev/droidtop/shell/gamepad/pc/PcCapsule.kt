package dev.droidtop.shell.gamepad.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.StoreDownloads
import dev.droidtop.library.kindLine
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.focusLift
import dev.droidtop.shell.gamepad.input.PadModality
import dev.droidtop.shell.gamepad.selectionFrame

/**
 * Steam's library capsule is 600x900, a 2:3 portrait, and every shelf and
 * grid in Steam Big Picture and on the Deck is built from it; box art
 * scraped for a PC game is the same shape. One shape for every capsule the
 * PC Games tab draws (docs/SPEC.md 7i).
 */
internal const val CAPSULE_ASPECT = 2f / 3f

/**
 * The hero card's shape: the landscape art (16:9) a scrape leaves as the
 * game's hero. The Continue playing shelf's first card is drawn this way,
 * at the capsules' own height, so the row keeps one baseline.
 */
internal const val HERO_ASPECT = 16f / 9f

/** A hero card as wide as its landscape art is at a capsule's height. */
internal fun heroWidth(capsuleWidth: Dp): Dp = capsuleWidth * (HERO_ASPECT / CAPSULE_ASPECT)

/**
 * How wide a capsule is: the window's usable width shared out so about five
 * and a half show across (the half says there is more, as Steam's rows do),
 * clamped so a phone held upright still gets whole, readable capsules.
 */
@Composable
internal fun capsuleWidth(): Dp {
    val window = LocalShellWindow.current
    val usable = window.widthDp.dp - window.edgePadding * 2
    return ((usable - Space.Md * 5) / 5.5f).coerceIn(104.dp, 220.dp)
}

/**
 * The one fact a capsule's top-left corner carries, from state already on
 * the entry and the live download map (no per-card lookup): a download
 * running or stopped, an update, a store game that is not installed, a
 * folder that went missing, or a store game that is installed. Null for the
 * rest (a folder game on this device needs no badge).
 */
internal enum class CapsuleStatus { DOWNLOADING, PAUSED, UPDATE, NOT_INSTALLED, MISSING, INSTALLED }

/** The [CapsuleStatus] of [entry]; pure, and built on the same [storeStageOf] the primary button reads. */
internal fun capsuleStatusOf(entry: LibraryEntry, download: StoreDownloads.Progress?): CapsuleStatus? {
    if (entry.missing) return CapsuleStatus.MISSING
    return when (storeStageOf(entry, download)) {
        StoreStage.DOWNLOADING -> CapsuleStatus.DOWNLOADING
        StoreStage.PAUSED -> CapsuleStatus.PAUSED
        StoreStage.UPDATE -> CapsuleStatus.UPDATE
        StoreStage.INSTALL -> CapsuleStatus.NOT_INSTALLED
        // A folder game whose update source names a newer version.
        null -> when {
            entry.availableUpdate != null -> CapsuleStatus.UPDATE
            entry.isStoreRow() -> CapsuleStatus.INSTALLED
            else -> null
        }
    }
}

/**
 * One game as the PC Games tab draws it: its box art, or the same plate
 * with its name when it has none. Nothing is drawn over the art but the
 * corner badges (owner, 2026-10-01: "I don't like the grid and weird
 * backing"): the art is the name, and the focused game's name and facts
 * are said once, in the tab's one line ([focusLine]), not on every card.
 * The corners: top-left the game's state ([CapsuleStatus]), top-right a
 * favourite, bottom-left the store it is from, bottom-right how many
 * folders or copies it stands for; a running download also draws a thin
 * progress bar along the bottom edge.
 *
 * The capsule is not a focus target. The tab moves ONE selection through
 * its one `onPad` handler (docs/SPEC.md 6e) and tells each capsule whether
 * it is the selected one; a finger moves that same selection by tapping,
 * and a second tap on the selected capsule is the pad's A. Long-press is
 * the touch route to Y (the game's page), the convention every card in
 * this shell follows.
 *
 * [hero] draws the game as a landscape card (docs/SPEC.md 7i, "Home art"):
 * its hero art, or, when only portrait art exists, that art beside the
 * title on the plate, and under it the name and one quiet line of when it
 * was last played ([heroCaption]). The caller gives it a [heroWidth].
 */
@Composable
internal fun PcCapsule(
    entry: LibraryEntry,
    selected: Boolean,
    width: Dp,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    download: StoreDownloads.Progress? = null,
    parts: Int = 1,
    hero: Boolean = false,
) {
    val shape = RoundedCornerShape(8.dp)
    val ring = selected && PadModality.showsFocus
    // The one focus treatment (docs/SPEC.md "Gaming motion and focus"): the
    // capsule lifts and gains a shadow under the cursor and the rest sit
    // slightly dimmed.
    val title = GameNaming.displayName(entry.title)
    Column(
        modifier = modifier
            .width(width)
            .pointerInput(entry.id) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
            },
        verticalArrangement = Arrangement.spacedBy(Space.Xs),
    ) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(if (hero) HERO_ASPECT else CAPSULE_ASPECT)
            .focusLift(ring, shape)
            .selectionFrame(selected, shape, rest = MenuTokens.Card, restOutline = MenuTokens.CardOutline),
    ) {
        // A hero card wants the landscape art; a capsule the box art.
        val art = if (hero) entry.heroUri else entry.artworkUri
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        } else if (hero && entry.artworkUri != null) {
            // Only portrait art: it is never stretched across a landscape
            // card. It sits at its own shape beside the title.
            Row(
                modifier = Modifier.fillMaxSize().padding(Space.Sm),
                horizontalArrangement = Arrangement.spacedBy(Space.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = entry.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxHeight().aspectRatio(CAPSULE_ASPECT).clip(RoundedCornerShape(6.dp)),
                )
                Text(
                    title,
                    color = MenuTokens.OnSurface,
                    style = TypeRole.rowTitle,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            // No art: the plate carries the name, as the Deck does for
            // a non-Steam shortcut with no artwork. Never a made-up
            // cover (design language, 2026-09-17).
            Column(
                modifier = Modifier.fillMaxSize().padding(Space.Md),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    title,
                    color = MenuTokens.OnSurface,
                    style = TypeRole.rowTitle,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.kindLine(),
                    color = MenuTokens.OnSurfaceMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (entry.favorite) {
            Text(
                "★",
                color = MenuTokens.Favourite,
                style = TypeRole.rowTitle,
                modifier = Modifier.align(Alignment.TopEnd).padding(Space.Sm),
            )
        }
        CapsuleStatusBadge(entry, download)
        CapsuleCorners(entry, download, parts)
    }
    if (hero) {
        Text(
            title,
            color = if (selected) MenuTokens.OnSurface else MenuTokens.Value,
            style = TypeRole.rowTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            heroCaption(entry, System.currentTimeMillis()),
            color = MenuTokens.OnSurfaceMuted,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    }
}

/** The top-left badge: the game's [CapsuleStatus], on a plate that stays legible over any art. */
@Composable
internal fun BoxScope.CapsuleStatusBadge(entry: LibraryEntry, download: StoreDownloads.Progress? = null) {
    val status = capsuleStatusOf(entry, download) ?: return
    val (text, fill, ink) = when (status) {
        CapsuleStatus.UPDATE -> Triple("Update", MenuTokens.Affirmative, MenuTokens.OnSelected)
        CapsuleStatus.DOWNLOADING -> Triple("${download?.percent ?: 0}%", MenuTokens.Selected, MenuTokens.OnSelected)
        CapsuleStatus.PAUSED -> Triple("Paused", MenuTokens.Scrim, MenuTokens.OnSurface)
        CapsuleStatus.NOT_INSTALLED -> Triple("Not installed", MenuTokens.Scrim, MenuTokens.OnSurface)
        CapsuleStatus.MISSING -> Triple("Missing", MenuTokens.Scrim, MenuTokens.Danger)
        CapsuleStatus.INSTALLED -> Triple("✓", MenuTokens.Scrim, MenuTokens.Affirmative)
    }
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(Space.Sm)
            .background(fill, RoundedCornerShape(50))
            .padding(horizontal = Space.Sm, vertical = Space.Hair),
    ) {
        Text(text, color = ink, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/**
 * The bottom corners and the progress bar: the store a game is from (one
 * letter, no store's own artwork), how many copies it stands for when more
 * than one, and a thin bar while a download runs.
 */
@Composable
private fun BoxScope.CapsuleCorners(entry: LibraryEntry, download: StoreDownloads.Progress?, parts: Int) {
    val source = entry.pcInfo?.source?.firstOrNull()?.uppercaseChar()
    if (entry.isStoreRow() && source != null) {
        CornerMark(source.toString(), Modifier.align(Alignment.BottomStart))
    }
    if (parts > 1) CornerMark("×$parts", Modifier.align(Alignment.BottomEnd))
    if (download != null) {
        Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(MenuTokens.Scrim)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(download.fraction.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(MenuTokens.Selected),
            )
        }
    }
}

@Composable
private fun CornerMark(text: String, modifier: Modifier) {
    Text(
        text,
        color = MenuTokens.OnSurface,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = modifier
            .padding(Space.Sm)
            .background(MenuTokens.Scrim, RoundedCornerShape(50))
            .padding(horizontal = Space.Sm, vertical = Space.Hair),
    )
}

/**
 * The one line that names the focused game and its facts, drawn once under
 * the capsules instead of on every card (docs/SPEC.md 7i): title, store,
 * state, version, size. Pure, for the tests.
 */
internal fun focusLine(entry: LibraryEntry, play: PcPlayState?, parts: Int): String = buildList {
    add(GameNaming.displayName(entry.title))
    entry.pcInfo?.takeIf { entry.isStoreRow() }?.let { add(it.source) }
    play?.takeIf { it.store != null }?.let { add(if (it.progress != null) "${it.verb} ${(it.progress * 100).toInt()}%" else it.verb) }
    entry.pcInfo?.installedVersion?.let { add(it) }
    entry.pcInfo?.sizeBytes?.takeIf { it > 0 }?.let { add(downloadSizeLabel(it)) }
    if (parts > 1) add("$parts copies")
}.joinToString(" · ")
