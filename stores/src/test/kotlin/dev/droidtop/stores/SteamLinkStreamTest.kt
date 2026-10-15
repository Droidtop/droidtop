package dev.droidtop.stores

import dev.droidtop.net.peer.Computer
import dev.droidtop.stores.steam.SteamLinkStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Stream with Steam Link": the words for each answer of the computer's agent, and which computer is asked (docs/SPEC.md 7o). */
class SteamLinkStreamTest {
    private fun pc(name: String, primary: Boolean = false, lastSyncMs: Long = 0) =
        Computer(id = name.padEnd(64, '0'), name = name, addresses = emptyList(), pairedAtMs = 0, primary = primary, lastSyncMs = lastSyncMs)

    private fun say(reply: String) = SteamLinkStream.describe(JSONObject(reply), "DESKTOP", "Portal", "Retroid")

    @Test
    fun `started is the only answer that opens Steam Link`() {
        assertTrue(say("""{"result":"started"}""").started)
        listOf(
            """{"result":"not_allowed"}""",
            """{"result":"unknown_game"}""",
            """{"result":"steam_not_running"}""",
            """{"result":"failed","message":"x"}""",
            """{"result":"agent_too_old"}""",
            """{"error":"boom"}""",
            """{"unreachable":"it did not answer on this network"}""",
            """{"result":"something_new"}""",
        ).forEach { assertFalse(it, say(it).started) }
    }

    @Test
    fun `each failure names the computer and the next step`() {
        assertTrue(say("""{"result":"not_allowed"}""").line.let { "DESKTOP" in it && "Retroid" in it && "Start games" in it })
        assertTrue("Update it on the computer" in say("""{"result":"agent_too_old"}""").line)
        assertTrue("Start Steam there and sign in" in say("""{"result":"steam_not_running"}""").line)
        assertTrue("Portal" in say("""{"result":"unknown_game"}""").line)
        assertTrue("it exploded" in say("""{"result":"failed","message":"it exploded"}""").line)
        assertTrue("did not answer on this network" in say("""{"unreachable":"it did not answer on this network"}""").line)
    }

    @Test
    fun `only an unreachable computer lets the next one be asked`() {
        assertFalse(say("""{"unreachable":"no"}""").definitive)
        listOf("""{"result":"not_allowed"}""", """{"result":"started"}""", """{"error":"e"}""").forEach { assertTrue(say(it).definitive) }
    }

    @Test
    fun `the primary computer is asked first, then the most recent`() {
        val ordered = SteamLinkStream.order(listOf(pc("old", lastSyncMs = 1), pc("new", lastSyncMs = 9), pc("main", primary = true, lastSyncMs = 0)))
        assertEquals(listOf("main", "new", "old"), ordered.map { it.name })
    }

    @Test
    fun `an offline computer is skipped for one that answers, and all offline says so`() {
        val asked = mutableListOf<String>()
        val answers = mapOf("a" to """{"unreachable":"no route"}""", "b" to """{"result":"started"}""")
        val (who, outcome) = SteamLinkStream.ask(listOf(pc("a"), pc("b")), "Portal", "Retroid") { asked += it.name; JSONObject(answers.getValue(it.name)) }!!
        assertEquals(listOf("a", "b"), asked)
        assertEquals("b", who.name)
        assertTrue(outcome.started)

        val (_, none) = SteamLinkStream.ask(listOf(pc("a")), "Portal", "Retroid") { JSONObject(answers.getValue("a")) }!!
        assertFalse(none.started)
        assertTrue("no route" in none.line)
    }

    @Test
    fun `a definitive refusal stops the asking`() {
        val asked = mutableListOf<String>()
        SteamLinkStream.ask(listOf(pc("a"), pc("b")), "Portal", "Retroid") { asked += it.name; JSONObject("""{"result":"not_allowed"}""") }
        assertEquals(listOf("a"), asked)
    }
}
