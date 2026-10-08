package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.arr
import dev.droidtop.pluginhost.TestPlugins.obj
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `library.files` op `changed` (docs/plugin-api.md 3 A2, docs/SPEC.md 7g "Targeted indexing", Droidtop/tracker#354):
 * a plugin that put a file in a game folder says so, and droidtop indexes exactly that. The broker's job is the
 * permission, the shape of the arguments and the scope: every path inside the person's game folders.
 */
class LibraryFilesApiTest {
    private val roots = listOf("/storage/games", "/sdcard/ROMs")

    private fun request(args: JSONObject) = obj("api" to "library.files", "version" to 1, "op" to "changed", "args" to args).toString()

    private fun reply(text: String) = PluginReply.parse(text)

    private fun paths(vararg p: String) = JSONArray(p.toList())

    private fun writer(declared: Boolean = true) = TestPlugins.record(
        TestPlugins.manifest(id = "acme.writer", label = "Writer") {
            if (declared) it.put("permissions", arr(obj("id" to "library.folders.write", "scope" to "destination")))
        },
    )

    private fun envWith(record: PluginRecord, granted: Boolean = true) = FakeEnv(record).also { env ->
        env.roots = roots
        if (granted) env.setGrant(record.manifest.id, "library.folders.write", GrantState.GRANTED)
    }

    @Test
    fun `a plugin with the grant reports files inside a game folder and droidtop is told exactly those`() {
        val env = envWith(writer())
        val args = obj(
            "added" to paths("/storage/games/snes/New Game.sfc", "/sdcard/ROMs/pc/Platformer"),
            "removed" to paths("/storage/games/snes/Old.sfc"),
            "changed" to paths("/storage/games/gba/Patched.gba"),
        )
        val r = reply(BrokerCore("acme.writer", env).call(request(args)))
        assertTrue(r.message, r.ok)
        assertTrue(r.data.getBoolean("accepted"))
        val (pluginId, change) = env.filesReports.single()
        assertEquals("acme.writer", pluginId)
        assertEquals(listOf("/storage/games/snes/New Game.sfc", "/sdcard/ROMs/pc/Platformer"), change.added)
        assertEquals(listOf("/storage/games/snes/Old.sfc"), change.removed)
        assertEquals(listOf("/storage/games/gba/Patched.gba"), change.changed)
    }

    @Test
    fun `one path outside the game folders refuses the whole call and droidtop hears nothing`() {
        val env = envWith(writer())
        val core = BrokerCore("acme.writer", env)
        val outside = listOf(
            "/storage/other/a.sfc",
            "/storage/games2/a.sfc",
            "/storage/games",
            "/data/data/dev.droidtop/files/secret",
            "/storage/games/../other/a",
            "relative/a.sfc",
        )
        for (path in outside) {
            val r = reply(core.call(request(obj("added" to paths("/storage/games/snes/fine.sfc", path)))))
            assertEquals(path, PluginErrorCode.PERMISSION_DENIED, r.code)
            assertTrue(path, r.message.orEmpty().contains("not inside your game folders"))
        }
        assertTrue("nothing, not even the path that was fine, reached the library", env.filesReports.isEmpty())
    }

    @Test
    fun `a plugin that never declared the permission, or whose permission is off, is refused`() {
        val undeclared = envWith(writer(declared = false))
        val r = reply(BrokerCore("acme.writer", undeclared).call(request(obj("added" to paths("/storage/games/a.sfc")))))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, r.code)

        val off = envWith(writer())
        off.setGrant("acme.writer", "library.folders.write", GrantState.DENIED)
        assertEquals(PluginErrorCode.PERMISSION_DENIED, reply(BrokerCore("acme.writer", off).call(request(obj("added" to paths("/storage/games/a.sfc"))))).code)

        val ask = envWith(writer(), granted = false)
        assertEquals("a call outside a user action cannot show the sheet", PluginErrorCode.PERMISSION_DENIED, reply(BrokerCore("acme.writer", ask).call(request(obj("added" to paths("/storage/games/a.sfc"))))).code)
        assertTrue(undeclared.filesReports.isEmpty() && off.filesReports.isEmpty() && ask.filesReports.isEmpty())
    }

    @Test
    fun `arguments that are not a list of paths are invalid, and so are none and too many`() {
        val env = envWith(writer())
        val core = BrokerCore("acme.writer", env)
        fun code(args: JSONObject) = reply(core.call(request(args))).code
        assertEquals(PluginErrorCode.INVALID_ARGS, code(obj()))
        assertEquals(PluginErrorCode.INVALID_ARGS, code(obj("added" to paths())))
        assertEquals(PluginErrorCode.INVALID_ARGS, code(obj("added" to "/storage/games/a.sfc")))
        assertEquals(PluginErrorCode.INVALID_ARGS, code(obj("added" to JSONArray(listOf(5)))))
        assertEquals(PluginErrorCode.INVALID_ARGS, code(obj("added" to paths(" "))))
        assertEquals(PluginErrorCode.INVALID_ARGS, code(obj("added" to paths("/storage/games/" + "a".repeat(HostApis.MAX_PATH_LENGTH)))))
        val many = JSONArray((0..HostApis.MAX_CHANGED_PATHS).map { "/storage/games/snes/$it.sfc" })
        assertEquals(PluginErrorCode.INVALID_ARGS, code(obj("added" to many)))
        val limit = JSONArray((1..HostApis.MAX_CHANGED_PATHS).map { "/storage/games/snes/$it.sfc" })
        assertTrue(reply(core.call(request(obj("added" to limit)))).ok)
        assertEquals(1, env.filesReports.size)
    }

    @Test
    fun `the call is on the host's list of apis and is a dangerous grant on the permission screen`() {
        assertTrue(HostApis.isHostApi("library.files"))
        assertEquals(PermissionTier.DANGEROUS, PluginPermissions.find("library.folders.write")!!.tier)
        val env = envWith(writer())
        val info = reply(BrokerCore("acme.writer", env).call(obj("api" to "host", "version" to 1, "op" to "info", "args" to obj()).toString()))
        assertTrue(info.data.getJSONObject("apis").has("library.files"))
    }
}
