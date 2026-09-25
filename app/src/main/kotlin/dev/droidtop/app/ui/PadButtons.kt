package dev.droidtop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.currentShellWindow
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.input.padSelectable

/**
 * The button of droidtop's own full-screen flows outside the shells
 * (onboarding, the tutorial, the games screen): the pad's A presses it,
 * the shell's selection ring shows when it has the pad's focus, and it is
 * never smaller than a finger.
 *
 * [filled] is the one forward action of a screen, in the accent. Its ring
 * is drawn in the text colour, because an accent ring on an accent fill
 * is no ring at all; every other button carries the accent ring.
 *
 * Not a Material button: those answer Enter and DPAD_CENTER but never
 * BUTTON_A, show focus only as a faint overlay, and cannot hold focus in
 * touch mode ([padSelectable] says why that matters), so onboarding's
 * Welcome ignored the pad's A and its hint pills did nothing (rig,
 * dq-coordinator-24, finding 7; dq-onboard-01).
 */
@Composable
internal fun PadButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    color: Color = MenuTokens.Accent,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .heightIn(min = currentShellWindow().minTouchTarget)
            .padSelectable(onFocus = { focused = it }, onPress = onClick)
            .background(if (filled) MenuTokens.Accent else Color.Transparent, shape)
            .border(
                width = if (focused) MenuTokens.FocusRingWidth else 1.dp,
                color = when {
                    !focused -> Color.Transparent
                    filled -> MenuTokens.OnSurface
                    else -> MenuTokens.Accent
                },
                shape = shape,
            )
            .padding(horizontal = Space.Xl, vertical = Space.Sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (filled) MenuTokens.OverlaySurface else color, style = TypeRole.button)
    }
}
