package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.obj
import java.net.InetAddress
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The host APIs a contained plugin lives on (docs/plugin-api.md 3 D1 to D5, H1), through the broker's checks. */
class HostApisTest {
    private fun request(api: String, op: String, args: JSONObject = JSONObject()) =
        obj("api" to api, "version" to 1, "op" to op, "args" to args).toString()

    private fun plugin(vararg permissions: JSONObject) = TestPlugins.record(
        TestPlugins.manifest(id = "acme.net") { it.put("permissions", JSONArray(permissions.toList())) },
    )

    private val domains = obj("id" to "net.domains", "domains" to JSONArray(listOf("api.acme.example", "*.cdn.example")))

    private fun call(env: FakeEnv, api: String, op: String, args: JSONObject) = PluginReply.parse(BrokerCore("acme.net", env).call(request(api, op, args)))

    @Test
    fun `the url alone decides which network permission a request needs`() {
        val declared = plugin(domains).manifest.v2.permissions
        assertEquals("net.domains", NetScope.permissionFor(declared, "https://api.acme.example/v1"))
        assertEquals("net.domains", NetScope.permissionFor(declared, "https://img.cdn.example/a.png"))
        assertEquals("a wildcard does not cover the bare domain", "net.any", NetScope.permissionFor(declared, "https://cdn.example/"))
        assertEquals("net.any", NetScope.permissionFor(declared, "https://evil.example/"))
        assertEquals("net.local", NetScope.permissionFor(declared, "http://192.168.1.20:8080/"))
        assertEquals("net.local", NetScope.permissionFor(declared, "http://nas.local/"))
        assertEquals("net.local", NetScope.permissionFor(declared, "http://localhost/"))
        assertEquals("net.local", NetScope.permissionFor(declared, "http://[fd00::1]/"))
        assertNull(NetScope.permissionFor(declared, "file:///data/data/dev.droidtop/files"))
        assertTrue(NetScope.isLocalAddress(InetAddress.getByName("100.64.0.1")))
        assertFalse(NetScope.isLocalAddress(InetAddress.getByName("93.184.216.34")))
    }

    @Test
    fun `a request to a declared domain goes out and is logged with its host`() {
        val env = FakeEnv(plugin(domains))
        val r = call(env, "net", "http", obj("url" to "https://api.acme.example/v1/status"))
        assertTrue(r.message, r.ok)
        assertEquals(200, r.data.getInt("status"))
        assertEquals("{\"a\":1}", r.data.getString("body"))
        val (who, entry) = env.audits.single()
        assertEquals("acme.net", who)
        assertEquals("net.domains", entry.permission)
        assertEquals("api.acme.example", entry.target)
    }

    @Test
    fun `anything beyond the declared domains needs net any, which is asked for`() {
        val env = FakeEnv(plugin(domains))
        val refused = call(env, "net", "http", obj("url" to "https://evil.example/"))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, refused.code)
        assertTrue(env.httpCalls.isEmpty())

