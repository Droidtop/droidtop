package dev.droidtop.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.droidtop.library.settings.UiMode
import dev.droidtop.library.settings.UiModePasskey
import dev.droidtop.library.settings.UiModePrefs
import dev.droidtop.library.settings.UiModeRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Leaving Kid or Kiosk when a passkey is set (docs/SPEC.md "UI modes and ControlAccess", Droidtop/tracker#414): a
 * keypad over whatever was on screen, worked by touch or by pad. The D-pad moves between keys, A presses one and B
 * cancels; a wrong passkey says so and clears, the right one leaves and closes. The check is
 * [UiModePasskey.matches]; nothing here stores or compares digits itself.
 */
class UiModePasskeyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true, gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                PasskeyEntry(onDone = { finish() })
            }
        }
    }

    /** The pad's A presses the focused key (as the D-pad's centre does) and B cancels. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> super.dispatchKeyEvent(
            KeyEvent(
                event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount,
                event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
            ),
        )
        KeyEvent.KEYCODE_BUTTON_B -> {
            if (event.action == KeyEvent.ACTION_UP) finish()
            true
        }
        else -> super.dispatchKeyEvent(event)
    }
}

@Composable
private fun PasskeyEntry(onDone: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    // Read once off the main thread; null while loading and when none is set.
    val loaded by produceState<Pair<UiMode, UiModePasskey.Stored?>?>(null) {
        value = withContext(Dispatchers.IO) { UiModePrefs.get(context) to UiModePasskey.stored(context) }
    }
    var entered by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(loaded != null) { if (loaded != null) runCatching { first.requestFocus() } }

    fun submit() {
        val (_, stored) = loaded ?: return
        if (UiModePasskey.matches(stored, entered)) {
            UiModeRefresh.set(context, UiMode.FULL)
            onDone()
        } else {
            entered = ""
            message = "Wrong passkey"
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .widthIn(max = 360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .padding(20.dp),
        ) {
            val modeLabel = when (loaded?.first) {
                UiMode.KID -> "Kid"
                UiMode.KIOSK -> "Kiosk"
                else -> "this mode"
            }
            Text("Passkey to leave $modeLabel", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Text(
                if (entered.isEmpty()) "–" else "●".repeat(entered.length),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface,
                modifier = Modifier.semantics {
                    contentDescription = if (entered.isEmpty()) "No digits entered" else "${entered.length} digits entered"
                },
            )
            Text(
                message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("Clear", "0", "OK"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { key ->
                        PasskeyKey(
                            label = key,
                            modifier = if (key == "1") Modifier.focusRequester(first) else Modifier,
                        ) {
                            message = null
                            when (key) {
                                "Clear" -> entered = ""
                                "OK" -> submit()
                                else -> if (entered.length < UiModePasskey.LENGTHS.last) entered += key
                            }
                        }
                    }
                }
            }
            PasskeyKey(label = "Cancel", wide = true, onClick = onDone)
        }
    }
}

/** One key: a 64dp target, focusable for the pad, with a visible ring when focused. */
@Composable
private fun PasskeyKey(label: String, modifier: Modifier = Modifier, wide: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(if (wide) Modifier.heightIn(min = 56.dp).widthIn(min = 216.dp) else Modifier.size(64.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceVariant)
            .border(if (focused) 3.dp else 0.dp, if (focused) colors.primary else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
    }
}
