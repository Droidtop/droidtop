package dev.droidtop.shell.gamepad.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.droidtop.library.GameNaming
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.StoreDownloads
import dev.droidtop.library.kindLine
import androidx.compose.ui.layout.onGloballyPositioned
import dev.droidtop.shell.gamepad.Corners
import dev.droidtop.shell.gamepad.FloodOrigin
import dev.droidtop.shell.gamepad.FocusLook
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.ShellProgressBar
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.StatusChip
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.focusLift
import dev.droidtop.shell.gamepad.input.PadModality
import dev.droidtop.shell.gamepad.selectionFrame
import dev.droidtop.shell.gamepad.shine

/**
 * Steam's library capsule is 600x900, a 2:3 portrait, and every shelf and
 * grid in Steam Big Picture and on the Deck is built from it; box art
 * scraped for a PC game is the same shape. One shape for every capsule the
 * PC Games tab draws (docs/SPEC.md 7i).
 */
internal const val CAPSULE_ASPECT = 2f / 3f

/**
 * How many capsules wide the hero card is: Steam's featured card on Home is
 * about 3.2 portrait capsules wide at the same height, so the game you most
 * likely want is the biggest thing on the screen and the row keeps one
 * baseline.
 */
internal const val HERO_CAPSULES_WIDE = 3.2f

/**
 * The hero card's shape: [HERO_CAPSULES_WIDE] capsules at a capsule's
 * height, a little wider than 2:1. Landscape art (16:9) fills it with a
 * thin band cropped top and bottom, as Steam's featured card does.
 */
internal const val HERO_ASPECT = CAPSULE_ASPECT * HERO_CAPSULES_WIDE

/** A hero card as wide as [HERO_CAPSULES_WIDE] capsules. */
internal fun heroWidth(capsuleWidth: Dp): Dp = capsuleWidth * (HERO_ASPECT / CAPSULE_ASPECT)

/**
 * How wide a capsule is, on every shelf and in every grid: Steam's three
 * window tiers (measured, docs/SPEC.md 7i), so a window up to 853dp wide
 * shows 110dp capsules (about six across on the console's 768dp window, a
 * phone held upright three), a mid window 134dp and a wide one 172dp. The
 * grid packs whole capsules of this width with the shelf's own gap; it never
 * stretches them.
 */
@Composable
internal fun capsuleWidth(): Dp = capsuleWidthFor(LocalShellWindow.current.widthDp.toFloat())

/** [capsuleWidth] for a window [widthDp] wide. Pure. */
internal fun capsuleWidthFor(widthDp: Float): Dp = when {
    widthDp <= CAPSULE_TIER_NARROW_MAX_DP -> 110.dp
    widthDp < CAPSULE_TIER_WIDE_MIN_DP -> 134.dp
    else -> 172.dp
}

