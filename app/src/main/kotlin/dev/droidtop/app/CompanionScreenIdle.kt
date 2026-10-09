package dev.droidtop.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.droidtop.library.settings.CompanionPrefs
import dev.droidtop.library.settings.ControlAccess
import dev.droidtop.library.settings.UiModeRefresh
import dev.droidtop.runtime.CompanionIdle
import dev.droidtop.runtime.DisplayControls
import dev.droidtop.runtime.systemstatus.NotificationsStore
import kotlinx.coroutines.delay

/** "Turn this screen off" on the companion's own Display card: asks the idle layer to go off now. */
internal object CompanionScreenPower {
    val offRequests = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun off() {
        offRequests.value++
    }
}

/** The newest message notification's post time, or 0: a later one is a new message (it wakes a sleeping companion). Pure. */
internal fun newestMessage(items: List<NotificationsStore.Item>): Long = items.filter { it.message }.maxOfOrNull { it.postTime } ?: 0L

/**
 * The companion screen's idle and off (docs/SPEC.md "The companion's tabs", Companion screen off and idle; slice C14):
 * every touch is noticed before the tab sees it (never consumed, so a tap on a dimmed screen brightens it and does what
 * was tapped); with no touch the screen dims, then goes off ([CompanionIdle]); [exempt] holds it on. The window's own
 * brightness follows ([DisplayControls.windowLevel]) where the host is an Activity, and a black layer does the rest.
 * Off is one large element for TalkBack; it wakes by the chosen tap, and a new message brings it back to dimmed. On
 * Input it stays a dark trackpad that touches still reach ([input]); the Tabs handle or button wakes it there.
 */
@Composable
internal fun CompanionIdleLayer(exempt: Boolean, input: Boolean, wakeRequest: Boolean, content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val settings by CompanionPrefs.settings.collectAsState()
    val uiMode by UiModeRefresh.mode.collectAsState()
    val level by DisplayControls.companionLevel.collectAsState()
    var lastTouch by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var state by remember { mutableStateOf(CompanionIdle.State.ON) }
    val recommended = remember { recommendedTimeout(context) }
    val keepOnPanels by CompanionKeepOn.collectAsState()
    val keepOn = exempt || keepOnPanels > 0
    fun touched() {
        lastTouch = SystemClock.uptimeMillis()
        if (state != CompanionIdle.State.ON) state = CompanionIdle.State.ON
    }
    // The timer: wakes up only when the state is due to change, never polls.
    LaunchedEffect(lastTouch, keepOn, settings.idleTimeout) {
        while (true) {
            val since = SystemClock.uptimeMillis() - lastTouch
            if (CompanionIdle.warnNow(since, settings.idleTimeout, keepOn, recommended)) {
                view.announceForAccessibility("Companion screen will turn off soon. Touch to keep it on.")
            }
            state = CompanionIdle.state(since, settings.idleTimeout, keepOn, recommended)
            val next = CompanionIdle.nextChangeInMs(since, settings.idleTimeout, keepOn, recommended) ?: break
            delay(next.coerceAtLeast(250L))
        }
    }
    // A new message notification brings an off or dimmed screen back to dimmed (not in Kid or Kiosk).
    val notifications by NotificationsStore.items.collectAsState()
    val newest = newestMessage(notifications)
    var seenMessage by remember { mutableLongStateOf(newest) }
    LaunchedEffect(newest) {
        if (newest > seenMessage) {
            CompanionIdle.afterMessage(state, ControlAccess.rules(uiMode).alwaysAsk, recommended)?.let { since ->
                lastTouch = SystemClock.uptimeMillis() - since
                state = CompanionIdle.State.DIM
            }
        }
        seenMessage = newest
    }
    // "Turn this screen off": off now, as if untouched past the off time.
    val offs by CompanionScreenPower.offRequests.collectAsState()
    var seenOffs by remember { mutableStateOf(offs) }
    LaunchedEffect(offs) {
        if (offs != seenOffs) {
            lastTouch = SystemClock.uptimeMillis() - CompanionIdle.timeouts(recommended).second
            state = CompanionIdle.State.OFF
        }
        seenOffs = offs
    }
    // The Tabs handle or button on Input, and the Quick Menu's Companion screen row, wake the screen.
    LaunchedEffect(wakeRequest) { if (wakeRequest) touched() }
    val wakes by CompanionPrefs.wakeRequests.collectAsState()
    var seenWakes by remember { mutableStateOf(wakes) }
    LaunchedEffect(wakes) {
        if (wakes != seenWakes) touched()
        seenWakes = wakes
    }
    // The window's own brightness, where the host is an Activity (a Presentation's window is not ours to set).
    val window = remember(view) { findActivity(view.context)?.window }
    DisposableEffect(window, state, level) {
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = DisplayControls.windowLevel(state, level) } }
        onDispose {}
    }
    DisposableEffect(window) {
        onDispose { window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = -1f } } }
    }
    var offHintSeen by remember { mutableStateOf(settings.offHintSeen) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        if (state != CompanionIdle.State.OFF) touched()
                    }
                }
            },
    ) {
        content()
        when (state) {
            CompanionIdle.State.ON -> Unit
            CompanionIdle.State.DIM -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            CompanionIdle.State.OFF -> if (input) {
                // A dark trackpad: the touches still reach it, and only the Tabs handle or button wakes it.
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)))
            } else {
                OffScreen(
                    wake = CompanionIdle.Wake.of(settings.wake),
                    showHint = !offHintSeen,
                    onWake = {
                        touched()
                        if (!offHintSeen) {
                            offHintSeen = true
                            CompanionPrefs.setOffHintSeen(context.applicationContext)
                        }
                    },
                )
            }
        }
    }
}

/** The off screen: black, one element for TalkBack, woken by the chosen tap; the first time, a line says how. */
@Composable
private fun OffScreen(wake: CompanionIdle.Wake, showHint: Boolean, onWake: () -> Unit) {
    var taps by remember { mutableStateOf(0) }
    var firstTapAt by remember { mutableLongStateOf(0L) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(wake) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.all { !it.pressed }) {
                            val now = SystemClock.uptimeMillis()
                            taps = if (now - firstTapAt <= DOUBLE_TAP_MS) taps + 1 else 1
                            if (taps == 1) firstTapAt = now
                            if (CompanionIdle.wakes(wake, taps)) {
                                taps = 0
                                onWake()
                            }
                        }
                    }
                }
            }
            .semantics {
                contentDescription = "Companion screen off, activate to turn on"
                onClick { onWake(); true }
            },
    ) {
        if (showHint) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(24.dp)) {
                Text(
                    when (wake) {
                        CompanionIdle.Wake.DOUBLE_TAP -> "Double tap to turn this screen on"
                        CompanionIdle.Wake.SINGLE_TAP -> "Tap to turn this screen on"
                        CompanionIdle.Wake.NONE -> "Turn this screen on from the Quick Menu: Companion screen"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
    }
}

private const val DOUBLE_TAP_MS = 400L

/** What Android's accessibility settings ask an interface to wait before acting on its own (Android 10 and later), else 0. */
private fun recommendedTimeout(context: Context): Long {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0L
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return 0L
    return manager.getRecommendedTimeoutMillis(CompanionIdle.DIM_AFTER_MS.toInt(), AccessibilityManager.FLAG_CONTENT_CONTROLS).toLong()
}

private fun findActivity(context: Context): Activity? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
