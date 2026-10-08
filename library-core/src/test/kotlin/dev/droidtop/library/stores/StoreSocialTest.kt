package dev.droidtop.library.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The friends list and conversation rules (docs/SPEC.md 7g, "Stores", Droidtop/tracker#313). */
class StoreSocialTest {
    private fun friend(name: String, state: SocialState, unread: Int = 0, activity: String? = null) =
        SocialFriend(id = name, name = name, state = state, activity = activity, unread = unread, lastMessageMs = 0L)

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
    fun `the standing defaults to online and reads its saved key`() {
        assertEquals(SocialPresence.ONLINE, SocialPresence.from(null))
        assertEquals(SocialPresence.ONLINE, SocialPresence.from("nonsense"))
        assertEquals(SocialPresence.INVISIBLE, SocialPresence.from("invisible"))
        assertEquals(SocialPresence.OFFLINE, SocialPresence.from(SocialPresence.OFFLINE.key))
    }
}