        val withAny = FakeEnv(TestPlugins.record(TestPlugins.manifest(id = "acme.net") { it.put("permissions", JSONArray(listOf(domains, obj("id" to "net.any")))) }))
        val background = call(withAny, "net", "http", obj("url" to "https://evil.example/"))
        assertEquals("dangerous and not answered: refused in the background", PluginErrorCode.PERMISSION_DENIED, background.code)
        assertEquals(listOf("acme.net" to "net.any"), withAny.wanted)
        withAny.user = true
        assertTrue(call(withAny, "net", "http", obj("url" to "https://evil.example/")).ok)
        assertEquals(1, withAny.prompts.size)
    }

    @Test
    fun `every redirect hop is checked again`() {
        val env = FakeEnv(plugin(domains))
        env.redirectTo = "https://evil.example/collect"
        val r = call(env, "net", "http", obj("url" to "https://api.acme.example/v1"))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, r.code)
        assertEquals(listOf("https://api.acme.example/v1"), env.hops)
    }

    @Test
    fun `the internet is reached over https only, and a declared name on the local network needs net local`() {
        val env = FakeEnv(plugin(domains))
        assertEquals(PluginErrorCode.INVALID_ARGS, call(env, "net", "http", obj("url" to "http://api.acme.example/")).code)
        env.resolved = mapOf("api.acme.example" to listOf(InetAddress.getByName("10.0.0.5")))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, call(env, "net", "http", obj("url" to "https://api.acme.example/")).code)
        assertEquals(PluginErrorCode.PERMISSION_DENIED, call(env, "net", "http", obj("url" to "http://192.168.1.20/")).code)
    }

    @Test
    fun `a plugin's data stays inside its own folder`() {
        val env = FakeEnv(plugin())
        assertTrue(call(env, "data", "write", obj("name" to "settings/main.json", "text" to "{\"greeting\":\"hi\"}")).ok)
        val read = call(env, "data", "read", obj("name" to "settings/main.json"))
        assertEquals("{\"greeting\":\"hi\"}", read.data.getString("text"))
        assertTrue(read.data.getBoolean("eof"))
        assertEquals("settings/main.json", call(env, "data", "list", JSONObject()).data.getJSONArray("files").getJSONObject(0).getString("name"))
        for (bad in listOf("../escape", "/etc/passwd", "a//b", "a/./b", "")) {
            assertEquals(bad, PluginErrorCode.INVALID_ARGS, call(env, "data", "write", obj("name" to bad, "text" to "x")).code)
        }
        assertTrue(call(env, "data", "delete", obj("name" to "settings/main.json")).data.getBoolean("deleted"))
        assertEquals(PluginErrorCode.NOT_FOUND, call(env, "data", "read", obj("name" to "settings/main.json")).code)
    }

    @Test
    fun `the data limit stops a write that would pass it`() {
        val store = PluginDataStore(kotlin.io.path.createTempDirectory("quota").toFile())
        try {
            store.requireRoom(PluginDataStore.LIMIT_BYTES + 1)
            throw AssertionError("expected a refusal")
        } catch (e: BrokerException) {
            assertEquals(PluginErrorCode.RATE_LIMITED, e.code)
        }
    }

    @Test
    fun `the file picker opens only while the person is using the plugin`() {
        val env = FakeEnv(plugin(obj("id" to "files.picker")))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, call(env, "files", "pick", JSONObject()).code)
    }

    @Test
    fun `shared files are reachable only inside the declared folders`() {
        val read = DeclaredPermission("files.shared.read", extra = obj("paths" to JSONArray(listOf("Download/acme"))).toString())
        assertNull(SharedFiles.scopeRefusal(read, "Download/acme/notes.txt"))
        assertNull(SharedFiles.scopeRefusal(read, "/Download/acme/"))
        assertTrue(SharedFiles.scopeRefusal(read, "Download/acme-other/x") != null)
        assertTrue(SharedFiles.scopeRefusal(read, "Download/acme/../../DCIM/a.jpg") != null)
        val all = DeclaredPermission("files.shared.read", extra = obj("paths" to JSONArray(listOf("*"))).toString())
        assertNull(SharedFiles.scopeRefusal(all, "DCIM/a.jpg"))
        assertTrue("other apps' private folders never", SharedFiles.scopeRefusal(all, "Android/data/com.other/files/x") != null)
        assertTrue(SharedFiles.scopeRefusal(all, "android/OBB/x") != null)
    }

    @Test
    fun `a link out of the volume is refused after it is followed`() {
        val volume = kotlin.io.path.createTempDirectory("volume").toFile()
        val outside = kotlin.io.path.createTempDirectory("outside").toFile()
        java.nio.file.Files.createSymbolicLink(java.io.File(volume, "link").toPath(), outside.toPath())
        try {
            SharedFiles.resolve(volume, "link/secret.txt")
            throw AssertionError("expected a refusal")
        } catch (e: BrokerException) {
            assertEquals(PluginErrorCode.PERMISSION_DENIED, e.code)
        }
        assertEquals(java.io.File(volume, "Download/a.txt"), SharedFiles.resolve(volume, "Download/a.txt"))
    }

    @Test
    fun `shared files need droidtop's own all files access first`() {
        val env = FakeEnv(plugin(obj("id" to "files.shared.read", "paths" to JSONArray(listOf("*")))))
        env.user = true
        val r = call(env, "files.shared", "list", obj("path" to "Download"))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, r.code)
        assertTrue(r.message!!.contains("all files"))
    }

    @Test
    fun `an op that hands over a file is not answered as json, and the reverse`() {
        val env = FakeEnv(plugin())
        assertEquals(PluginErrorCode.UNSUPPORTED, call(env, "data", "open", obj("name" to "a")).code)
        val (text, fd) = BrokerCore("acme.net", env).open(request("data", "list"))
        assertNull(fd)
        assertEquals(PluginErrorCode.UNSUPPORTED, PluginReply.parse(text).code)
    }

    @Test
    fun `a finished file takes its final name inside the plugin's data, and its path is droidtop's to give`() {
        val env = FakeEnv(plugin())
        assertTrue(call(env, "data", "write", obj("name" to "cores/x.so.part", "text" to "elf")).ok)
        assertTrue(call(env, "data", "move", obj("from" to "cores/x.so.part", "to" to "cores/x.so")).data.getBoolean("moved"))
        assertEquals("elf", call(env, "data", "read", obj("name" to "cores/x.so")).data.getString("text"))
        assertEquals(PluginErrorCode.INVALID_ARGS, call(env, "data", "move", obj("from" to "cores/x.so", "to" to "../x.so")).code)
        val path = call(env, "data", "path", obj("name" to "cores/x.so")).data.getString("path")
        assertTrue(path.endsWith("/acme.net/cores/x.so"))
    }

    @Test
    fun `a plugin can stop only its own job`() {
        val env = FakeEnv(plugin())
        assertFalse(call(env, "plugins", "job_cancel", obj("jobId" to "someone-elses")).data.getBoolean("cancelled"))
    }
}
