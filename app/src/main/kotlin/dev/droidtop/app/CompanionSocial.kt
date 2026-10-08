package dev.droidtop.app

import android.content.Context
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.droidtop.app.settings.SocialTime
import dev.droidtop.shell.gamepad.DroidtopKeyboard
import org.pocketworkstation.pckeyboard.KeyboardSink
import dev.droidtop.library.social.SocialContact
import dev.droidtop.library.social.SocialHub
import dev.droidtop.library.social.SocialMessage
import dev.droidtop.library.social.SocialOrder
import dev.droidtop.library.social.SocialPresence
import dev.droidtop.library.userFacingErrorMessage
import dev.droidtop.runtime.systemstatus.NotificationsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The companion's Social tab (docs/SPEC.md "Social" and "The companion's tabs", Droidtop/tracker#327): the
 * same merged friends and conversations as the Social place, by touch. A conversation has a draft field and
 * droidtop's own keyboard drawn inside the companion window, typing into the field directly: no IME and no
 * window focus, so it works on the add-on display where Android's keyboard does not (Droidtop/tracker#314)
 * and never takes the pad from the shell (SPEC 4c, "a Presentation takes no focus").
 */
@Composable
internal fun CompanionSocialTab(start: OpenConversation? = null) {
    // [start]: a conversation opened from Home's Social section; B or the back pill returns to the list.
    var open by remember(start) { mutableStateOf(start) }
    val chat = open
    if (chat == null) {
        CompanionSocialList(onOpen = { open = it })
    } else {
        CompanionConversation(chat, onBack = { open = null })
    }
}

/** Which conversation the tab shows: the provider and friend, and the names to head it with. */
internal data class OpenConversation(val providerId: String, val friendId: String, val name: String, val service: String)

/** What the list draws, worked out off the main thread from [SocialHub]; Home's Social section reads the same rows. */
internal class SocialRows(
    val conversations: List<SocialContact>,
    val friends: List<SocialContact>,
    val badged: Boolean,
    val anyAccount: Boolean,
    /** Notification access is off, so other apps' conversations cannot be read. */
    val needsAccess: Boolean,
    /** Android 13+ locks that grant for a sideloaded droidtop until "Allow restricted settings" in App info. */
    val restricted: Boolean,
)

/** The conversation a contact's row opens. */
internal fun openConversation(contact: SocialContact): OpenConversation =
    OpenConversation(contact.provider.id, contact.friend.id, contact.friend.name, contact.friend.source?.label ?: contact.provider.label)

/** Reads every provider (some ask the system), so callers run it on [Dispatchers.IO]. */
internal fun socialRows(context: Context): SocialRows {
    val available = SocialHub.available(context)
    val shown = available.filter { it.presence(context) != SocialPresence.OFFLINE }
    val contacts = SocialOrder.contacts(shown)
    return SocialRows(
        SocialOrder.conversations(contacts),
        SocialOrder.friends(contacts),
        shown.size > 1,
        available.isNotEmpty(),
        !NotificationsStore.isGranted(context),
        NotificationsStore.restrictedStepNeeded(context),
    )
}

