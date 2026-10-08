package dev.droidtop.library.social

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Real notifications, built the way a messaging app builds them, read into the conversation model
 * (docs/SPEC.md "Social: messages from your apps", Droidtop/tracker#329), and a provider holding the result.
 */
@RunWith(RobolectricTestRunner::class)
// A library module has no targetSdk of its own for Robolectric to pick its android-all jar from; pin one.
@Config(sdk = [34])
class AppMessageExtractorTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun pending(): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent("test.action"), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(title: String, semantic: Int, input: Boolean, freeForm: Boolean = true): Notification.Action {
        val builder = Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_dialog_info), title, pending())
            .setSemanticAction(semantic)
        if (input) builder.addRemoteInput(RemoteInput.Builder("reply").setAllowFreeFormInput(freeForm).build())
        return builder.build()
    }

    private fun conversation(): Notification {
        val me = Person.Builder().setName("Me").setKey("me").build()
        val alice = Person.Builder().setName("Alice").setKey("alice").build()
        val style = Notification.MessagingStyle(me)
            .setConversationTitle("Weekend")
            .setGroupConversation(true)
            .addMessage("hi", 1_000L, alice)
            .addMessage("ok", 2_000L, me)
            .addMessage("where?", 3_000L, alice)
        return Notification.Builder(context, "chat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(style)
            .setShortcutId("shortcut-1")
            .setContentIntent(pending())
            .addAction(action("Mark as read", Notification.Action.SEMANTIC_ACTION_MARK_AS_READ, input = false))
            .addAction(action("Reply", Notification.Action.SEMANTIC_ACTION_REPLY, input = true))
            .build()
    }

    @Test
    fun `a MessagingStyle notification becomes a conversation with senders, times and who wrote them`() {
        val extracted = AppMessageExtractor.extract(conversation(), "k1", "com.example.chat", 9_000L, "Chat")
        assertNotNull(extracted)
        val note = extracted!!.note
        assertTrue(note.messagingStyle)
        assertEquals("Weekend", note.title)
        assertTrue(note.group)
        assertEquals("shortcut-1", note.shortcutId)
        assertEquals("shortcut-1", note.conversationId)
        assertEquals(listOf("hi", "ok", "where?"), note.messages.map { it.text })
        assertEquals(listOf("Alice", "Me", "Alice"), note.messages.map { it.sender })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), note.messages.map { it.timeMs })
        assertEquals(listOf(false, true, false), note.messages.map { it.mine })
        assertNotNull(extracted.handles.contentIntent)

        val conversations = AppMessagesModel.conversations(listOf(note))
        assertEquals(1, conversations.single().unread)
        assertEquals("Chat", conversations.single().source.label)
    }

    @Test
    fun `the reply and mark as read actions are found by what they are, not where they sit`() {
        val note = AppMessageExtractor.extract(conversation(), "k1", "com.example.chat", 9_000L, "Chat")!!.note
        assertEquals(1, AppMessagesModel.pickReply(note.actions)?.index)
        assertEquals(0, AppMessagesModel.pickMarkRead(note.actions)?.index)
    }

    @Test
    fun `a message notification without MessagingStyle is one line from its title and text`() {
        val n = Notification.Builder(context, "chat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentTitle("Bob")
            .setContentText("yo")
            .build()
        val note = AppMessageExtractor.extract(n, "k2", "com.whatsapp", 5_000L, "WhatsApp")!!.note
        assertFalse(note.messagingStyle)
        assertTrue(note.messageCategory)
        assertEquals("Bob", note.title)
        assertEquals(listOf("yo"), note.messages.map { it.text })
        assertFalse(note.messages.single().mine)
        assertTrue(AppMessagesModel.included(note, null))
    }

    @Test
    fun `an action with only canned answers is not offered as a reply`() {
        val n = Notification.Builder(context, "chat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentTitle("Bob")
            .setContentText("yo")
            .addAction(action("Reply", Notification.Action.SEMANTIC_ACTION_REPLY, input = true, freeForm = false))
            .build()
        val note = AppMessageExtractor.extract(n, "k3", "com.whatsapp", 5_000L, "WhatsApp")!!.note
        assertNull(AppMessagesModel.pickReply(note.actions))
    }

    @Test
    fun `a notification that says nothing is not a conversation`() {
        val n = Notification.Builder(context, "chat").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        assertNull(AppMessageExtractor.extract(n, "k4", "com.example", 1L, "Example"))
    }

    // The provider holds what the notifications carried, and lets go of it when they go.

    private fun extractedConversation(key: String, title: String): ExtractedNote {
        val note = MessageNote(
            key = key,
            packageName = "com.example.chat",
            appLabel = "Chat",
            postTime = 1_000L,
            conversationId = title,
            title = title,
            group = false,
            shortcutId = null,
            messagingStyle = true,
            messageCategory = false,
            messages = listOf(NoteMessage(title, "hello", 1_000L, mine = false)),
            actions = emptyList(),
        )
        return ExtractedNote(note, NoteHandles(null, emptyList()))
    }

    @Test
    fun `a conversation whose notification went stays read only for a while, then goes`() {
        AppMessages.unbind()
        AppMessages.setPosted(listOf(extractedConversation("a", "Alice")), nowMs = 10_000L)
        val live = AppMessages.friends.value.single()
        assertEquals("Alice", live.name)
        assertEquals(1, live.unread)
        assertEquals("Chat", live.source?.label)
        assertEquals(1, AppMessages.unread.value)

        AppMessages.setPosted(emptyList(), nowMs = 20_000L)
        val retired = AppMessages.friends.value.single()
        assertEquals(0, retired.unread)
        assertEquals(0, AppMessages.unread.value)
        assertEquals(listOf("hello"), AppMessages.conversation(retired.id).value.map { it.text })
        assertFalse(AppMessages.canSend(retired.id))

        AppMessages.setPosted(emptyList(), nowMs = 20_000L + AppMessages.RETAIN_MS + 1L)
        assertTrue(AppMessages.friends.value.isEmpty())
        AppMessages.unbind()
    }

    @Test
    fun `at most a bounded number of finished conversations are kept`() {
        AppMessages.unbind()
        val many = (1..30).map { extractedConversation("n$it", "Person $it") }
        AppMessages.setPosted(many, nowMs = 1_000L)
        assertEquals(30, AppMessages.friends.value.size)
        AppMessages.setPosted(emptyList(), nowMs = 2_000L)
        assertEquals(AppMessages.MAX_RETIRED, AppMessages.friends.value.size)
        AppMessages.unbind()
        assertTrue(AppMessages.friends.value.isEmpty())
    }

    @Test
    fun `losing notification access forgets everything`() {
        AppMessages.setPosted(listOf(extractedConversation("a", "Alice")), nowMs = 1_000L)
        AppMessages.unbind()
        assertTrue(AppMessages.friends.value.isEmpty())
        assertEquals(0, AppMessages.unread.value)
    }
}
