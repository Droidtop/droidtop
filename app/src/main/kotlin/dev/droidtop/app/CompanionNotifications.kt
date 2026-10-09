package dev.droidtop.app

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.droidtop.library.settings.CompanionPrefs
import dev.droidtop.runtime.systemstatus.NotificationRows
import dev.droidtop.runtime.systemstatus.NotificationsStore
import dev.droidtop.runtime.systemstatus.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Notifications on the companion (docs/SPEC.md "The companion's tabs", Droidtop/tracker#414): one row model for
 * Home's group and Social's messages. Each row offers Reply where the app takes a typed reply (droidtop's own
 * keyboard in the companion window, the app's reply action filled directly: [NotificationsStore.Controller.reply]),
 * Open (a button and a long-press; the app opens on the companion's screen), and Dismiss (a button and a swipe).
 * Whether a row shows what a message says follows "Show message text" ([NotificationRows.showsText]); a hidden one
 * shows the app and sender, and a tap shows the text.
 */
@Composable
internal fun CompanionNotificationsSection(open: Boolean, onToggle: () -> Unit) {
    val items by NotificationsStore.items.collectAsState()
    if (items.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val newest = items.first()
        CompanionSectionHeader(
            label = "Notifications",
            open = open,
            summary = if (open) items.size.toString() else items.size.toString() + "   " + listOfNotNull(newest.appLabel, newest.title).joinToString(": "),
            onToggle = onToggle,
        )
        if (open) {
            if (items.any { it.clearable }) {
                Row { CompanionPill("Clear all") { NotificationsStore.controller?.clearAll() } }
            }
            CompanionNotificationList(items.take(MAX_ROWS))
            if (items.size > MAX_ROWS) CompanionNote("+${items.size - MAX_ROWS} more")
        }
    }
}

private const val MAX_ROWS = 12

/** The rows themselves, for Home's group and Social's messages alike. */
@Composable
internal fun CompanionNotificationList(items: List<NotificationsStore.Item>) {
    val settings by CompanionPrefs.settings.collectAsState()
    val locked = rememberLocked()
    val lockScreenPrivate = rememberLockScreenShowsPrivate()
    val showText = NotificationRows.showsText(NotificationRows.MessageText.of(settings.messageText), locked, lockScreenPrivate)
    var replying by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            androidx.compose.runtime.key(item.key) {
                CompanionNotificationRow(item, showText, replying == item.key) { replying = if (replying == item.key) null else item.key }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompanionNotificationRow(item: NotificationsStore.Item, showText: Boolean, replying: Boolean, onReply: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val colors = MaterialTheme.colorScheme
    val actions = NotificationRows.actions(item)
    var revealed by remember(item.key) { mutableStateOf(false) }
    var drag by remember(item.key) { mutableFloatStateOf(0f) }
    val open = { openOnCompanion(context, item, view.display?.displayId) }
    val dismiss = { NotificationsStore.controller?.dismiss(item.key); Unit }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(drag.roundToInt(), 0) }
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .let { base ->
                if (NotificationRows.Action.DISMISS !in actions) base else base.pointerInput(item.key) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (abs(drag) > size.width * 0.4f) dismiss() else drag = 0f },
                        onDragCancel = { drag = 0f },
                    ) { _, amount -> drag += amount }
                }
            }
            .combinedClickable(
                onClick = { revealed = !revealed },
                onLongClick = if (NotificationRows.Action.OPEN in actions) open else null,
                onLongClickLabel = "Open",
            )
            .semantics {
                customActions = buildList {
                    if (NotificationRows.Action.OPEN in actions) add(CustomAccessibilityAction("Open") { open(); true })
                    if (NotificationRows.Action.DISMISS in actions) add(CustomAccessibilityAction("Dismiss") { dismiss(); true })
                }
            }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            listOfNotNull(item.appLabel, item.title).joinToString(": "),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.text.isNotBlank()) {
            if (showText || revealed) {
                Text(item.text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
            } else {
                Text("Tap to show", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (NotificationRows.Action.REPLY in actions) CompanionPill(if (replying) "Close reply" else "Reply", selected = replying, onClick = onReply)
            if (NotificationRows.Action.OPEN in actions) CompanionPill("Open", onClick = open)
            if (NotificationRows.Action.DISMISS in actions) CompanionPill("Dismiss", onClick = dismiss)
        }
        if (replying) {
            CompanionComposer(key = item.key, startWithKeyboard = true, onSent = onReply) { text ->
                NotificationsStore.controller?.reply(item.key, text)
                    ?: Result.failure(dev.droidtop.library.UserFacingException("Notification access is off"))
            }
        }
    }
}

/**
 * Opens a notification's own tap action on the companion's screen: an explicit display, and (Android 14) the
 * permission to start it from droidtop's visible window.
 */
private fun openOnCompanion(context: Context, item: NotificationsStore.Item, displayId: Int?) {
    val intent = item.contentIntent ?: return
    val options = ActivityOptions.makeBasic()
    if (displayId != null) options.setLaunchDisplayId(displayId)
    if (Build.VERSION.SDK_INT >= 34) {
        options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
    }
    runCatching { intent.send(context, 0, null, null, null, null, options.toBundle()) }
}

/** Whether the keyguard is up, followed by screen-off and unlock broadcasts while composed. */
@Composable
private fun rememberLocked(): Boolean {
    val context = LocalContext.current
    val keyguard = remember { context.getSystemService(KeyguardManager::class.java) }
    var locked by remember { mutableStateOf(keyguard?.isKeyguardLocked == true) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                locked = keyguard?.isKeyguardLocked == true || intent.action == Intent.ACTION_SCREEN_OFF
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return locked
}

/** Android's "show sensitive content" on the lock screen; hidden when it cannot be read. Read off the main thread. */
@Composable
private fun rememberLockScreenShowsPrivate(): Boolean {
    val context = LocalContext.current
    val value by produceState(false) {
        value = withContext(Dispatchers.IO) {
            runCatching { Settings.Secure.getInt(context.contentResolver, "lock_screen_allow_private_notifications", 0) == 1 }
                .getOrDefault(false)
        }
    }
    return value
}

/**
 * Now playing (docs/SPEC.md "The companion's tabs"): the newest media session's title, artist and app, with
 * previous, play or pause, and next. Sessions are read through droidtop's notification access
 * (`MediaSessionManager.getActiveSessions`), followed by its change listener and the controller's callback while
 * the card is on screen; without access, or with nothing playing, there is no card.
 */
@Composable
internal fun CompanionNowPlayingCard() {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var state by remember { mutableStateOf<NowPlaying?>(null) }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, DroidtopNotificationListener::class.java)
        var watched: MediaController? = null
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(playbackState: PlaybackState?) { state = nowPlaying(context, watched) }
            override fun onMetadataChanged(metadata: MediaMetadata?) { state = nowPlaying(context, watched) }
            override fun onSessionDestroyed() { state = null }
        }
        fun follow(sessions: List<MediaController>?) {
            watched?.unregisterCallback(callback)
            watched = sessions?.firstOrNull()
            watched?.registerCallback(callback)
            controller = watched
            state = nowPlaying(context, watched)
        }
        val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { follow(it) }
        runCatching {
            manager?.addOnActiveSessionsChangedListener(sessionsListener, listener)
            follow(manager?.getActiveSessions(listener))
        }
        onDispose {
            runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
            watched?.unregisterCallback(callback)
        }
    }
    val card = state ?: return
    val controls = controller?.transportControls
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Now playing · ${card.app}", style = MaterialTheme.typography.labelMedium, color = colors.primary)
        Text(card.title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        card.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(min = 48.dp)) {
            if (card.canPrevious) CompanionPill("Previous") { controls?.skipToPrevious() }
            if (card.canPlayPause) {
                CompanionPill(if (card.playing) "Pause" else "Play", selected = true) {
                    if (card.playing) controls?.pause() else controls?.play()
                }
            }
            if (card.canNext) CompanionPill("Next") { controls?.skipToNext() }
        }
    }
}

private fun nowPlaying(context: Context, controller: MediaController?): NowPlaying? {
    controller ?: return null
    val metadata = controller.metadata
    val app = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(controller.packageName, 0)).toString()
    }.getOrDefault(controller.packageName)
    return NowPlaying.from(
        title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
        artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
        app = app,
        state = controller.playbackState?.state,
        actions = controller.playbackState?.actions ?: 0L,
    )
}
