package dev.droidtop.library.social

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import dev.droidtop.library.UserFacingException
import dev.droidtop.runtime.systemstatus.NotificationsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * "Messages from your apps" (docs/SPEC.md "Social: messages from your apps", Droidtop/tracker#329): the
 * conversations other apps expose through their notifications, as one more social provider. droidtop's
 * notification listener ([NotificationsStore]'s source) hands every change of the active notifications to
 * [onPosted]; the conversations are read from what the notifications carry ([AppMessageExtractor]), merged
 * ([AppMessagesModel]) and drawn by the Social place and the companion like any provider, badged with the
 * source app.
 *
 * A reply fills the notification's own direct-reply input and fires its action; mark-as-read fires the app's
 * own action; "Open" fires the notification's tap action. Nothing is read from another app's screens.
 *
 * Privacy: conversation content lives in memory only. A conversation is held while its notification is posted
 * and, after the app removes it (the person read it there, or it was answered), for [RETAIN_MS] and at most
 * [MAX_RETIRED] of them, read-only. It is never written to disk, never handed to a plugin, and this provider
 * never posts a notification of its own: the source app already notifies about its conversations.
 */
object AppMessages : SocialProvider {
    private const val TAG = "AppMessages"

    /** How long a conversation stays listed after its notification is gone, and how many may. */
    const val RETAIN_MS = 30 * 60 * 1000L
    const val MAX_RETIRED = 20

    override val id: String = "messages"
    override val label: String = "Messages"

    private class Retired(val conversation: AppConversation, val retiredAtMs: Long)

    private val lock = Any()
    private var live: Map<String, ExtractedNote> = emptyMap()
    private var current: Map<String, AppConversation> = emptyMap()
    private val retired = LinkedHashMap<String, Retired>()

    /** Messages sent from droidtop that the source app's notification has not echoed yet, by conversation id. */
    private val sent = HashMap<String, List<SocialMessage>>()
    private val flows = HashMap<String, MutableStateFlow<List<SocialMessage>>>()

    private val friendList = MutableStateFlow<List<SocialFriend>>(emptyList())
    private val unreadTotal = MutableStateFlow(0)
    private val linkState = MutableStateFlow(SocialLink.OFF)
    private val meName = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inbox = Channel<List<StatusBarNotification>>(Channel.CONFLATED)
    private var started = false
    private var purgeJob: Job? = null

    @Volatile
    private var appContext: Context? = null

    override val friends: StateFlow<List<SocialFriend>> get() = friendList
    override val unread: StateFlow<Int> get() = unreadTotal
    override val link: StateFlow<SocialLink> get() = linkState
    override val me: StateFlow<String?> get() = meName

    /** Available once notification access is granted; until then the Social place offers the grant instead. */
    override fun available(context: Context): Boolean = NotificationsStore.isGranted(context)

    override fun presence(context: Context): SocialPresence? = null

    override suspend fun setPresence(context: Context, presence: SocialPresence) = Unit

    /** The listener connected: conversations can be read. */
    fun bind(context: Context) {
        appContext = context.applicationContext
        synchronized(lock) {
            if (!started) {
                started = true
                scope.launch { for (list in inbox) runCatching { process(list) }.onFailure { Timber.tag(TAG).w(it, "Reading notifications failed") } }
            }
        }
        linkState.value = SocialLink.ONLINE
    }

    /** The listener went away: nothing is known about other apps' conversations any more, so nothing is kept. */
    fun unbind() {
        linkState.value = SocialLink.OFF
        synchronized(lock) {
            live = emptyMap()
            current = emptyMap()
            retired.clear()
            sent.clear()
            lastActive = emptyList()
            publish()
        }
    }

    /** The active notifications changed: the conversations are worked out again, off the listener's thread. */
    fun onPosted(active: List<StatusBarNotification>) {
        lastActive = active
        inbox.trySend(active)
    }

    /** The person included or left out an app: the conversations are worked out again from the notifications now posted. */
    fun choose(context: Context, packageName: String, on: Boolean) {
        AppMessagesPrefs.setChosen(context, packageName, on)
        inbox.trySend(lastActive)
    }

    @Volatile
    private var lastActive: List<StatusBarNotification> = emptyList()

    private val labels = HashMap<String, String>()

    private fun process(active: List<StatusBarNotification>) {
        val context = appContext ?: return
        val chosen = AppMessagesPrefs.allChosen(context)
        val extracted = ArrayList<ExtractedNote>()
        for (sbn in active) {
            if (sbn.packageName == context.packageName) continue
            val app = labels.getOrPut(sbn.packageName) {
                runCatching {
                    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
                }.getOrDefault(sbn.packageName)
            }
            val one = AppMessageExtractor.fromPosted(sbn, app) ?: continue
            if (!AppMessagesModel.isCandidate(one.note)) continue
            AppMessagesPrefs.noteSeen(context, sbn.packageName, one.note.messagingStyle)
            if (AppMessagesModel.included(one.note, chosen[sbn.packageName])) extracted += one
        }
        setPosted(extracted)
    }

    /** Takes [extracted] as the notifications now posted: a conversation that dropped out is retired, one that came back is live again. */
    internal fun setPosted(extracted: List<ExtractedNote>, nowMs: Long = System.currentTimeMillis()) {
        var retiredAny = false
        synchronized(lock) {
            val conversations = AppMessagesModel.conversations(extracted.map { it.note }).associateBy { it.id }
            for ((conversationId, conversation) in current) {
                if (conversationId !in conversations) {
                    retired[conversationId] = Retired(conversation.copy(unread = 0), nowMs)
                    retiredAny = true
                }
            }
            for (conversationId in conversations.keys) retired.remove(conversationId)
            live = extracted.associateBy { it.note.key }
            current = conversations
            purgeRetired(nowMs)
            publish()
        }
        if (retiredAny) {
            // One pending purge: the newest retirement sets when the oldest kept conversation expires.
            purgeJob?.cancel()
            purgeJob = scope.launch {
                delay(RETAIN_MS + 1_000L)
                synchronized(lock) {
                    purgeRetired(System.currentTimeMillis())
                    publish()
                }
            }
        }
    }

    private fun purgeRetired(nowMs: Long) {
        retired.entries.removeAll { nowMs - it.value.retiredAtMs > RETAIN_MS }
        while (retired.size > MAX_RETIRED) retired.remove(retired.keys.first())
    }

    /** The conversation as the screens read it: the app's messages with ours that it has not echoed back yet. */
    private fun view(conversation: AppConversation): AppConversation {
        val mineTexts = conversation.messages.filter { it.mine }.map { it.text }.toSet()
        val pending = sent[conversation.id].orEmpty().filter { it.text !in mineTexts }
        if (pending.isEmpty()) {
            sent.remove(conversation.id)
            return conversation
        }
        sent[conversation.id] = pending
        val messages = SocialOrder.merged(conversation.messages, pending).takeLast(AppMessagesModel.MAX_MESSAGES)
        return conversation.copy(
            messages = messages,
            unread = AppMessagesModel.unreadAfterLastMine(messages),
            lastMessageMs = messages.lastOrNull()?.timeMs ?: conversation.lastMessageMs,
        )
    }

    /** Called with [lock] held. */
    private fun publish() {
        val shown = (current.values + retired.values.map { it.conversation }).map(::view)
        val ids = shown.map { it.id }.toSet()
        sent.keys.retainAll(ids)
        friendList.value = SocialOrder.sorted(
            shown.map {
                SocialFriend(
                    id = it.id,
                    name = it.title,
                    state = SocialState.ONLINE,
                    activity = null,
                    unread = it.unread,
                    lastMessageMs = it.lastMessageMs,
                    source = it.source,
                )
            },
        )
        unreadTotal.value = shown.sumOf { it.unread }
        for (conversation in shown) flow(conversation.id).value = conversation.messages
        flows.keys.retainAll(ids)
    }

    private fun flow(friendId: String): MutableStateFlow<List<SocialMessage>> = flows.getOrPut(friendId) { MutableStateFlow(emptyList()) }

    override fun conversation(friendId: String): StateFlow<List<SocialMessage>> = synchronized(lock) { flow(friendId) }

    /** The conversation was opened here, so the source app is told it has been read (its own mark-as-read action, when it has one). */
    override suspend fun open(friendId: String) {
        val context = appContext ?: return
        val targets = synchronized(lock) {
            val conversation = current[friendId] ?: return
            conversation.noteKeys.mapNotNull { key ->
                val one = live[key] ?: return@mapNotNull null
                AppMessagesModel.pickMarkRead(one.note.actions)?.let { one.handles.actions.getOrNull(it.index) }
            }
        }
        for (action in targets) {
            runCatching { action.actionIntent.send(context, 0, null) }.onFailure { Timber.tag(TAG).d(it, "Marking read failed") }
        }
    }

    override fun close(friendId: String) = Unit

    override fun canSend(friendId: String): Boolean = synchronized(lock) { replyTarget(friendId) != null }

    override fun sourceOf(friendId: String): SocialSource? = synchronized(lock) { (current[friendId] ?: retired[friendId]?.conversation)?.source }

    /** Called with [lock] held: the newest live notification of the conversation that can be replied to, and its action. */
    private fun replyTarget(friendId: String): Pair<ExtractedNote, android.app.Notification.Action>? {
        val conversation = current[friendId] ?: return null
        val notes = conversation.noteKeys.mapNotNull { live[it] }
        val (note, action) = AppMessagesModel.replyTarget(notes.map { it.note }) ?: return null
        val one = notes.first { it.note.key == note.key }
        return one to (one.handles.actions.getOrNull(action.index) ?: return null)
    }

    /**
     * Sends [text] through the notification's direct-reply action: its text input is filled and the action fired,
     * exactly what the notification shade's reply box does. The reply shows in the conversation until the app's
     * own notification carries it.
     */
    override suspend fun send(friendId: String, text: String): Result<Unit> {
        val context = appContext ?: return Result.failure(UserFacingException("Notification access is off"))
        if (!SocialOrder.sendable(text)) return Result.failure(UserFacingException("That message cannot be sent"))
        val action = synchronized(lock) { replyTarget(friendId)?.second }
            ?: return Result.failure(UserFacingException("This conversation can only be answered in its app"))
        return NotificationReply.send(context, action, text).fold(
            onSuccess = {
                synchronized(lock) {
                    val now = System.currentTimeMillis()
                    val message = SocialMessage("local-$now-${text.hashCode()}", mine = true, text = text, timeMs = now)
                    sent[friendId] = sent[friendId].orEmpty() + message
                    publish()
                }
                Result.success(Unit)
            },
            onFailure = { e -> Result.failure(e) },
        )
    }

    /** The notification's own tap action while it is posted, else the conversation's shortcut, else the app itself. */
    override fun openInSource(context: Context, friendId: String): Boolean {
        val (source, intent, shortcutId) = synchronized(lock) {
            val conversation = current[friendId] ?: retired[friendId]?.conversation ?: return false
            val notes = conversation.noteKeys.mapNotNull { live[it] }
            Triple(conversation.source, notes.firstNotNullOfOrNull { it.handles.contentIntent }, conversation.shortcutId)
        }
        if (intent != null && runCatching { intent.send() }.isSuccess) return true
        if (shortcutId != null) {
            val launcher = context.getSystemService(LauncherApps::class.java)
            if (launcher != null && runCatching {
                    check(launcher.hasShortcutHostPermission())
                    launcher.startShortcut(source.packageName, shortcutId, null, null, Process.myUserHandle())
                }.isSuccess
            ) return true
        }
        val launch = context.packageManager.getLaunchIntentForPackage(source.packageName) ?: return false
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
