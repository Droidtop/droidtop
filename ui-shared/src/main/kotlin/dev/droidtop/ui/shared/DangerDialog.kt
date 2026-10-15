package dev.droidtop.ui.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.droidtop.library.settings.NoticeDialogs
import dev.droidtop.library.settings.NoticeTone

/**
 * The danger dialog's card, and the plain confirm's (docs/SPEC.md 7k, "Notices"; Steam's shape): a centred dark
 * card, min(480 dp, 90% of the window) wide, with the octagon and the title, the outcome, the recommendation in
 * the danger ink, the plugins it is about, a Details row, and two buttons side by side at the bottom: Cancel on
 * the left, the risky one on the right in the danger fill (a plain confirm's proceed button has no fill).
 *
 * Drawing only: the host owns the window, the scrim, the pad and which button the cursor is on ([focused]: 0 is
 * Cancel, 1 the proceed button, 2 the Details row), so each shell keeps its own input pipeline. Touch works here.
 * The host shows the selection with [focusModifier], its own ring.
 */
@Composable
fun ConfirmDialogCard(
    request: NoticeDialogs.Request,
    focused: Int,
    detailsOpen: Boolean,
    surface: Color,
    onSurface: Color,
    neutralButton: Color,
    onCancel: () -> Unit,
    onProceed: () -> Unit,
    onToggleDetails: () -> Unit,
    focusModifier: @Composable (Boolean) -> Modifier = { Modifier },
) {
    val palette = LocalNoticePalette.current
    val notice = request.notice
    val tone = if (request.danger) NoticeTone.Danger else notice.tone
    val ink = palette.ink(tone)
    val scale = animatorScale(androidx.compose.ui.platform.LocalContext.current)
    val appear = remember { Animatable(if (scale == 0f) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (scale > 0f) appear.animateTo(1f, tween((OPEN_MS * scale).toInt())) else appear.snapTo(1f)
    }
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val width = minOf(MAX_WIDTH, maxWidth * 0.9f)
        Column(
            modifier = Modifier
                .graphicsLayer {
                    alpha = appear.value
                    scaleX = 0.96f + 0.04f * appear.value
                    scaleY = scaleX
                }
                .widthIn(max = width)
                .heightIn(max = maxHeight * 0.9f)
                .clip(RoundedCornerShape(8.dp))
                .background(surface)
                .semantics { paneTitle = notice.title }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(tone.glyph(), contentDescription = tone.name, tint = ink, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(notice.title, color = onSurface, style = TITLE)
                }
                notice.body?.let { Text(it, color = onSurface, style = BODY) }
                notice.recommendation?.let { Text(it, color = ink, style = BODY.copy(fontWeight = FontWeight.Bold)) }
                if (request.items.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        request.items.forEach { Text("• $it", color = onSurface, style = BODY) }
                    }
                }
                val details = notice.details
                if (!details.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(focusModifier(focused == 2))
                            .clickable(onClick = onToggleDetails)
                            .semantics { role = Role.Button }
                            .padding(vertical = 8.dp),
                    ) {
                        Text("Details", color = onSurface, style = BODY, modifier = Modifier.weight(1f))
                        Icon(if (detailsOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = onSurface)
                    }
                    if (detailsOpen) Text(details, color = onSurface, style = DETAILS)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                DialogButton(request.cancelLabel, neutralButton, onSurface, null, focusModifier(focused == 0), Modifier.weight(1f), onCancel)
                if (request.danger) {
                    DialogButton(request.proceedLabel, palette.dangerFill, palette.onDangerFill, null, focusModifier(focused == 1), Modifier.weight(1f), onProceed)
                } else {
                    DialogButton(request.proceedLabel, neutralButton, onSurface, ink, focusModifier(focused == 1), Modifier.weight(1f), onProceed)
                }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, fill: Color, text: Color, outline: Color?, ring: Modifier, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 48.dp)
            .then(ring)
            .clip(shape)
            .background(fill)
            .then(if (outline != null) Modifier.border(1.dp, outline, shape) else Modifier)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, color = text, style = BUTTON, maxLines = 1)
    }
}

/**
 * A flash's card (docs/SPEC.md 7k, "Notices"): at most 320 dp wide, the tone's glyph and up to [maxLines] lines
 * of 14 sp text, the plugin's name leading a plugin's flash. Nothing in it can be pressed.
 */
@Composable
fun FlashCard(text: String, tone: NoticeTone, source: String?, surface: Color, onSurface: Color, maxLines: Int, modifier: Modifier = Modifier) {
    val palette = LocalNoticePalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(surface)
            .border(1.dp, palette.ink(tone).copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(tone.glyph(), contentDescription = null, tint = palette.ink(tone), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (source != null) "$source: $text" else text,
            color = onSurface,
            style = FLASH,
            maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

private val MAX_WIDTH = 480.dp
private const val OPEN_MS = 200
private val TITLE = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
private val BODY = TextStyle(fontSize = 16.sp, lineHeight = 20.sp)
private val DETAILS = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
private val BUTTON = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
private val FLASH = TextStyle(fontSize = 14.sp, lineHeight = 18.sp)
