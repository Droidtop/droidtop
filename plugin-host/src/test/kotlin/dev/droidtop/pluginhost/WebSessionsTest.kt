package dev.droidtop.pluginhost

import dev.droidtop.net.SecretCipher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A plugin's signed-in web session (docs/plugin-api.md 3 G3) without a web view: the broker's side and the rules. */
class WebSessionsTest {
    private object Reversing : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }

    private val webUser = TestPlugins.record(
        TestPlugins.manifest(id = "acme.web") {
            it.put(
                "permissions",
                JSONArray()
                    .put(JSONObject().put("id", "web.session").put("domains", JSONArray().put("forum.example")))
                    .put(JSONObject().put("id", "vault.own")),
            )
        },
    )

    private fun env(answer: (WebSessionRequest) -> WebSessionResult?): Pair<FakeEnv, BrokerEnvironment> {
        val fake = FakeEnv(webUser)
        fake.user = true
        fake.states["acme.web"] = mutableMapOf("web.session" to GrantState.GRANTED, "vault.own" to GrantState.GRANTED)
        fake.vaultStore = PluginVault(fake.vaultDir, { Reversing })
        return fake to object : BrokerEnvironment by fake {
            override fun webSession(request: WebSessionRequest): WebSessionResult? = answer(request)
        }
    }

    private fun call(env: BrokerEnvironment, api: String, op: String, args: JSONObject = JSONObject()) =
        PluginReply.parse(BrokerCore("acme.web", env).call(JSONObject().put("api", api).put("version", 1).put("op", op).put("args", args).toString()))

    @Test
    fun `declared sites cover themselves and their subdomains, and only https is in a session`() {
        val declared = listOf(DeclaredPermission("web.session", extra = """{"domains":["forum.example","*.files.example","bad/path"]}"""))
        assertEquals(listOf("forum.example", "files.example"), WebSessions.declaredDomains(declared))
        val domains = WebSessions.declaredDomains(declared)
        assertEquals("forum.example", WebSessions.domainFor("https://attachments.forum.example/x", domains))
        assertNull(WebSessions.domainFor("http://forum.example/", domains))
        assertNull(WebSessions.domainFor("https://evilforum.example/", domains))
        assertNull(WebSessions.domainFor("https://forum.example.evil/", domains))
    }

    @Test
    fun `a site's file name becomes one the download job accepts`() {
        assertEquals("Some_Game_v0.9.5_pc.zip", WebSessions.safeFileName("Some Game (v0.9.5) pc.zip"))
        assertEquals("passwd", WebSessions.safeFileName("../../etc/passwd"))
        assertEquals("download", WebSessions.safeFileName("..."))
        assertEquals("download", WebSessions.safeFileName(null))
        assertEquals("Game v1.zip", WebSessions.fileNameFor("https://x.example/get?id=1", "attachment; filename=\"Game v1.zip\""))
        assertEquals("Spiel ü.7z", WebSessions.fileNameFor("https://x.example/a", "attachment; filename*=UTF-8''Spiel%20%C3%BC.7z"))
        assertEquals("file.rar", WebSessions.fileNameFor("https://x.example/dl/file.rar", null))
    }

    @Test
    fun `a sign-in is sealed in the vault where the plugin can neither read nor list it`() {
        val (fake, env) = env { WebSessionResult(true, WebSessions.Stored("UA", mapOf("forum.example" to "xf_user=abc")), null) }
        assertFalse(call(env, "web.session", "status").data.getBoolean("signedIn"))
        assertTrue(call(env, "web.session", "sign_in", JSONObject().put("url", "https://forum.example/login/")).data.getBoolean("signedIn"))
        assertTrue(call(env, "web.session", "status").data.getBoolean("signedIn"))
        assertEquals(0, call(env, "vault", "keys").data.getJSONArray("keys").length())
        assertEquals(PluginErrorCode.INVALID_ARGS, call(env, "vault", "get", JSONObject().put("key", WebSessions.VAULT_KEY)).code)
        assertFalse(java.io.File(fake.vaultDir, "acme.web.json").readText().contains("xf_user=abc"))
        assertTrue(call(env, "web.session", "clear").data.getBoolean("cleared"))
        assertFalse(call(env, "web.session", "status").data.getBoolean("signedIn"))
    }

    @Test
    fun `the account's name is kept with the session for the account row, and a new sign-in forgets it`() {
        val (_, env) = env { WebSessionResult(true, WebSessions.Stored("UA", mapOf("forum.example" to "xf_user=abc")), null) }
        // Nothing to name before a sign-in.
        assertFalse(call(env, "web.session", "set_account", JSONObject().put("name", "Reader")).data.getBoolean("saved"))
        call(env, "web.session", "sign_in", JSONObject().put("url", "https://forum.example/login/"))
        assertTrue(call(env, "web.session", "set_account", JSONObject().put("name", "  Reader  ")).data.getBoolean("saved"))
        assertEquals("Reader", call(env, "web.session", "get_status").data.getString("account"))
        assertEquals(PluginErrorCode.INVALID_ARGS, call(env, "web.session", "set_account", JSONObject().put("name", "two\nlines")).code)
        assertEquals(PluginErrorCode.INVALID_ARGS, call(env, "web.session", "set_account", JSONObject().put("name", "x".repeat(65))).code)
        call(env, "web.session", "sign_in", JSONObject().put("url", "https://forum.example/login/"))
        assertTrue(call(env, "web.session", "get_status").data.isNull("account"))
    }

    @Test
    fun `a stored session reads back with its account, and an old one without`() {
        val stored = WebSessions.Stored("UA", mapOf("forum.example" to "a=b"), "Reader")
        assertEquals(stored, WebSessions.Stored.parse(stored.toJson()))
        assertEquals(null, WebSessions.Stored.parse("""{"ua":null,"cookies":{"forum.example":"a=b"}}""")?.account)
    }

    @Test
    fun `sign-in only opens the sites the plugin declared, and backing out stores nothing`() {
        val (_, env) = env { WebSessionResult(false, WebSessions.Stored(null, mapOf("forum.example" to "a=b")), null) }
        assertEquals(PluginErrorCode.INVALID_ARGS, call(env, "web.session", "sign_in", JSONObject().put("url", "https://other.example/login")).code)
        assertFalse(call(env, "web.session", "sign_in", JSONObject().put("url", "https://forum.example/login")).data.getBoolean("signedIn"))
        assertFalse(call(env, "web.session", "status").data.getBoolean("signedIn"))
    }

    @Test
    fun `a captured download is handed to the plugin as a token, claimed once, by that plugin, for that address`() {
        val download = WebSessionDownload("https://files.example/get/1", "UA", "attachment; filename=\"Game v1.zip\"", "application/zip", 42L, "https://forum.example/t/1", "s=1")
        val (fake, env) = env { WebSessionResult(true, null, download) }
        assertEquals(PluginErrorCode.INVALID_ARGS, call(env, "web.session", "open_in_session", JSONObject().put("url", "http://files.example/x")).code)
        val reply = call(env, "web.session", "open_in_session", JSONObject().put("url", "https://forum.example/masked/1"))
        assertTrue(reply.data.getBoolean("captured"))
        val token = reply.data.getJSONObject("download").getString("session")
        assertFalse(reply.data.toString().contains("s=1"))
        assertNull(WebSessions.take("other.plugin", token, download.url, fake.now))
        assertNull(WebSessions.take("acme.web", token, "https://files.example/get/2", fake.now))
        val taken = WebSessions.take("acme.web", token, download.url, fake.now)!!
        assertEquals("s=1", taken.headers["Cookie"])
        assertEquals("https://forum.example/t/1", taken.headers["Referer"])
        assertEquals("Game_v1.zip", taken.fileName)
        assertNull(WebSessions.take("acme.web", token, download.url, fake.now))
    }

    @Test
    fun `a page that offers several files is taken in one visit, each a token of its own`() {
        val first = WebSessionDownload("https://files.example/get/1", "UA", "attachment; filename=\"Game.part1.rar\"", null, 10L, "https://forum.example/t/1", "s=1")
        val second = WebSessionDownload("https://files.example/get/2", "UA", "attachment; filename=\"Game.part2.rar\"", null, 10L, "https://forum.example/t/1", "s=1")
        var asked: WebSessionRequest? = null
        val (_, env) = env { request -> asked = request; WebSessionResult(true, null, first, listOf(first, second)) }
        val reply = call(env, "web.session", "open_in_session", JSONObject().put("url", "https://forum.example/masked/1").put("max", 4))
        assertEquals(4, asked!!.maxDownloads)
        val downloads = reply.data.getJSONArray("downloads")
        assertEquals(2, downloads.length())
        assertEquals("Game.part2.rar", downloads.getJSONObject(1).getString("fileName"))
        assertTrue(downloads.getJSONObject(0).getString("session") != downloads.getJSONObject(1).getString("session"))
        assertEquals(downloads.getJSONObject(0).toString(), reply.data.getJSONObject("download").toString())
        // At most the acquire reply's limit, and one when the plugin asks for none.
        call(env, "web.session", "open_in_session", JSONObject().put("url", "https://forum.example/masked/1").put("max", 99))
        assertEquals(HostWebApis.MAX_CAPTURED, asked!!.maxDownloads)
        call(env, "web.session", "open_in_session", JSONObject().put("url", "https://forum.example/masked/1"))
        assertEquals(1, asked!!.maxDownloads)
    }

    @Test
    fun `the acquire reply names a capture by its token`() {
        val parsed = AcquireDownloadDescriptor.parse("""{"url":"https://files.example/a.zip","fileName":"a.zip","session":"w-00000000-0000-0000-0000-000000000000"}""")!!
        assertEquals("w-00000000-0000-0000-0000-000000000000", parsed.session)
        assertNull(AcquireDownloadDescriptor.parse("""{"url":"https://files.example/a.zip","fileName":"a.zip","session":"Cookie: x"}"""))
    }
}
