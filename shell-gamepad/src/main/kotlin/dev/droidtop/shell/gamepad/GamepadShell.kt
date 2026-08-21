package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import kotlinx.coroutines.launch

/**
 * Full-screen, controller-first library shell — the "handheld style" UI:
 * Playnite fullscreen-mode / Android TV Leanback interaction patterns (see
 * README's design references). Never the default; a user opts into this the
 * same way they'd opt into :shell-desktop (see
 * dev.droidtop.app.ShellPreference in :app).
 *
 * D-pad navigation between cards is Compose's own default focus-key
 * handling — every focusable() node already responds to DPAD_UP/DOWN/LEFT/
 * RIGHT key events without custom code, because a physical D-pad/hat press
 * arrives as an ordinary Android KeyEvent. This file only needs to grab
 * initial focus and make each card visibly react when focused.
 *
 * Deliberately NOT implemented: analog left-stick-as-navigation. Unlike the
 * D-pad, a thumbstick arrives as raw MotionEvent axis data (AXIS_X/AXIS_Y),
 * which Compose does not translate into focus movement on its own — doing
 * that well (deadzone tuning, repeat-rate) needs a real controller to test
 * against, and none was available while writing this. Tracked as a known
 * gap, not a silent one.
 */
@Composable
fun GamepadShell(library: Library) {
    var entries by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    val scope = rememberCoroutineScope()
    val firstCardFocus = remember { FocusRequester() }

    LaunchedEffect(library) {
        entries = library.scanAll()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val currentEntries = entries
        when {
            currentEntries == null -> CircularProgressIndicator(color = Color.White)
            currentEntries.isEmpty() -> Text("Nothing in the library yet.", color = Color.White)
            else -> {
                LaunchedEffect(currentEntries) {
                    // Grabs initial D-pad focus once there's something to focus —
                    // otherwise the first D-pad press has nothing focused to move
                    // away from.
                    firstCardFocus.requestFocus()
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    itemsIndexed(currentEntries, key = { _, entry -> entry.id }) { index, entry ->
                        GameCard(
                            entry = entry,
                            modifier = if (index == 0) Modifier.focusRequester(firstCardFocus) else Modifier,
                            onLaunch = { scope.launch { library.launch(entry) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GameCard(entry: LibraryEntry, modifier: Modifier = Modifier, onLaunch: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .size(width = 220.dp, height = 260.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // DPAD_CENTER/Enter already trigger Modifier.clickable's default
            // key handling on a focused node, but a controller's face button
            // (A / cross) reports as a distinct keycode on most Android
            // gamepad mappings — handled explicitly here rather than relying
            // on clickable() to cover it.
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.ButtonA || event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    onLaunch()
                    true
                } else {
                    false
                }
            }
            .border(
                width = if (focused) 4.dp else 1.dp,
                color = if (focused) Color.White else Color.DarkGray,
                shape = RoundedCornerShape(12.dp),
            )
            .background(
                if (focused) Color(0xFF2A2A2A) else Color(0xFF1A1A1A),
                RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(entry.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(entry.kind.name, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
    }
}