/** The window widths where Steam's capsule tiers change. */
internal const val CAPSULE_TIER_NARROW_MAX_DP = 853f
internal const val CAPSULE_TIER_WIDE_MIN_DP = 1280f

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
 * The focus look is Steam's (docs/SPEC.md "Gaming motion and focus"): the
 * capsule lifts ([focusLift]), the window's ring sits just outside its
 * crisp corners, and a sheen crosses it once as the cursor arrives.
 *
 * [badge], set on the shelves, says what the game is (PC, Retro, App,
 * Engine) and its store or system in words at the bottom-left (it replaces
 * the store letter).
 *
 * [hero] draws the game as a landscape card (docs/SPEC.md 7i, "Home art"):
 * its hero art, or, when only portrait art exists, that art whole beside
 * the title over a soft, darkened copy of itself, and under it the name
 * and one small label of when it was last played ([heroCaption]). The
 * caller gives it a [heroWidth].
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
    badge: KindBadge? = null,
) {
    val shape = Corners.Crisp
    val ring = selected && PadModality.showsFocus
    val title = GameNaming.displayName(entry.title)
    val view = androidx.compose.ui.platform.LocalView.current
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
            // The capsule under the cursor says where it is, so its page can grow out of it
            // (PageFlood.kt); only that one, so a scroll costs the others nothing.
            .then(
                if (selected) {
                    Modifier.onGloballyPositioned { FloodOrigin.trackCapsule(entry.id, it, view) }
                } else {
                    Modifier
                },
            )
            .focusLift(ring, shape, wide = hero)
            .selectionFrame(
                selected,
                shape,
                rest = MenuTokens.Card,
                restOutline = MenuTokens.CardOutline,
                ringOutset = FocusLook.RingOffsetDp.dp,
            )
            .shine(ring),
    ) {
        // A hero card wants the landscape art; a capsule the box art.
        val art = if (hero) entry.heroUri else entry.artworkUri
        // Art that names a file which will not load (moved, not an image Android decodes) is no
        // art: the card falls back to the plate with the name instead of staying an empty frame
        // (console, build 1386: three blank cards on Home's Recently added).
        var artFailed by remember(art) { mutableStateOf(false) }
        if (art != null && !artFailed) {
            CapsuleArt(art, title, wholeIfWide = !hero, onError = { artFailed = true })
        } else if (hero && entry.artworkUri != null && entry.artworkUri != art) {
            // Only portrait art: it is never stretched across a landscape
            // card. It sits whole at its own shape beside the title, over a
            // soft, darkened copy of itself that fills the card (DroidDeck's
            // GameHero, ui/FrontEndGames.kt at 9310d19), never a flat slab.
            SoftArt(entry.artworkUri!!)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to MenuTokens.Ground.copy(alpha = 0.92f),
                            0.55f to MenuTokens.Ground.copy(alpha = 0.6f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
            Row(
                modifier = Modifier.fillMaxSize().padding(Space.Sm),
                horizontalArrangement = Arrangement.spacedBy(Space.Lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = entry.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxHeight().aspectRatio(CAPSULE_ASPECT).clip(Corners.Crisp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Hair)) {
                    Text(
                        entry.kindLine().uppercase(),
                        color = MenuTokens.Accent,
                        style = TypeRole.eyebrow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        title,
                        color = MenuTokens.OnSurface,
                        style = TypeRole.heroTitle,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            // No art: the plate carries the name, as the Deck does for
            // a non-Steam shortcut with no artwork. Never a made-up
            // cover (design language, 2026-09-17). Centred, not on the
            // bottom edge: a shelf low on the screen shows its cards cut
            // off by the footer until it is scrolled to, and a name on
            // the bottom edge left those plates looking empty; the bottom
            // corner marks (source, copies) also sat on its second line
            // (console, build 1397, Droidtop/tracker#273).
            Column(
                modifier = Modifier.fillMaxSize().padding(Space.Md),
                verticalArrangement = Arrangement.Center,
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
        CapsuleCorners(entry, download, parts, badge)
    }
    if (hero) {
        // Steam's featured card: the name in bold under the card, and under
        // the name one small uppercase line of when it was played.
        Text(
            title,
            color = if (selected) MenuTokens.OnSurface else MenuTokens.Value,
            style = TypeRole.rowTitle,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            heroCaption(entry, System.currentTimeMillis()).uppercase(),
            color = MenuTokens.OnSurfaceMuted,
            style = TypeRole.eyebrow,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    }
}

/**
 * A capsule's art. Box art fills the capsule. Wide art in a portrait
 * capsule ([wholeIfWide]) -- a Retro game's screenshot, a header image -- is
 * shown whole over a soft, darkened copy of itself instead of losing its
 * sides to the crop (DroidDeck's CoverImage, ui/FrontEndArt.kt at 9310d19).
 * The soft copy is a small decode scaled up, not a blur (docs/SPEC.md 7i,
 * "no live blur"), and is only requested once the art has turned out wide.
 */
@Composable
private fun BoxScope.CapsuleArt(art: String, title: String, wholeIfWide: Boolean, onError: () -> Unit) {
    var wide by remember(art) { mutableStateOf(false) }
    if (wide) {
        SoftArt(art)
        Box(Modifier.matchParentSize().background(MenuTokens.Scrim.copy(alpha = SOFT_ART_DARKEN)))
    }
    AsyncImage(
        model = art,
        contentDescription = title,
        contentScale = if (wide) ContentScale.Fit else ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
        onError = { onError() },
        onSuccess = { state ->
            val size = state.painter.intrinsicSize
            if (wholeIfWide && isWideArt(size.width, size.height)) wide = true
        },
    )
}

/** A soft copy of [art] filling its box: a tiny decode scaled up, so it is soft by construction. */
@Composable
private fun SoftArt(art: String) {
    val context = LocalContext.current
    AsyncImage(
        model = remember(art) {
            ImageRequest.Builder(context).data(art).size(coil3.size.Size(SOFT_ART_WIDTH_PX, SOFT_ART_HEIGHT_PX)).build()
        },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

/** Whether art of this size loses real content to a portrait capsule's crop: noticeably wider than tall. Pure. */
internal fun isWideArt(width: Float, height: Float): Boolean =
    width > 0f && height > 0f && width > height * 1.1f

/** The soft copy's decode size and how much it is darkened under the whole art. */
private const val SOFT_ART_WIDTH_PX = 24
private const val SOFT_ART_HEIGHT_PX = 36
private const val SOFT_ART_DARKEN = 0.45f

/** The top-left badge: the game's [CapsuleStatus], on a chip that stays legible over any art. */
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
    CapsuleChip(text, ink, fill, Modifier.align(Alignment.TopStart))
}

/**
 * The bottom corners and the progress bar: what the game is and where it is
 * from ([badge], [kindBadgeOf]; no store's artwork, no letter), a small
 * generic glyph when it is shared with you or left your library
 * ([ownershipGlyph]; never words, docs/SPEC.md 7i "Badges"), how many copies
 * it stands for when more than one, and a thin bar while a download runs.
 */
@Composable
private fun BoxScope.CapsuleCorners(entry: LibraryEntry, download: StoreDownloads.Progress?, parts: Int, badge: KindBadge?) {
    if (badge != null) KindMark(badge, Modifier.align(Alignment.BottomStart))
    val glyph = ownershipGlyph(entry)
    val copies = if (parts > 1) "\u00d7$parts" else null
    listOfNotNull(glyph, copies).joinToString(" ").takeIf { it.isNotEmpty() }?.let {
        CapsuleChip(it, MenuTokens.OnSurface, MenuTokens.Scrim, Modifier.align(Alignment.BottomEnd))
    }
    // The shell's one progress bar, the same a download's row in the Downloads place draws.
    if (download != null) ShellProgressBar(download.fraction.coerceIn(0f, 1f), Modifier.align(Alignment.BottomStart).fillMaxWidth())
}

/**
 * The kind badge as a capsule chip (the look of [CapsuleChip]): the kind in
 * bold, then its store or system quieter, in small capitals.
 */
@Composable
private fun KindMark(badge: KindBadge, modifier: Modifier) {
    val muted = MenuTokens.OnSurfaceMuted
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(badge.kind.word.uppercase()) }
            badge.detail?.let { detail ->
                withStyle(SpanStyle(color = muted)) { append(" · ${detail.uppercase()}") }
            }
        },
        color = MenuTokens.OnSurface,
        style = TypeRole.eyebrow,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(Space.Sm)
            .background(MenuTokens.Scrim, Corners.Pill)
            .border(1.dp, MenuTokens.OnSurface.copy(alpha = 0.3f), Corners.Pill)
            .padding(horizontal = Space.Sm, vertical = Space.Hair),
    )
}

/**
 * The generic glyph a capsule carries for a store row that is not simply the
 * person's: shared with them by another account, or gone from their library.
 * Null for the rest. Pure, one look at the row's holding.
 */
internal fun ownershipGlyph(entry: LibraryEntry): String? = when (dev.droidtop.library.originLabel(entry, 1).mark) {
    dev.droidtop.library.OwnershipMark.SHARED -> SHARED_GLYPH
    dev.droidtop.library.OwnershipMark.LEFT -> LEFT_GLYPH
    null -> null
}

internal const val SHARED_GLYPH = "\u21c4"
internal const val LEFT_GLYPH = "\u2298"

/** A chip in a capsule's corner: the shell's one [StatusChip], kept clear of the corner. */
@Composable
private fun CapsuleChip(text: String, ink: Color, fill: Color, modifier: Modifier) {
    StatusChip(text, ink, fill, modifier.padding(Space.Sm))
}

/**
 * The one line that names the focused game and its facts, drawn once under
 * the capsules instead of on every card (docs/SPEC.md 7i): title, where it
 * came from in full ("Steam · Shared with you", "GOG · via Heroic", "Folder:
 * Games", [originLabel]), state, version, size. Pure, for the tests.
 */
internal fun focusLine(
    entry: LibraryEntry,
    play: PcPlayState?,
    parts: Int,
    roots: List<String> = emptyList(),
    via: String? = null,
): String = buildList {
    add(GameNaming.displayName(entry.title))
    add(dev.droidtop.library.originLabel(entry, 1, roots = roots, via = via).full)
    play?.takeIf { it.store != null }?.let { add(if (it.progress != null) "${it.verb} ${(it.progress * 100).toInt()}%" else it.verb) }
    entry.pcInfo?.installedVersion?.let { add(it) }
    // The version-management fact a shelf or the grid owes the focused game:
    // a version a source knows of that this device does not have (docs/SPEC.md 7g).
    entry.availableUpdate?.let { add(GameUpdates.line(it)) }
    entry.pcInfo?.sizeBytes?.takeIf { it > 0 }?.let { add(downloadSizeLabel(it)) }
    if (parts > 1) add("$parts copies")
}.joinToString(" · ")