@Composable
private fun CompanionSocialList(onOpen: (OpenConversation) -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<SocialRows?>(null) }
    // Plugin providers are asked once when the tab opens; everything after that arrives as a change.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { SocialHub.refresh(context) } } }
    LaunchedEffect(Unit) { SocialHub.changes().collect { rows = withContext(Dispatchers.IO) { socialRows(context) } } }
    val current = rows
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (current?.needsAccess == true) {
            ContactRow("Allow notification access", "") { NotificationsStore.openGrantScreen(context) }
            if (current?.restricted == true) {
                ContactRow(
                    dev.droidtop.runtime.systemstatus.RestrictedSettings.TITLE,
                    dev.droidtop.runtime.systemstatus.RestrictedSettings.VALUE,
                ) { dev.droidtop.runtime.systemstatus.RestrictedSettings.openAppInfo(context) }
            }
        }
        when {
            current == null -> CompanionNote("Loading")
            !current.anyAccount -> {
                if (!current.needsAccess) CompanionNote("No accounts")
            }
            current.friends.isEmpty() && current.conversations.isEmpty() -> CompanionNote("No conversations yet")
            else -> {
                fun open(contact: SocialContact) = onOpen(openConversation(contact))
                if (current.conversations.isNotEmpty()) {
                    SectionTitle("Conversations")
                    current.conversations.forEach { c ->
                        ContactRow(c.friend.name, SocialOrder.value(c, current.badged), c.friend.source?.packageName) { open(c) }
                    }
                }
                if (current.friends.isNotEmpty()) {
                    SectionTitle("Friends")
                    current.friends.forEach { c -> ContactRow(c.friend.name, SocialOrder.value(c, current.badged)) { open(c) } }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}

/** The icons of the apps that conversations come from, loaded once each off the main thread. */
private val sourceIcons = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

/** [packageName]'s launcher icon, or nothing until it is loaded (or when the app has none). */
@Composable
private fun SourceIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState(sourceIcons[packageName], packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(64, 64).asImageBitmap() }.getOrNull()
            }?.also { sourceIcons[packageName] = it }
        }
    }
    icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun ContactRow(name: String, value: String, packageName: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (packageName != null) SourceIcon(packageName)
        Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CompanionConversation(chat: OpenConversation, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val provider = remember(chat) { SocialHub.provider(chat.providerId) }
    if (provider == null) {
        // The provider went away (its plugin was turned off): back to the list.
        LaunchedEffect(chat) { onBack() }
        return
    }
    val messages by provider.conversation(chat.friendId).collectAsState()
    // Opening reads the history and marks it read; while it is open its messages do not notify.
    DisposableEffect(chat) {
        val job = scope.launch(Dispatchers.IO) { runCatching { provider.open(chat.friendId) } }
        onDispose {
            job.cancel()
            provider.close(chat.friendId)
        }
    }
    var draft by remember(chat) { mutableStateOf(CompanionDraft()) }
    var keyboard by remember(chat) { mutableStateOf(false) }
    var sending by remember(chat) { mutableStateOf(false) }
    var failure by remember(chat) { mutableStateOf<String?>(null) }

    fun send() {
        val text = draft.text.trim()
        if (sending || !SocialOrder.sendable(text)) return
        sending = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { provider.send(chat.friendId, text) }
            sending = false
            result.onSuccess {
                draft = CompanionDraft()
                failure = null
            }.onFailure { failure = userFacingErrorMessage(it) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CompanionPill("Back", onClick = onBack)
            Column(modifier = Modifier.weight(1f)) {
                Text(chat.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(chat.service, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Another app's conversation opens in that app.
            provider.sourceOf(chat.friendId)?.let { source ->
                CompanionPill("Open in ${source.label}") { scope.launch(Dispatchers.IO) { provider.openInSource(context, chat.friendId) } }
            }
        }
        // Newest at the bottom, the way a conversation reads; the list starts there.
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (messages.isEmpty()) {
                item { CompanionNote("No messages yet") }
            }
            items(messages.asReversed(), key = { it.key }) { message -> MessageBubble(context, message, chat.name) }
        }
        failure?.let { Text("Not sent: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        // Re-read when the conversation changes: its notification may have gone, and with it the reply.
        val canSend = remember(messages) { provider.canSend(chat.friendId) }
        if (canSend) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DraftField(
                draft = draft,
                modifier = Modifier.weight(1f),
                onTap = { offset ->
                    draft = draft.placeCursor(offset)
                    keyboard = true
                },
            )
            CompanionPill(if (sending) "Sending" else "Send", selected = SocialOrder.sendable(draft.text.trim())) { send() }
            CompanionPill(if (keyboard) "Hide" else "Keys") { keyboard = !keyboard }
        }
        if (canSend && keyboard) {
            // droidtop's one keyboard (KeyboardPanel); here its keys edit the draft directly, so no input method
            // and no window focus are involved (docs/SPEC.md 4c).
            DroidtopKeyboard(
                sink = object : KeyboardSink {
                    override fun key(androidKeyCode: Int, down: Boolean) {
                        val (next, submit) = draft.key(androidKeyCode, down, ::virtualChar)
                        draft = next
                        if (submit) send()
                    }

                    override fun text(chars: CharSequence) {
                        draft = draft.insert(chars)
                    }
                },
            )
        }
    }
}

@Composable
private fun MessageBubble(context: Context, message: SocialMessage, friendName: String) {
    val colors = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (message.mine) colors.primaryContainer else colors.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(message.text, style = MaterialTheme.typography.bodyLarge, color = if (message.mine) colors.onPrimaryContainer else colors.onSurfaceVariant)
            Text(
                (if (message.mine) "You" else friendName) + " · " + SocialTime.text(context, message.timeMs),
                style = MaterialTheme.typography.labelSmall,
                color = if (message.mine) colors.onPrimaryContainer else colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * The draft as a field: its text with a caret, "Message" when empty. A tap places the caret where it lands and
 * opens the keyboard. It never takes focus (the companion's tree denies it); the keyboard below types into it.
 */
@Composable
private fun DraftField(draft: CompanionDraft, modifier: Modifier, onTap: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val caretColor = colors.primary
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(12.dp))
            .pointerInput(draft.text) {
                detectTapGestures { position ->
                    val at = layout?.takeIf { draft.text.isNotEmpty() }?.getOffsetForPosition(position - Offset(12.dp.toPx(), 12.dp.toPx()))
                    onTap(at ?: draft.text.length)
                }
            }
            .padding(12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (draft.text.isEmpty()) {
            Text("Message", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        }
        Text(
            draft.text,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth().drawBehind {
                val l = layout ?: return@drawBehind
                val rect = l.getCursorRect(draft.cursor.coerceIn(0, draft.text.length))
                drawLine(caretColor, Offset(rect.left, rect.top), Offset(rect.left, rect.bottom), strokeWidth = 2.dp.toPx())
            },
        )
    }
}

private val virtualKeys: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

/** The character [keyCode] types on Android's built-in virtual keyboard map, with or without Shift; null for none. */
private fun virtualChar(keyCode: Int, shift: Boolean): Char? {
    val unicode = virtualKeys.get(keyCode, if (shift) KeyEvent.META_SHIFT_ON else 0)
    if (unicode == 0 || unicode and KeyCharacterMap.COMBINING_ACCENT != 0) return null
    return unicode.toChar()
}

/**
 * The companion's message draft (pure, so it is tested without a device): text, a caret, and the modifier state the
 * keyboard's hardware-style key stream implies. Keys arrive as presses and releases of Android keycodes, Shift
 * as its own key around a capital, exactly as `SecondScreenKeyboardListener` sends them to any destination.
 */
internal data class CompanionDraft(
    val text: String = "",
    val cursor: Int = 0,
    val shift: Boolean = false,
    val control: Boolean = false,
) {
    /** The draft after [keyCode] went [down] or up, and whether it asked to send (Enter). [charFor] maps a key to what it types. */
    fun key(keyCode: Int, down: Boolean, charFor: (Int, Boolean) -> Char?): Pair<CompanionDraft, Boolean> {
        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> return copy(shift = down) to false
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            -> return copy(control = down) to false
        }
        if (!down) return this to false
        val at = cursor.coerceIn(0, text.length)
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> this to true
            KeyEvent.KEYCODE_DEL -> backspace(at) to false
            KeyEvent.KEYCODE_FORWARD_DEL -> forwardDelete(at) to false
            KeyEvent.KEYCODE_DPAD_LEFT -> copy(cursor = stepBack(at)) to false
            KeyEvent.KEYCODE_DPAD_RIGHT -> copy(cursor = stepForward(at)) to false
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_DPAD_UP -> copy(cursor = 0) to false
            KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_DPAD_DOWN -> copy(cursor = text.length) to false
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE -> this to false
            else -> {
                // A Ctrl or Alt chord is a shortcut elsewhere; here it types nothing.
                if (control) return this to false
                val c = charFor(keyCode, shift) ?: return this to false
                insert(c.toString()) to false
            }
        }
    }

    /** [chars] at the caret, cut so the draft never grows past one message. */
    fun insert(chars: CharSequence): CompanionDraft {
        val at = cursor.coerceIn(0, text.length)
        val room = (SocialOrder.MAX_MESSAGE - text.length).coerceAtLeast(0)
        val add = chars.toString().take(room)
        if (add.isEmpty()) return this
        return copy(text = text.substring(0, at) + add + text.substring(at), cursor = at + add.length)
    }

    /** The caret at [offset], kept inside the text. */
    fun placeCursor(offset: Int): CompanionDraft = copy(cursor = offset.coerceIn(0, text.length))

    private fun stepBack(at: Int): Int = when {
        at <= 0 -> 0
        at >= 2 && Character.isLowSurrogate(text[at - 1]) && Character.isHighSurrogate(text[at - 2]) -> at - 2
        else -> at - 1
    }

    private fun stepForward(at: Int): Int = when {
        at >= text.length -> text.length
        at + 1 < text.length && Character.isHighSurrogate(text[at]) && Character.isLowSurrogate(text[at + 1]) -> at + 2
        else -> at + 1
    }

    private fun backspace(at: Int): CompanionDraft {
        val from = stepBack(at)
        return if (from == at) this else copy(text = text.removeRange(from, at), cursor = from)
    }

    private fun forwardDelete(at: Int): CompanionDraft {
        val to = stepForward(at)
        return if (to == at) this else copy(text = text.removeRange(at, to), cursor = at)
    }
}
