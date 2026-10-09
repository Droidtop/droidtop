package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.arr
import dev.droidtop.pluginhost.TestPlugins.obj
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `retroarch.command` (docs/plugin-api.md 3 B, Droidtop/tracker#414 slice C10): RetroArch's network commands, sent by droidtop. */
class RetroArchCommandTest {
    private fun request(args: JSONObject) = obj("api" to "retroarch", "version" to 1, "op" to "command", "args" to args).toString()

    private val manager = TestPlugins.record(
        TestPlugins.manifest(id = "acme.ra") { it.put("permissions", arr(obj("id" to "retroarch.commands"))) },
    )

    private fun call(env: FakeEnv, command: String) = PluginReply.parse(BrokerCore("acme.ra", env).call(request(obj("command" to command))))

    @Test fun `a command goes out, then RetroArch's status says it answered`() {
        val env = FakeEnv(manager)
        val r = call(env, "SAVE_STATE")
        assertTrue(r.message, r.ok)
        assertEquals(listOf("SAVE_STATE", "GET_STATUS"), env.retroArchLines)
        assertTrue(r.data.getBoolean("answered"))
        assertEquals("PLAYING", r.data.getString("state"))
        assertEquals("snes", r.data.getString("system"))
        assertEquals("Super Metroid", r.data.getString("content"))
    }

    @Test fun `status asks RetroArch without sending a command`() {
        val env = FakeEnv(manager)
        val r = PluginReply.parse(BrokerCore("acme.ra", env).call(obj("api" to "retroarch", "version" to 1, "op" to "status", "args" to JSONObject()).toString()))
        assertTrue(r.message, r.ok)
        assertEquals(listOf("GET_STATUS"), env.retroArchLines)
        assertEquals("Super Metroid", r.data.getString("content"))
    }

    @Test fun `no answer is reported, not an error`() {
        val env = FakeEnv(manager)
        env.retroArchStatus = null
        val r = call(env, "LOAD_STATE")
        assertTrue(r.ok)
        assertFalse(r.data.getBoolean("answered"))
    }

    @Test fun `only RetroArch's in-game commands are sent, never quit or memory reads`() {
        val env = FakeEnv(manager)
        for (command in listOf("QUIT", "READ_CORE_MEMORY 0 4", "SAVE_STATE\nQUIT", "")) {
            assertEquals(command, PluginErrorCode.INVALID_ARGS, call(env, command).code)
        }
        assertTrue(env.retroArchLines.isEmpty())
    }

    @Test fun `a plugin that did not declare the permission sends nothing`() {
        val env = FakeEnv(TestPlugins.record(TestPlugins.manifest(id = "acme.ra")))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, call(env, "SAVE_STATE").code)
        assertTrue(env.retroArchLines.isEmpty())
    }

    @Test fun `GET_STATUS replies are read the way command_get_status writes them`() {
        assertEquals(RetroArchCommands.Status("PAUSED", "gba", "Metroid Fusion"), RetroArchCommands.parseStatus("GET_STATUS PAUSED gba,Metroid Fusion\n"))
        assertEquals(RetroArchCommands.Status("CONTENTLESS", null, null), RetroArchCommands.parseStatus("GET_STATUS CONTENTLESS"))
        assertNull(RetroArchCommands.parseStatus("VERSION 1.19.1"))
        assertNull(RetroArchCommands.parseStatus(null))
    }
}
