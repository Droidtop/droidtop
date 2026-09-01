package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp

/**
 * The Handheld shell's shared menu language, in one place.
 *
 * Every menu-ish surface in this shell (settings catalogs, the gamelist
 * options overlay, the Quick Menu, the metadata and collection editors,
 * the display chooser) used to pick its own greys, its own row padding
 * and its own idea of what "selected" looks like -- 43 hand-written
 * colors across five files, which is exactly why the menus read as
 * unfinished next to the themed views. These are the tokens and the row
 * anatomy all of them share now.
 *
 * The rules the anatomy encodes, so surfaces stop re-deciding them:
 * - Every row sits on a faint card. SELECTION BRIGHTENS THAT CARD; it
 *   never materialises a slab under text that was previously floating.
 * - Titles are one line, subtitles at most two, so rows keep a uniform
 *   height and a list scans as a column instead of a ragged stack.
 * - A value and a chevron are different things: a chevron means "this
 *   opens", a value means "this is set to". Nothing renders a chevron
 *   in a value's place.
 * - Unset reads as a dim placeholder, never as loud as a real value.
 */
object MenuTokens {
    val Surface = Color(0x0DFFFFFF)
    val SurfaceSelected = Color(0x2BFFFFFF)
    val OverlaySurface = Color(0xFF1C2027)
    val OnSurface = Color.White
    val OnSurfaceMuted = Color(0xFF8A93A1)
    val Value = Color(0xFFAEB7C4)
    val Placeholder = Color(0xFF6B7480)
    val Accent = Color(0xFF8AB4FF)
    val Danger = Color(0xFFFFB4AB)
    /** "This is on / included" -- the one affirmative in the menus. */
    val Affirmative = Color(0xFF7FE08A)
    val SectionLabel = Color(0xFF7D8794)

    val RowShape = RoundedCornerShape(10.dp)
    val OverlayShape = RoundedCornerShape(14.dp)
    val RowSpacing = 6.dp
    val ScreenPadding = 48.dp
}

/** List padding shared by every full-screen menu list. */
internal val MenuListPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp)

/** A screen-level menu header: name first, explanation second, both quiet. */
@Composable
internal fun MenuHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = MenuTokens.ScreenPadding).padding(top = 18.dp, bottom = 2.dp)) {
        Text(
            title,
            color = MenuTokens.OnSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        subtitle?.let {
            Text(
                it,
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A group label: a real section marker, not another grey title competing with the rows. */
@Composable
internal fun MenuSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = MenuTokens.SectionLabel,
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = TextUnit(1.2f, TextUnitType.Sp),
        modifier = modifier.padding(top = 18.dp, bottom = 6.dp, start = 4.dp),
    )
}

/**
 * The one row anatomy. [value] is what the setting is set to; [chevron]
 * says the row opens something; [accent] paints a leading rail for rows
 * that carry an identity color (a system's own, say).
 */
@Composable
internal fun MenuRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    placeholder: Boolean = false,
    adjustable: Boolean = false,
    chevron: Boolean = false,
    selected: Boolean = false,
    danger: Boolean = false,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(MenuTokens.RowShape)
            .background(if (selected) MenuTokens.SurfaceSelected else MenuTokens.Surface)
            // Touch works on every row, always -- the shell is
            // gamepad-first, never gamepad-only.
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (accent != null) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (danger) MenuTokens.Danger else MenuTokens.OnSurface,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    color = MenuTokens.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(16.dp))
            Text(
                if (selected && adjustable) "‹ $value ›" else value,
                color = when {
                    placeholder -> MenuTokens.Placeholder
                    selected -> MenuTokens.OnSurface
                    else -> MenuTokens.Value
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Text("›", color = MenuTokens.Placeholder, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The button-hint line every menu ends with, so controls are never a guess. */
@Composable
internal fun MenuHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = MenuTokens.OnSurfaceMuted,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.padding(top = 10.dp),
    )
}

/**
 * A modal menu panel with focus handled ONCE, here.
 *
 * A Compose Dialog silently drops key events unless something inside it
 * actually holds focus -- a real bug that shipped in this shell before
 * this existed (the gamelist options overlay ignored every D-pad press
 * on a real device). Surfaces built on this cannot reintroduce it.
 */
@Composable
internal fun MenuPanel(
    modifier: Modifier = Modifier,
    focusLabel: String = "Menu",
    onKey: (KeyEvent) -> Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, focusLabel) }
    Column(
        modifier = modifier
            .clip(MenuTokens.OverlayShape)
            .background(MenuTokens.OverlaySurface)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent(onKey)
            // A panel whose content can outgrow the screen must scroll:
            // the jump-to-letter list reaches 27 rows on a library that
            // spans the alphabet, which is taller than the display.
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(MenuTokens.RowSpacing),
        content = content,
    )
}

/** Shared full-screen menu ground, so a menu never shows the themed view bleeding through. */
@Composable
internal fun MenuScreen(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
}
