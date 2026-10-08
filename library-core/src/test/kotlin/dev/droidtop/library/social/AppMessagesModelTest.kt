package dev.droidtop.library.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversations from other apps' notifications, the reply action choice and the include rules (docs/SPEC.md "Social: messages from your apps", Droidtop/tracker#329). */
class AppMessagesModelTest {
    private fun note(
        key: String,
        conversation: String = "Alice",
        pkg: String = "com.example.chat",
        post: Long = 1_000L,
        messages: List<NoteMessage> = emptyList(),
        actions: List<NoteAction> = emptyList(),
        group: Boolean = false,
        style: Boolean = true,
        category: Boolean = false,
        shortcut: String? = null,
    ) = MessageNote(
        key = key,
        packageName = pkg,
        appLabel = "Chat",
        postTime = post,
        conversationId = conversation,
        title = conversation,
        group = group,
        shortcutId = shortcut,
        messagingStyle = style,
        messageCategory = category,
        messages = messages,
        actions = actions,
    )

    private fun theirs(text: String, at: Long, who: String? = "Alice") = NoteMessage(who, text, at, mine = false)
    private fun mine(text: String, at: Long) = NoteMessage("Me", text, at, mine = true)

    @Test
    fun `two notifications of one conversation are one conversation with every message once`() {
        val a = note("n1", post = 2_000L, messages = listOf(theirs("hi", 1_000L), theirs("there?", 2_000L)))
        val b = note("n2", post = 3_000L, messages = listOf(theirs("there?", 2_000L), theirs("hello", 3_000L)))
        val conversations = AppMessagesModel.conversations(listOf(a, b))
        assertEquals(1, conversations.size)
        val c = conversations.single()
        assertEquals(listOf("hi", "there?", "hello"), c.messages.map { it.text })
        assertEquals(3, c.unread)
        assertEquals(3_000L, c.lastMessageMs)
        assertEquals(listOf("n2", "n1"), c.noteKeys)
        assertEquals(SocialSource("com.example.chat", "Chat"), c.source)
    }

    @Test
    fun `the same title in two apps is two conversations`() {
        val conversations = AppMessagesModel.conversations(
            listOf(
                note("n1", pkg = "com.a", messages = listOf(theirs("x", 1L))),
                note("n2", pkg = "com.b", messages = listOf(theirs("y", 2L))),
            ),
        )
        assertEquals(setOf("com.a|Alice", "com.b|Alice"), conversations.map { it.id }.toSet())
    }

    @Test
    fun `unread is what the other side wrote after the person's last message`() {
        val c = AppMessagesModel.conversations(
            listOf(note("n", messages = listOf(theirs("a", 1L), mine("b", 2L), theirs("c", 3L), theirs("d", 4L)))),
        ).single()
        assertEquals(2, c.unread)
        val answered = AppMessagesModel.conversations(listOf(note("n", messages = listOf(theirs("a", 1L), mine("b", 2L))))).single()
        assertEquals(0, answered.unread)
    }

    @Test
    fun `a group message names its sender and a one to one message does not`() {
        val group = AppMessagesModel.conversations(
            listOf(note("g", conversation = "Crew", group = true, messages = listOf(theirs("on my way", 1L, "Bo"), mine("ok", 2L)))),
        ).single()
        assertEquals(listOf("Bo: on my way", "ok"), group.messages.map { it.text })
        val direct = AppMessagesModel.conversations(listOf(note("d", messages = listOf(theirs("on my way", 1L))))).single()
        assertEquals("on my way", direct.messages.single().text)
    }

    @Test
    fun `a conversation keeps only its newest messages`() {
        val many = (1..50).map { theirs("m$it", it.toLong()) }
        val c = AppMessagesModel.conversations(listOf(note("n", messages = many))).single()
        assertEquals(AppMessagesModel.MAX_MESSAGES, c.messages.size)
        assertEquals("m50", c.messages.last().text)
    }

    @Test
    fun `conversations come newest first`() {
        val list = AppMessagesModel.conversations(
            listOf(
                note("old", conversation = "Old", messages = listOf(theirs("x", 1L))),
                note("new", conversation = "New", messages = listOf(theirs("y", 9L))),
            ),
        )
        assertEquals(listOf("New", "Old"), list.map { it.title })
    }

    private fun action(index: Int, semantic: Int = 0, input: Boolean = true, freeForm: Boolean = true) =
        NoteAction(index, "action$index", semantic, input, freeForm)

    @Test
    fun `the reply action is the one marked as reply, else the first with free text`() {
        val marked = listOf(action(0, input = false), action(1), action(2, AppMessagesModel.SEMANTIC_REPLY))
        assertEquals(2, AppMessagesModel.pickReply(marked)?.index)
        val unmarked = listOf(action(0, input = false), action(1), action(2))
        assertEquals(1, AppMessagesModel.pickReply(unmarked)?.index)
    }

