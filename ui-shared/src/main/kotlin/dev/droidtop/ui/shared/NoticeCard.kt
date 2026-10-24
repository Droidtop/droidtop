package dev.droidtop.ui.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.droidtop.library.settings.Notice
import dev.droidtop.library.settings.NoticePlacement
import dev.droidtop.library.settings.NoticePress
import dev.droidtop.library.settings.NoticeTone

/**
 * The one notice card (docs/SPEC.md 7k, "Notices"; Droidtop/tracker#485):
 * a plate in the tone's colour with a [EDGE] bar of its ink on the leading
 * edge, the tone's glyph, the title (and "from <plugin>" for a plugin's
 * notice), the body, and either the action as a chip or a chevron when there
 * is more to read in Info. A row and a group notice have [ROW_CORNER]
 * corners; the page banner has [PAGE_CORNER]. The card never takes focus by
 * itself: the surface that draws it owns the selection and passes its frame
 * in through [modifier].
 *
 * A screen reader hears it as one node, tone first ([Notice.spoken]), polite
 * for a group notice or a banner, with the custom actions Info and the
 * action's label. Fades in over [FADE_MS] with a [RISE] rise, scaled by the
 * system animator scale (none at 0); nothing pulses or blinks.
 */
@Composable
fun NoticeCard(
    notice: Notice,
    placement: NoticePlacement,
    modifier: Modifier = Modifier,
    onAction: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
) {
    val palette = LocalNoticePalette.current
    val tone = notice.tone
    val ink = palette.ink(tone)
    val plate = palette.plate(tone)
    val on = palette.on(tone)
    val shape = RoundedCornerShape(if (placement == NoticePlacement.Page) PAGE_CORNER else ROW_CORNER)
    val density = LocalDensity.current
    // Motion's switch: Android's Remove animations makes it a snap (Droidtop/tracker#643). Compose scales the tween itself.
    val appear = remember { Animatable(if (Motion.enabled) 0f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, Motion.tw(FADE_MS)) }
    val press = notice.aPress()
    val onPress: (() -> Unit)? = when (press) {
        NoticePress.ACTION -> onAction
        NoticePress.INFO -> onInfo
        NoticePress.NONE -> null
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * with(density) { RISE.toPx() }
            }
            .fillMaxWidth()
            .heightIn(min = if (notice.body != null) MIN_HEIGHT_WITH_BODY else MIN_HEIGHT)
            .clip(shape)
            .background(plate)
            .drawBehind { drawRect(ink, size = Size(EDGE.toPx(), size.height)) }
            .then(if (onPress != null) Modifier.clickable(onClick = onPress) else Modifier)
            .clearAndSetSemantics {
                contentDescription = notice.spoken()
                if (placement != NoticePlacement.Row) liveRegion = LiveRegionMode.Polite
                val actions = buildList {
                    if (onInfo != null && !notice.details.isNullOrBlank()) {
                        add(CustomAccessibilityAction(Notice.INFO_ACTION) { onInfo(); true })
                    }
                    val action = notice.action
                    if (action != null && onAction != null) add(CustomAccessibilityAction(action.label) { onAction(); true })
                }
                if (actions.isNotEmpty()) customActions = actions
                if (onPress != null) onClick { onPress(); true }
            }
            .padding(start = EDGE + 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Icon(tone.glyph(), contentDescription = null, tint = ink, modifier = Modifier.size(GLYPH))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(notice.title, color = ink, style = TITLE, modifier = Modifier.weight(1f, fill = false))
                notice.source?.let { source ->
                    Spacer(Modifier.width(8.dp))
                    Text("from $source", color = palette.muted, style = FROM, maxLines = 1)
                }
            }
            notice.body?.let { Text(it, color = on, style = BODY) }
        }
        val action = notice.action
        when {
            action != null -> {
                Spacer(Modifier.width(12.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .heightIn(min = CHIP_HEIGHT)
                        .clip(RoundedCornerShape(CHIP_HEIGHT / 2))
                        .border(1.dp, ink, RoundedCornerShape(CHIP_HEIGHT / 2))
                        .then(if (onAction != null) Modifier.clickable(onClick = onAction) else Modifier)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(action.label, color = ink, style = CHIP, maxLines = 1)
                }
            }
            !notice.details.isNullOrBlank() -> {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = ink, modifier = Modifier.size(GLYPH))
            }
        }
    }
}

/** The tone's glyph: circle i, triangle !, octagon !. */
fun NoticeTone.glyph(): ImageVector = when (this) {
    NoticeTone.Info -> Icons.Rounded.Info
    NoticeTone.Caution -> Icons.Rounded.Warning
    NoticeTone.Danger -> Icons.Rounded.Report
}

private val ROW_CORNER = 8.dp
private val PAGE_CORNER = 12.dp
private val EDGE = 4.dp
private val GLYPH = 20.dp
private val MIN_HEIGHT = 48.dp
private val MIN_HEIGHT_WITH_BODY = 56.dp
private val CHIP_HEIGHT = 32.dp
private val RISE = 4.dp
private const val FADE_MS = 150
private val TITLE = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
private val BODY = TextStyle(fontSize = 15.sp, lineHeight = 19.sp)
private val FROM = TextStyle(fontSize = 14.sp, lineHeight = 18.sp)
private val CHIP = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
