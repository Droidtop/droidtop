package dev.droidtop.library.computers

import dev.droidtop.net.peer.Computer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Starting a game on a computer: the words for each answer of the computer's agent, and which computer is asked (docs/SPEC.md 7o). */
class ComputerLaunchTest {
    private fun pc(name: String, lastSyncMs: Long = 0) =
        Computer(id = name.padEnd(64, '0'), name = name, addresses = emptyList(), pairedAtMs = 0, lastSyncMs = lastSyncMs)

    private fun say(reply: String, key: String = "steam:400") = ComputerLaunch.describe(JSONObject(reply), "DESKTOP", "Portal", "Retroid", key)

    @Test
    fun `started is the only answer that starts`() {
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
    fun `the computer met most recently is asked first`() {
        val ordered = ComputerLaunch.order(listOf(pc("old", lastSyncMs = 1), pc("new", lastSyncMs = 9), pc("never", lastSyncMs = 0)))
        assertEquals(listOf("new", "old", "never"), ordered.map { it.name })
    }

    @Test
    fun `an offline computer is skipped for one that answers, and all offline says so`() {
        val asked = mutableListOf<String>()
        val answers = mapOf("a" to """{"unreachable":"no route"}""", "b" to """{"result":"started"}""")
        val (who, outcome) = ComputerLaunch.ask(listOf(pc("a"), pc("b")), "Portal", "Retroid", "steam:400") { asked += it.name; JSONObject(answers.getValue(it.name)) }!!
        assertEquals(listOf("a", "b"), asked)
        assertEquals("b", who.name)
        assertTrue(outcome.started)

        val (_, none) = ComputerLaunch.ask(listOf(pc("a")), "Portal", "Retroid", "steam:400") { JSONObject(answers.getValue("a")) }!!
        assertFalse(none.started)
        assertTrue("no route" in none.line)
    }

    @Test
    fun `a definitive refusal stops the asking`() {
        val asked = mutableListOf<String>()
        ComputerLaunch.ask(listOf(pc("a"), pc("b")), "Portal", "Retroid", "steam:400") { asked += it.name; JSONObject("""{"result":"not_allowed"}""") }
        assertEquals(listOf("a"), asked)
    }

    @Test
    fun `a started game says how it is shown`() {
        assertEquals(ComputerLaunch.STREAM_WINDOWCAST, say("""{"result":"started","stream":"windowcast"}""", "pythia:12").stream)
        assertNull(say("""{"result":"started"}""").stream)
        assertNull(say("""{"result":"failed","message":"x","stream":"windowcast"}""").stream)
    }

    @Test
    fun `an unknown game that is not Steam's says which programs start such games`() {
        assertTrue("Pythia" in say("""{"result":"unknown_game"}""", "pythia:12").line)
        assertFalse("Pythia" in say("""{"result":"unknown_game"}""").line)
    }

    private fun pcAt(vararg addresses: String) = Computer(id = "ab".repeat(32), name = "DESKTOP", addresses = addresses.toList(), pairedAtMs = 0)

    @Test
    fun `the stream screen is asked for the computer, its windowcast address and the game's window`() {
        val extras = ComputerLaunch.streamExtras(pcAt("192.168.1.20:47610"), null, " Portal 2 ")!!
        assertEquals("ab".repeat(32), extras[ComputerLaunch.EXTRA_HOST_ID])
        assertEquals("the agent's sync port is not windowcast's", "192.168.1.20:47100", extras[ComputerLaunch.EXTRA_ADDRESS])
        assertEquals("Portal 2", extras[ComputerLaunch.EXTRA_WINDOW])
        assertNull("no window name is not an empty one", ComputerLaunch.streamExtras(pcAt("10.0.0.2:47610"), null, "  ")!![ComputerLaunch.EXTRA_WINDOW])
    }

    @Test
    fun `an address the answer gave wins, and a bad one is not used`() {
        assertEquals("203.0.113.5:5000", ComputerLaunch.streamExtras(pcAt("192.168.1.20:47610"), "203.0.113.5:5000", "g")!![ComputerLaunch.EXTRA_ADDRESS])
        assertEquals("192.168.1.20:47100", ComputerLaunch.streamExtras(pcAt("192.168.1.20:47610"), "not an address", "g")!![ComputerLaunch.EXTRA_ADDRESS])
        assertEquals("203.0.113.5:5000", say("""{"result":"started","stream":"windowcast","address":"203.0.113.5:5000"}""").streamAddress)
        assertNull(say("""{"result":"started","stream":"windowcast","address":"http://x/y"}""").streamAddress)
    }

    @Test
    fun `an IPv6 host is bracketed and a computer with no address names none`() {
        assertEquals("[2001:db8::7]:47100", ComputerLaunch.windowcastAddress(pcAt("[2001:db8::7]:47610"), null))
        assertNull(ComputerLaunch.streamExtras(pcAt(), null, "g"))
    }

    @Test
    fun `HOST_PORT is what the stream screen takes`() {
        assertTrue(ComputerLaunch.isHostPort("pc.local:47100"))
        assertTrue(ComputerLaunch.isHostPort("[::1]:1"))
        assertFalse(ComputerLaunch.isHostPort("pc:0"))
        assertFalse(ComputerLaunch.isHostPort("pc:65536"))
        assertFalse(ComputerLaunch.isHostPort("pc"))
        assertFalse(ComputerLaunch.isHostPort("a b:1"))
    }
}
