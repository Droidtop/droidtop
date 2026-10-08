package dev.droidtop.library.social

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lists, the merge across providers and the conversation rules (docs/SPEC.md "Social", Droidtop/tracker#327). */
class SocialOrderTest {
    private fun friend(name: String, state: SocialState, unread: Int = 0, activity: String? = null, last: Long = 0L, id: String = name) =
        SocialFriend(id = id, name = name, state = state, activity = activity, unread = unread, lastMessageMs = last)

    /** A provider that only holds a list: what the merge reads. */
    private class Fake(override val id: String, override val label: String, list: List<SocialFriend>) : SocialProvider {
        override val friends: StateFlow<List<SocialFriend>> = MutableStateFlow(list)
        override val unread: StateFlow<Int> = MutableStateFlow(list.sumOf { it.unread })
        override val link: StateFlow<SocialLink> = MutableStateFlow(SocialLink.ONLINE)
        override val me: StateFlow<String?> = MutableStateFlow(null)
        override fun available(context: Context) = true
        override fun presence(context: Context): SocialPresence? = SocialPresence.ONLINE
        override suspend fun setPresence(context: Context, presence: SocialPresence) = Unit
        override fun conversation(friendId: String): StateFlow<List<SocialMessage>> = MutableStateFlow(emptyList())
        override suspend fun open(friendId: String) = Unit
        override fun close(friendId: String) = Unit
        override suspend fun send(friendId: String, text: String): Result<Unit> = Result.success(Unit)
    }

    @Test
    fun `unread conversations come first, then who is playing, online, busy, away, offline, by name`() {
        val sorted = SocialOrder.sorted(
            listOf(
                friend("zed", SocialState.OFFLINE),
                friend("amy", SocialState.AWAY),
                friend("Bob", SocialState.ONLINE),
                friend("cat", SocialState.IN_GAME, activity = "Hades"),
                friend("dan", SocialState.BUSY),
                friend("eve", SocialState.OFFLINE, unread = 2),
                friend("abe", SocialState.ONLINE),
            ),
        )
        assertEquals(listOf("eve", "cat", "abe", "Bob", "dan", "amy", "zed"), sorted.map { it.name })
    }

    @Test
    fun `every provider's friends merge into one list in the same order, badged by service`() {
        val steam = Fake("steam", "Steam", listOf(friend("cat", SocialState.IN_GAME, activity = "Hades"), friend("zed", SocialState.OFFLINE)))
        val chat = Fake("plugin:acme.chat/default", "Acme Chat", listOf(friend("amy", SocialState.ONLINE, unread = 1), friend("bob", SocialState.AWAY)))
        val merged = SocialOrder.contacts(listOf(steam, chat))
        assertEquals(listOf("amy", "cat", "bob", "zed"), merged.map { it.friend.name })
        assertEquals(listOf("Acme Chat", "Steam", "Acme Chat", "Steam"), merged.map { it.provider.label })
        assertEquals("1 new · Acme Chat", SocialOrder.value(merged[0], badged = true))
        assertEquals("Hades", SocialOrder.value(merged[1], badged = false))
    }

    @Test
    fun `the same friend id on two services is two contacts with their own keys`() {
        val a = Fake("steam", "Steam", listOf(friend("sam", SocialState.ONLINE, id = "42")))
        val b = Fake("plugin:acme.chat/default", "Acme Chat", listOf(friend("sam", SocialState.ONLINE, id = "42")))
        val merged = SocialOrder.contacts(listOf(a, b))
        assertEquals(2, merged.size)
        assertEquals(setOf("steam/42", "plugin:acme.chat/default/42"), merged.map { it.key }.toSet())
        // Same name and state: the service's name decides, so the order is stable.
        assertEquals(listOf("Acme Chat", "Steam"), merged.map { it.provider.label })
    }

    @Test
    fun `unread counts add up over every provider and never go below zero`() {
        val a = Fake("steam", "Steam", listOf(friend("a", SocialState.ONLINE, unread = 2), friend("b", SocialState.ONLINE, unread = 1)))
        val b = Fake("plugin:x/default", "X", listOf(friend("c", SocialState.ONLINE, unread = 4)))
        assertEquals(7, SocialOrder.unread(listOf(a, b)))
        assertEquals(0, SocialOrder.unread(emptyList()))
        val broken = object : SocialProvider by Fake("plugin:y/default", "Y", emptyList()) {
            override val unread: StateFlow<Int> = MutableStateFlow(-3)
        }
        assertEquals(7, SocialOrder.unread(listOf(a, b, broken)))
    }

    @Test
    fun `conversations are the contacts with messages, unread first, then the newest`() {
        val p = Fake(
            "steam",
            "Steam",
            listOf(
                friend("old", SocialState.ONLINE, last = 1_000L),
                friend("new", SocialState.OFFLINE, last = 9_000L),
                friend("waiting", SocialState.OFFLINE, unread = 1, last = 500L),
                friend("never", SocialState.ONLINE),
            ),
        )
        val conversations = SocialOrder.conversations(SocialOrder.contacts(listOf(p)))
        assertEquals(listOf("waiting", "new", "old"), conversations.map { it.friend.name })
    }

    @Test
    fun `a row's value is the unread count, else the game, else how they are`() {
        assertEquals("3 new", SocialOrder.value(friend("a", SocialState.IN_GAME, unread = 3, activity = "Hades")))
        assertEquals("Hades", SocialOrder.value(friend("a", SocialState.IN_GAME, activity = "Hades")))
        assertEquals("In game", SocialOrder.value(friend("a", SocialState.IN_GAME, activity = " ")))
        assertEquals("Away", SocialOrder.value(friend("a", SocialState.AWAY)))
        assertEquals("Offline", SocialOrder.value(friend("a", SocialState.OFFLINE)))
    }

    @Test
    fun `messages merge in time order with one of each`() {
        val a = SocialMessage("10.0", mine = false, text = "hi", timeMs = 10_000L)
        val b = SocialMessage("11.0", mine = true, text = "hello", timeMs = 11_000L)
        val c = SocialMessage("11.1", mine = false, text = "again", timeMs = 11_000L)
        val merged = SocialOrder.merged(listOf(b), listOf(c, a, b))
        assertEquals(listOf("10.0", "11.0", "11.1"), merged.map { it.key })
        assertEquals(merged, SocialOrder.merged(merged, merged))
    }

    @Test
    fun `BBCode is taken out of a message`() {
        assertEquals("look at this", SocialOrder.plain("[b]look[/b] at [url=https://x.y]this[/url]"))
        assertEquals("a [not a tag", SocialOrder.plain("  a [not a tag "))
        assertEquals("", SocialOrder.plain("[b][/b]"))
    }

    @Test
    fun `a message is sendable when it has words and fits in one`() {
        assertTrue(SocialOrder.sendable("hi"))
        assertFalse(SocialOrder.sendable("   "))
        assertFalse(SocialOrder.sendable("x".repeat(SocialOrder.MAX_MESSAGE + 1)))
        assertTrue(SocialOrder.sendable("x".repeat(SocialOrder.MAX_MESSAGE)))
    }

    @Test
    fun `the standing defaults to online and reads its saved key, and a plugin's word is checked`() {
        assertEquals(SocialPresence.ONLINE, SocialPresence.from(null))
        assertEquals(SocialPresence.ONLINE, SocialPresence.from("nonsense"))
        assertEquals(SocialPresence.INVISIBLE, SocialPresence.from("invisible"))
        assertEquals(SocialPresence.OFFLINE, SocialPresence.from(SocialPresence.OFFLINE.key))
        assertEquals(null, SocialPresence.parse("nonsense"))
        assertEquals(SocialState.OFFLINE, SocialState.from("asleep"))
        assertEquals(SocialState.IN_GAME, SocialState.from("in_game"))
        assertEquals(SocialLink.OFF, SocialLink.from(null))
    }
}
