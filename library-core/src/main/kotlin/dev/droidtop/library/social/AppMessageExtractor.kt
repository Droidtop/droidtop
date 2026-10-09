package dev.droidtop.library.social

import android.annotation.TargetApi
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification

/**
 * The live pieces of a notification that [AppMessages] fires: its tap action and its action list (the reply and
 * mark-as-read actions among them). Held only while the notification is posted; never stored.
 */
class NoteHandles(val contentIntent: PendingIntent?, val actions: List<Notification.Action>)

/** A notification read into the plain [MessageNote] and the [NoteHandles] that act on it. */
class ExtractedNote(val note: MessageNote, val handles: NoteHandles)

/**
 * Reads what a notification exposes about a conversation (docs/SPEC.md "Social: messages from your apps"):
 * `Notification.MessagingStyle`'s messages with their senders and times, the conversation title and whether it is
 * a group, the conversation shortcut id, and the actions. Only what the notification itself carries; nothing is
 * read from the other app's screens or storage.
 */
internal object AppMessageExtractor {
    /** A posted notification, or null for one that cannot be a conversation (a group summary, an ongoing one). */
    fun fromPosted(sbn: StatusBarNotification, appLabel: String): ExtractedNote? {
        val n = sbn.notification ?: return null
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return null
        return extract(n, sbn.key, sbn.packageName, sbn.postTime, appLabel)
    }

    /** A person's name and key, read where `android.app.Person` exists (Android 9). */
    private class Who(val name: CharSequence?, val key: String?)

    @TargetApi(Build.VERSION_CODES.P)
    private fun who(parcel: Parcelable?): Who? = (parcel as? Person)?.let { Who(it.name, it.key) }

    private const val MESSAGE_TEXT = "text"
    private const val MESSAGE_TIME = "time"
    private const val MESSAGE_SENDER = "sender"
    private const val MESSAGE_SENDER_PERSON = "sender_person"

    @Suppress("DEPRECATION")
    fun extract(n: Notification, key: String, packageName: String, postTime: Long, appLabel: String): ExtractedNote? {
        val extras = n.extras ?: return null
        val messagingStyle = extras.getString(Notification.EXTRA_TEMPLATE) == Notification.MessagingStyle::class.java.name
        val messageCategory = n.category == Notification.CATEGORY_MESSAGE

        val self = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) who(extras.getParcelable<Parcelable>(Notification.EXTRA_MESSAGING_PERSON)) else null
        val selfName = self?.name ?: extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)
        val selfKey = self?.key

        val messages = ArrayList<NoteMessage>()
        fun add(array: Array<out Parcelable>?) {
            if (array == null) return
            // MessagingStyle stores each message as a Bundle under these keys (the framework's own, the ones
            // androidx writes too); the framework's parser for them is not public API.
            for (parcel in array) {
                val m = parcel as? Bundle ?: continue
                val text = m.getCharSequence(MESSAGE_TEXT)?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val person = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) who(m.getParcelable<Parcelable>(MESSAGE_SENDER_PERSON)) else null
                val senderName: CharSequence? = person?.name ?: m.getCharSequence(MESSAGE_SENDER)
                messages += NoteMessage(
                    sender = senderName?.toString()?.takeIf { it.isNotBlank() },
                    text = text,
                    timeMs = m.getLong(MESSAGE_TIME),
                    mine = AppMessagesModel.isMine(senderName, person?.key, selfName, selfKey),
                )
            }
        }
        if (messagingStyle) {
            add(extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES))
            add(extras.getParcelableArray(Notification.EXTRA_MESSAGES))
        }

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.takeIf { it.isNotBlank() }
        if (messages.isEmpty()) {
            // A message-category notification without MessagingStyle is one line: its title and text.
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return null
            messages += NoteMessage(sender = null, text = text, timeMs = postTime, mine = false)
        }

        val shownTitle = conversationTitle ?: title ?: appLabel
        val shortcutId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.shortcutId?.takeIf { it.isNotBlank() } else null
        val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, conversationTitle != null && messagingStyle)

        val actions = NotificationReply.actions(n)
        val described = NotificationReply.describe(actions)
        val note = MessageNote(
            key = key,
            packageName = packageName,
            appLabel = appLabel,
            postTime = postTime,
            conversationId = shortcutId ?: shownTitle,
            title = shownTitle,
            group = group,
            shortcutId = shortcutId,
            messagingStyle = messagingStyle,
            messageCategory = messageCategory,
            messages = messages.sortedBy { it.timeMs },
            actions = described,
        )
        return ExtractedNote(note, NoteHandles(n.contentIntent, actions))
    }
}
