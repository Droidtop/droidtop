package dev.droidtop.shell.gamepad

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HideSystemBarsInThisDialog
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds

/** One place the left menu can take the user. */
internal data class LeftMenuEntry(val section: GamingSection, val label: String)

/** The left menu's rows for the sections this UI mode allows (Kiosk and Kid hide Settings). */
internal fun leftMenuEntries(sections: List<GamingSection>): List<LeftMenuEntry> =
    sections.map { LeftMenuEntry(it, it.displayName()) }

/**
 * The row the cursor starts on: where the user is now (focus memory). A
 * destination the mode hides falls to the top rather than to nothing.
 */
internal fun leftMenuStartIndex(entries: List<LeftMenuEntry>, current: GamingSection): Int =
    entries.indexOfFirst { it.section == current }.coerceAtLeast(0)

// How long the panel takes to slide in and the page behind it to dim.
// Local until the shared motion tokens land (Droidtop/tracker#256).
private const val LEFT_MENU_SLIDE_MS = 160

// How dark the page behind goes. A plain scrim, not a blur: a real blur
// of a themed canvas with video and animation on it is a per-frame cost
// the handheld should not pay for a menu that is open for seconds.
private const val LEFT_MENU_SCRIM_ALPHA = 0.55f

/**
 * The left menu: press Start anywhere in the Gaming shell (docs/SPEC.md
 * 7j, "Gaming controls", Droidtop/tracker#258). It is where things LIVE
 * and how you get to them -- the destinations -- while the Quick Menu on
 * the right (R2) is quick management only. A left-edge panel over a
 * dimmed page, opening on the destination the user is on.
 *
 * Its own layout, not the ES-DE theme's (no theme draws a menu), but
 * every colour and type style comes from the same tokens the theme feeds
 * the rest of the shell, so it follows a theme switch.
 *
 * Controller: Up/Down move, A goes there, B or Start closes, R2 swaps to
 * the Quick Menu. Touch: tap a row, tap the dimmed page to close. A
 * Compose [Dialog] on purpose, like the Quick Menu: its window owns input
 * while it is open, so the shell underneath needs no fencing.
 */
@Composable
internal fun LeftMenu(
    entries: List<LeftMenuEntry>,
    current: GamingSection,
    onSelect: (GamingSection) -> Unit,
    onOpenQuickMenu: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        GatePadInThisDialog()
        HideSystemBarsInThisDialog()
        val window = currentShellWindow()
        // A tap moves the cursor and sends the real press, so the one key
        // handler below is the only place that says what a row does.
        val press = rememberGamepadTouch()
        var focusIndex by remember { mutableIntStateOf(leftMenuStartIndex(entries, current)) }
        var heldStep by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()
        val focusRequester = remember { FocusRequester() }
        val enter = remember { Animatable(0f) }
        LaunchedEffect(Unit) { enter.animateTo(1f, tween(LEFT_MENU_SLIDE_MS)) }
        LaunchedEffect(Unit) { requestFocusWhenAttached(focusRequester, "Left menu") }
        LaunchedEffect(focusIndex, entries.size) {
            if (entries.isNotEmpty()) listState.keepInView(focusIndex.coerceIn(0, entries.size - 1), animate = !heldStep)
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The dimmed page: tapping it closes, as B does.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = enter.value }
                    .background(MenuTokens.Scrim.copy(alpha = LEFT_MENU_SCRIM_ALPHA))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
            )
            val panelWidth = if (window.portrait) maxWidth * 0.82f else (maxWidth * 0.34f).coerceIn(280.dp, 420.dp)
            Surface(
                color = MenuTokens.OverlaySurface,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(panelWidth)
                    .graphicsLayer { translationX = -(1f - enter.value) * size.width }
                    // Preview, like the Quick Menu's sheet: the panel takes
                    // its own presses before the focused column inside it.
                    .onPad(preview = true) { p ->
                        when (p.action) {
                            GamepadAction.UP, GamepadAction.DOWN -> {
                                heldStep = p.repeat
                                val next = menuStep(focusIndex, entries.size, if (p.action == GamepadAction.UP) -1 else 1)
                                if (next != focusIndex) EsDeNavigationSounds.play("scroll")
                                focusIndex = next
                            }
                            GamepadAction.A -> entries.getOrNull(focusIndex)?.let { onSelect(it.section) }
                            GamepadAction.B, GamepadAction.START -> onDismiss()
                            GamepadAction.R2 -> onOpenQuickMenu()
                            else -> return@onPad false
                        }
                        true
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .padding(Space.Lg),
                ) {
                    Text("Go to", color = MenuTokens.SectionLabel, style = TypeRole.sectionLabel)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(MenuTokens.RowSpacing),
                        contentPadding = PaddingValues(top = Space.Md, bottom = Space.Md),
                    ) {
                        itemsIndexed(entries, key = { _, entry -> entry.section.name }) { index, entry ->
                            MenuRow(
                                title = entry.label,
                                value = if (entry.section == current) "Here" else null,
                                selected = index == focusIndex,
                                onClick = {
                                    focusIndex = index
                                    press(GamepadAction.A)
                                },
                            )
                        }
                    }
                    HintRow(
                        bindings = listOf(
                            HintBinding(GamepadAction.A, "Open"),
                            HintBinding(GamepadAction.R2, "Quick Menu"),
                            HintBinding(GamepadAction.B, "Close"),
                        ),
                        background = Color.Transparent,
                    )
                }
            }
        }
    }
}
