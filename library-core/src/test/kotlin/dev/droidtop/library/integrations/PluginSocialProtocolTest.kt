package dev.droidtop.library.integrations

import dev.droidtop.library.social.SocialLink
import dev.droidtop.library.social.SocialPresence
import dev.droidtop.library.social.SocialState
import dev.droidtop.pluginhost.PluginErrorCode
import dev.droidtop.pluginhost.PluginReply
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A plugin's `social.provider@1` replies as droidtop reads them (docs/plugin-api.md C19, Droidtop/tracker#327). */
class PluginSocialProtocolTest {
    private fun obj(vararg kv: Pair<String, Any?>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }

    @Test
    fun `account reads the connection, the name and the standing, and leaves out a standing it does not know`() {
        val a = PluginSocialProtocol.account(obj("link" to "online", "name" to "  Mech ", "presence" to "invisible"))
        assertEquals(SocialLink.ONLINE, a.link)
        assertEquals("Mech", a.name)
        assertEquals(SocialPresence.INVISIBLE, a.presence)
        val bare = PluginSocialProtocol.account(obj("link" to "sideways"))
        assertEquals(SocialLink.OFF, bare.link)
        assertNull(bare.name)
        assertNull("no standing means no status choice", bare.presence)
        assertNull(PluginSocialProtocol.account(obj("presence" to "dancing")).presence)
    }

    @Test
    fun `friends are checked, deduplicated, defaulted and sorted the lists' way`() {
        val list = JSONArray()
            .put(obj("id" to "1", "name" to "zed", "state" to "offline"))
            .put(obj("id" to "2", "name" to "amy", "state" to "in_game", "activity" to "Hades", "unread" to 2, "lastMessageMs" to 5L))
            .put(obj("id" to "2", "name" to "duplicate"))
            .put(obj("name" to "no id"))
            .put(obj("id" to "3", "state" to "napping", "unread" to -4))
            .put("not an object")
        val friends = PluginSocialProtocol.friends(obj("friends" to list))
        assertEquals(listOf("2", "1", "3").toSet(), friends.map { it.id }.toSet())
        assertEquals("amy", friends.first().name)
        assertEquals(SocialState.IN_GAME, friends.first().state)
        assertEquals("Hades", friends.first().activity)
        val unnamed = friends.first { it.id == "3" }
        assertEquals("Friend", unnamed.name)
        assertEquals(SocialState.OFFLINE, unnamed.state)
        assertEquals(0, unnamed.unread)
        assertTrue(PluginSocialProtocol.friends(obj()).isEmpty())
    }

    @Test
    fun `a provider cannot flood the list`() {
        val list = JSONArray()
        repeat(PluginSocialProtocol.MAX_FRIENDS + 50) { list.put(obj("id" to "f$it", "name" to "x".repeat(500))) }
        val friends = PluginSocialProtocol.friends(obj("friends" to list))
        assertEquals(PluginSocialProtocol.MAX_FRIENDS, friends.size)
        assertEquals(PluginSocialProtocol.MAX_NAME, friends.first().name.length)
    }

    @Test
    fun `a conversation keeps the newest messages in time order, one of each key, none without text`() {
        val list = JSONArray()
        repeat(PluginSocialProtocol.MAX_MESSAGES + 10) { list.put(obj("key" to "k$it", "text" to "m$it", "timeMs" to (1_000L + it))) }
        list.put(obj("key" to "k0", "text" to "again", "timeMs" to 1_000L))
        list.put(obj("key" to "blank", "text" to "   ", "timeMs" to 99_999L))
        list.put(obj("text" to "no key", "timeMs" to 99_999L))
        val messages = PluginSocialProtocol.messages(obj("messages" to list))
        assertEquals(PluginSocialProtocol.MAX_MESSAGES, messages.size)
        assertEquals("k10", messages.first().key)
        assertEquals("k${PluginSocialProtocol.MAX_MESSAGES + 9}", messages.last().key)
        assertTrue(messages.none { it.mine })
    }

    @Test
    fun `a sent message defaults to mine, and changed names its service, conversation and message`() {
        val sent = PluginSocialProtocol.message(obj("key" to "s1", "text" to "hi", "timeMs" to 7L), mineDefault = true)!!
        assertTrue(sent.mine)
        val change = PluginSocialProtocol.change(obj("service" to "work", "friendId" to "2", "message" to obj("key" to "m", "text" to "yo", "timeMs" to 3L)))
        assertEquals("work", change.service)
        assertEquals("2", change.friendId)
        assertEquals("yo", change.message?.text)
        val empty = PluginSocialProtocol.change(obj())
        assertNull(empty.service)
        assertNull(empty.friendId)
        assertNull(empty.message)
    }

    @Test
    fun `every call names its entry, and a refusal is said in the plugin's words or plainly`() {
        val args = PluginSocialProtocol.args("default", "friendId" to "2", "read" to true)
        assertEquals("default", args.getString("service"))
        assertEquals("2", args.getString("friendId"))
        assertEquals("Rate limited by the service", PluginSocialProtocol.failure(PluginReply.error(PluginErrorCode.FAILED, " Rate limited by the service "), "Acme"))
        assertEquals("Acme did not take the message", PluginSocialProtocol.failure(PluginReply.error(PluginErrorCode.FAILED, ""), "Acme"))
    }
}
