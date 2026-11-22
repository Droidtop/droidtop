package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** docs/plugin-api.md 3 G3a: droidtop's pages shown in an app that provides web.view, with droidtop keeping the session. */
class WebViewProvidersTest {
    private fun app(provides: String = "web.view") = TestPlugins.record(
        PluginManifest.fromJson(
            JSONObject(
                """{"id": "dev.droidtop.browser", "origin": "droidtop", "label": "droidtop Browser", "version": "1", "kind": "app_interface",
                   "contractVersion": 3,
                   "app": {"package": "dev.droidtop.browser", "service": "dev.droidtop.browser.DroidtopInterfaceService"},
                   "provides": [{"point": "$provides", "version": 1}]}""",
            ),
        )!!,
    )

    private val results = mutableListOf<WebSessionResult>()

    @Before
    fun answerAtOnce() {
        WebViewProviders.deliver = { it() }
    }

    @After
    fun forget() {
        results.clear()
    }

    private fun request(mode: WebSessionRequest.Mode, max: Int = 1, doneCookie: String? = null) = WebSessionRequest(
        label = "F95zone",
        mode = mode,
        url = "https://f95zone.to/threads/1/",
        domains = listOf("f95zone.to"),
        session = WebSessions.Stored(userAgent = "old", cookies = mapOf("f95zone.to" to "xf_user=1", "elsewhere.example" to "x=1")),
        doneCookie = doneCookie,
        maxDownloads = max,
    )

    private fun call(record: PluginRecord, op: String, args: JSONObject, finish: ((JSONObject) -> Boolean)? = null): PluginReply {
        val env = object : BrokerEnvironment by FakeEnv(record) {
            override fun webViewFinish(pluginId: String, token: String, args: JSONObject): Boolean? =
                finish?.invoke(args) ?: WebViewProviders.finish(null, pluginId, token, args)
        }
        return PluginReply.parse(
            BrokerCore(record.manifest.id, env).call(
                JSONObject().put("api", "web.view").put("version", 1).put("op", op).put("args", args).toString(),
            ),
        )
    }

    @Test
    fun `web view is a point that needs consent and its ops follow the naming rule`() {
        assertEquals(PointRisk.HIGH, ExtensionPoints.find("web.view")!!.risk)
        for (op in listOf("take_request", "report_page", "finish")) {
            assertNull(op, HostOpNames.check(HostApis.find("web.view", op)!!.op))
        }
    }

    @Test
    fun `the app takes a page once, with the cookies of its sites and droidtop's download sites only`() {
        val token = WebViewProviders.register("dev.droidtop.browser", request(WebSessionRequest.Mode.SIGN_IN), mapOf("mega.nz" to "sid=2")) { results += it }
        val reply = call(app(), "take_request", JSONObject().put("token", token))
        assertTrue(reply.message, reply.ok)
        assertEquals("sign_in", reply.data.getString("mode"))
        assertEquals("https://f95zone.to/threads/1/", reply.data.getString("url"))
        val cookies = reply.data.getJSONObject("cookies")
        assertEquals("xf_user=1", cookies.getString("f95zone.to"))
        assertEquals("sid=2", cookies.getString("mega.nz"))
        assertFalse(cookies.has("elsewhere.example"))
        assertFalse(call(app(), "take_request", JSONObject().put("token", token)).ok)
    }

    @Test
    fun `a page given to one app is refused to another, and a plugin that shows no pages is refused`() {
        val token = WebViewProviders.register("someone.else", request(WebSessionRequest.Mode.CHECK), emptyMap()) { results += it }
        assertFalse(call(app(), "take_request", JSONObject().put("token", token)).ok)
        val notViewer = call(app(provides = "engines.cores"), "take_request", JSONObject().put("token", token))
        assertFalse(notViewer.ok)
        assertEquals(PluginErrorCode.PERMISSION_DENIED, notViewer.code)
    }

    @Test
    fun `droidtop decides when a check or a sign-in is done`() {
        val check = WebViewProviders.register("dev.droidtop.browser", request(WebSessionRequest.Mode.CHECK), emptyMap()) { results += it }
        call(app(), "take_request", JSONObject().put("token", check))
        fun page(token: String, title: String, cookies: String, signedIn: Boolean? = null) = call(
            app(), "report_page",
            JSONObject().put("token", token).put("url", "https://f95zone.to/").put("title", title).put("cookies", cookies)
                .apply { if (signedIn != null) put("signedIn", signedIn) },
        ).data.getBoolean("finish")
        assertFalse(page(check, "Just a moment...", "__cf_bm=1"))
        assertTrue(page(check, "F95zone", "cf_clearance=abc"))
        assertFalse("a check on a site the plugin signs in to waits for the sign-in", page(check, "F95zone", "cf_clearance=abc", signedIn = false))

        val signIn = WebViewProviders.register("dev.droidtop.browser", request(WebSessionRequest.Mode.SIGN_IN, doneCookie = "xf_user"), emptyMap()) { results += it }
        call(app(), "take_request", JSONObject().put("token", signIn))
        assertFalse(page(signIn, "Log in", ""))
        assertTrue(page(signIn, "F95zone", "xf_user=9", signedIn = true))
    }

    @Test
    fun `the app's answer keeps only the request's sites and the downloads it started`() {
        val token = WebViewProviders.register("dev.droidtop.browser", request(WebSessionRequest.Mode.OPEN, max = 2), emptyMap()) { results += it }
        call(app(), "take_request", JSONObject().put("token", token))
        val reply = call(
            app(), "finish",
            JSONObject().put("token", token).put("completed", true).put("userAgent", "Gecko/160")
                .put("cookies", JSONObject().put("f95zone.to", "xf_user=1; cf_clearance=z").put("evil.example", "steal=1"))
                .put(
                    "downloads",
                    JSONArray()
                        .put(JSONObject().put("url", "https://files.example/a.zip").put("size", 10))
                        .put(JSONObject().put("url", "blob:https://files.example/x"))
                        .put(JSONObject().put("url", "https://files.example/a.zip"))
                        .put(JSONObject().put("url", "https://files.example/b.zip"))
                        .put(JSONObject().put("url", "https://files.example/c.zip")),
                ),
        )
        assertTrue(reply.message, reply.ok)
        val result = results.single()
        assertTrue(result.completed)
        assertEquals("Gecko/160", result.session!!.userAgent)
        assertEquals(mapOf("f95zone.to" to "xf_user=1; cf_clearance=z"), result.session!!.cookies)
        assertEquals(listOf("https://files.example/a.zip", "https://files.example/b.zip"), result.downloads.map { it.url })
        assertEquals(10L, result.download!!.size)
        assertFalse("a page is answered once", call(app(), "finish", JSONObject().put("token", token)).ok)
    }
}