    @Test
    fun `an action that only offers canned answers is not a reply`() {
        assertNull(AppMessagesModel.pickReply(listOf(action(0, AppMessagesModel.SEMANTIC_REPLY, freeForm = false), action(1, input = false))))
        assertNull(AppMessagesModel.pickReply(emptyList()))
    }

    @Test
    fun `mark as read is the action with that meaning`() {
        val actions = listOf(action(0), action(1, AppMessagesModel.SEMANTIC_MARK_AS_READ, input = false))
        assertEquals(1, AppMessagesModel.pickMarkRead(actions)?.index)
        assertNull(AppMessagesModel.pickMarkRead(listOf(action(0))))
    }

    @Test
    fun `the reply goes through the newest notification that can take one`() {
        val newest = note("newest", post = 5L, actions = emptyList())
        val older = note("older", post = 3L, actions = listOf(action(0, AppMessagesModel.SEMANTIC_REPLY)))
        val target = AppMessagesModel.replyTarget(listOf(newest, older))
        assertEquals("older", target?.first?.key)
        assertNull(AppMessagesModel.replyTarget(listOf(newest)))
    }

    @Test
    fun `no sender or the apps own you is the person`() {
        assertTrue(AppMessagesModel.isMine(null, null, "Me", "me"))
        assertTrue(AppMessagesModel.isMine("Me", "me", "Me", "me"))
        assertFalse(AppMessagesModel.isMine("Alice", "alice", "Me", "me"))
        // Without keys the names decide.
        assertTrue(AppMessagesModel.isMine("Me", null, "Me", null))
        assertFalse(AppMessagesModel.isMine("Alice", null, "Me", null))
        // Two people of one name are told apart by their keys.
        assertFalse(AppMessagesModel.isMine("Me", "other", "Me", "me"))
    }

    @Test
    fun `any app posting MessagingStyle is included, a known app is included for the message category, and a choice wins`() {
        val styled = note("a", pkg = "com.unknown", style = true)
        assertTrue(AppMessagesModel.included(styled, null))
        assertFalse(AppMessagesModel.included(styled, false))

        val knownLine = note("b", pkg = "com.whatsapp", style = false, category = true)
        assertTrue(AppMessagesModel.included(knownLine, null))
        assertFalse(AppMessagesModel.included(knownLine, false))

        val unknownLine = note("c", pkg = "com.unknown", style = false, category = true)
        assertFalse(AppMessagesModel.included(unknownLine, null))
        assertTrue(AppMessagesModel.included(unknownLine, true))

        val notAMessage = note("d", pkg = "com.whatsapp", style = false, category = false)
        assertFalse(AppMessagesModel.included(notAMessage, true))
    }

    @Test
    fun `a conversation from another app names the app and is not a friend`() {
        val provider = FakeProvider
        val source = SocialSource("com.whatsapp", "WhatsApp")
        val fresh = SocialFriend("w|Alice", "Alice", SocialState.ONLINE, null, 2, 5L, source)
        val read = fresh.copy(unread = 0)
        val person = SocialFriend("p", "Pat", SocialState.ONLINE, null, 0, 0L)
        assertEquals("2 new", SocialOrder.value(fresh))
        assertEquals("WhatsApp", SocialOrder.value(read))
        assertEquals("2 new · WhatsApp", SocialOrder.value(SocialContact(provider, fresh), false))
        assertEquals("WhatsApp", SocialOrder.value(SocialContact(provider, read), true))
        val contacts = listOf(SocialContact(provider, read), SocialContact(provider, person))
        assertEquals(listOf("Pat"), SocialOrder.friends(contacts).map { it.friend.name })
        assertEquals(setOf("Alice"), SocialOrder.conversations(listOf(SocialContact(provider, fresh))).map { it.friend.name }.toSet())
    }

    /** A provider that does nothing, for the rows that only read a provider's label. */
    private object FakeProvider : SocialProvider {
        override val id = "fake"
        override val label = "Fake"
        override val friends = kotlinx.coroutines.flow.MutableStateFlow<List<SocialFriend>>(emptyList())
        override val unread = kotlinx.coroutines.flow.MutableStateFlow(0)
        override val link = kotlinx.coroutines.flow.MutableStateFlow(SocialLink.ONLINE)
        override val me = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        override fun available(context: android.content.Context) = true
        override fun presence(context: android.content.Context): SocialPresence? = null
        override suspend fun setPresence(context: android.content.Context, presence: SocialPresence) = Unit
        override fun conversation(friendId: String) = kotlinx.coroutines.flow.MutableStateFlow<List<SocialMessage>>(emptyList())
        override suspend fun open(friendId: String) = Unit
        override fun close(friendId: String) = Unit
        override suspend fun send(friendId: String, text: String): Result<Unit> = Result.success(Unit)
    }
}
