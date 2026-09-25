package dev.droidtop.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.droidtop.shell.gamepad.Measure
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.currentShellWindow

/**
 * The ONE choice component (docs/SPEC.md 7b, "The one choice
 * component"): every question with mutually exclusive answers is a run of
 * these. Full width, at least the window's own minimum touch target, an
 * optional leading icon, a title, one supporting line, and a real
 * selected state — the shell's own menu row anatomy rather than a third
 * one invented here.
 *
 * What it replaces: three equal answers rendered as two filled buttons
 * and a text link, with no selection semantics at all.
 */
@Composable
internal fun SelectableRow(
    title: String,
    supporting: String? = null,
    selected: Boolean = false,
    icon: android.graphics.drawable.Drawable? = null,
    // A leading slot the caller draws itself, for a choice whose icon is
    // not a drawable: onboarding's Appearance step puts a live render of
    // the theme here.
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    // Null for a row that is information with its own action beside it (a
    // games folder and its Remove), rather than a choice to be made.
    onClick: (() -> Unit)? = null,
) {
    val window = currentShellWindow()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = window.minTouchTarget + Space.Sm)
            .background(
                if (selected) MenuTokens.SurfaceSelected else MenuTokens.Surface,
                MenuTokens.RowShape,
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.Lg, vertical = Space.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.Md),
    ) {
        leading?.invoke()
        icon?.let { drawable ->
            val bitmap = remember(drawable) {
                runCatching { dev.droidtop.library.DrawableBitmaps.render(drawable, 96, 96).asImageBitmap() }.getOrNull()
            }
            bitmap?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(Measure.rowIcon)) }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Hair)) {
            Text(
                title,
                color = if (selected) MenuTokens.OnSurface else MenuTokens.OnSurface,
                style = TypeRole.rowTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            supporting?.let {
                Text(it, color = MenuTokens.OnSurfaceMuted, style = TypeRole.supporting)
            }
        }
        // The chosen answer carries the check the shell's chips use for
        // "this is on" (ShellChip), not a word in a different blue (UI
        // pass 2026-09-24, L11); the accent ring stays for focus alone.
        if (selected && trailing == null) {
            Text(
                "\u2713",
                color = MenuTokens.Accent,
                style = TypeRole.rowTitle,
                modifier = Modifier.semantics { contentDescription = "Chosen" },
            )
        }
        trailing?.invoke()
    }
}
