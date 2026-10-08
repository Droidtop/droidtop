package dev.droidtop.library.social

/*
 * Conversations from other apps' notifications (docs/SPEC.md "Social: messages from your apps",
 * Droidtop/tracker#329). Everything here is plain data and pure rules, so the notification parsing
 * and the choice of which action replies are tested without a device. The Android side that fills
 * these from a notification is [AppMessageExtractor]; the provider that holds the result is [AppMessages].
 */

/** One message a notification exposes. [sender] is null when the app named none. */
data class NoteMessage(val sender: String?, val text: String, val timeMs: Long, val mine: Boolean)

/**
 * One action of a notification, by its place in the notification's action list. [semantic] is
 * `Notification.Action.getSemanticAction` (0 when the app set none or the system is older than Android 9).
 */
data class NoteAction(
    val index: Int,
    val title: String,
    val semantic: Int,
    val hasRemoteInput: Boolean,
    val freeForm: Boolean,
)

/**
 * What one notification says about a conversation, with no Android types. [conversationId] is the
 * app's conversation shortcut when it set one, else the conversation's title: it is what makes
 * two notifications one conversation.
 */
data class MessageNote(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val postTime: Long,
    val conversationId: String,
    val title: String,
    val group: Boolean,
    val shortcutId: String?,
    /** The notification uses `Notification.MessagingStyle`: the app says it is a conversation. */
    val messagingStyle: Boolean,
    /** The notification's category is "msg". */
    val messageCategory: Boolean,
    val messages: List<NoteMessage>,
    val actions: List<NoteAction>,
)

/** One conversation as the lists draw it: the notifications of one app and conversation merged. */
data class AppConversation(
    /** `<package>|<conversation id>`: the friend id the Social lists use. */
    val id: String,
    val source: SocialSource,
    val title: String,
    val group: Boolean,
    val shortcutId: String?,
    /** Oldest first, at most [AppMessagesModel.MAX_MESSAGES]. */
    val messages: List<SocialMessage>,
    val unread: Int,
    val lastMessageMs: Long,
    /** The keys of the notifications behind it, newest first. */
    val noteKeys: List<String>,
)

object AppMessagesModel {
    const val MAX_MESSAGES = 30

    /** `Notification.Action.SEMANTIC_ACTION_REPLY` and `SEMANTIC_ACTION_MARK_AS_READ`. */
    const val SEMANTIC_REPLY = 1
    const val SEMANTIC_MARK_AS_READ = 2

    /**
     * Messaging apps that are included by default even for a notification that is not MessagingStyle but has
     * the message category. Any app that posts MessagingStyle is included by default regardless.
     */
    val KNOWN_APPS: Set<String> = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
        "org.thoughtcrime.securesms", "com.discord", "com.facebook.orca", "com.google.android.apps.messaging",
        "com.samsung.android.messaging", "com.android.mms", "com.slack", "com.microsoft.teams",
        "com.skype.raider", "com.viber.voip", "jp.naver.line.android", "com.tencent.mm", "com.kakao.talk",
        "im.vector.app", "org.mozilla.fennec_fdroid.messaging", "com.beeper.android", "chat.simplex.app",
    )

    fun conversationKey(note: MessageNote): String = note.packageName + "|" + note.conversationId

    /** Whether the notification looks like a message at all: the app says MessagingStyle or the message category. */
    fun isCandidate(note: MessageNote): Boolean = note.messagingStyle || note.messageCategory

    /** Whether the person has not chosen: any app posting MessagingStyle, and the known messaging apps. */
    fun defaultIncluded(packageName: String, messagingStyle: Boolean): Boolean = messagingStyle || packageName in KNOWN_APPS

    /** Whether [note] joins the Social place; [chosen] is the person's own setting for its app, null when none. */
    fun included(note: MessageNote, chosen: Boolean?): Boolean =
        isCandidate(note) && (chosen ?: defaultIncluded(note.packageName, note.messagingStyle))

    /**
     * The action that sends a typed reply: the one the app marked as the reply action, else the first with a
     * free-form text input. An action that only offers canned answers is not a reply.
     */
    fun pickReply(actions: List<NoteAction>): NoteAction? {
        val typed = actions.filter { it.hasRemoteInput && it.freeForm }
        return typed.firstOrNull { it.semantic == SEMANTIC_REPLY } ?: typed.firstOrNull()
    }

    /** The action that marks the conversation read, when the app offers one. */
    fun pickMarkRead(actions: List<NoteAction>): NoteAction? = actions.firstOrNull { it.semantic == SEMANTIC_MARK_AS_READ }

    /** The newest note ([newestFirst]) that has a reply action, with that action. */
    fun replyTarget(newestFirst: List<MessageNote>): Pair<MessageNote, NoteAction>? =
        newestFirst.firstNotNullOfOrNull { note -> pickReply(note.actions)?.let { note to it } }

    /**
     * Whether a message was written by the person: the app named no sender, or the sender is the app's own
     * "you" (same key, else same name).
     */
    fun isMine(senderName: CharSequence?, senderKey: String?, selfName: CharSequence?, selfKey: String?): Boolean {
        if (senderName.isNullOrBlank() && senderKey.isNullOrBlank()) return true
        if (!senderKey.isNullOrBlank() && !selfKey.isNullOrBlank()) return senderKey == selfKey
        return !senderName.isNullOrBlank() && !selfName.isNullOrBlank() && senderName.toString() == selfName.toString()
    }

    private fun message(note: MessageNote, m: NoteMessage): SocialMessage {
        val text = if (note.group && !m.mine && !m.sender.isNullOrBlank()) m.sender + ": " + m.text else m.text
        return SocialMessage(key = "${m.timeMs}:${m.mine}:${(m.sender ?: "").hashCode()}:${m.text.hashCode()}", mine = m.mine, text = text, timeMs = m.timeMs)
    }

    /** The unread count of a conversation: what the other side wrote after the person's last message. */
    fun unreadAfterLastMine(messages: List<SocialMessage>): Int = messages.takeLastWhile { !it.mine }.size

    /**
     * The conversations [notes] describe: notifications of one app and conversation merged, messages in time
     * order without repeats and cut to the newest [MAX_MESSAGES], the unread count, the app as the source. The
     * newest conversation first.
     */
    fun conversations(notes: Collection<MessageNote>): List<AppConversation> =
        notes.groupBy { conversationKey(it) }.map { (id, group) ->
            val newestFirst = group.sortedByDescending { it.postTime }
            val newest = newestFirst.first()
            val messages = SocialOrder.merged(emptyList(), group.flatMap { note -> note.messages.map { message(note, it) } })
                .takeLast(MAX_MESSAGES)
            AppConversation(
                id = id,
                source = SocialSource(newest.packageName, newest.appLabel),
                title = newest.title,
                group = newest.group,
                shortcutId = newestFirst.firstNotNullOfOrNull { it.shortcutId },
                messages = messages,
                unread = unreadAfterLastMine(messages),
                lastMessageMs = messages.lastOrNull()?.timeMs ?: newest.postTime,
                noteKeys = newestFirst.map { it.key },
            )
        }.sortedByDescending { it.lastMessageMs }
}
