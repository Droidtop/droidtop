package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * droidtop's side of the web.view contract (docs/contracts/web-view.json, docs/plugin-api.md 3 G3a). droidtop Browser's CI
 * checks its adapter against the same file, so the two sides cannot drift apart without one of them failing.
 */
class WebViewProvidersContractTest {
    private val contract: JSONObject = JSONObject(
        javaClass.classLoader!!.getResourceAsStream("web-view.json")!!.use { it.readBytes().toString(Charsets.UTF_8) },
    )

    private fun JSONArray.strings(): Set<String> = (0 until length()).map { getString(it) }.toSet()
    private fun op(name: String) = contract.getJSONObject("ops").getJSONObject(name)

    private val app = TestPlugins.record(
        PluginManifest.fromJson(
            JSONObject(
                """{"id": "dev.droidtop.browser", "origin": "droidtop-browser", "label": "droidtop Browser", "version": "1",
                   "kind": "app_interface", "contractVersion": 3,
                   "app": {"package": "dev.droidtop.browser", "service": "dev.droidtop.browser.DroidtopInterfaceService"},
                   "provides": [{"point": "web.view", "version": 1}]}""",
            ),
        )!!,
    )

    @Before
    fun answerAtOnce() {
        WebViewProviders.deliver = { it() }
    }

    @Test
    fun `the point, intent and every op are the contract's`() {
        assertEquals(contract.getString("point"), WebViewProviders.POINT)
        assertTrue(ExtensionPoints.supports(WebViewProviders.POINT, contract.getInt("version")))
        assertEquals(contract.getJSONObject("intent").getString("action"), WebViewProviders.ACTION)
        assertEquals(contract.getJSONObject("intent").getString("extraToken"), WebViewProviders.EXTRA_TOKEN)
        val api = contract.getString("api")
        for (name in contract.getJSONObject("ops").keys()) assertNotNull("$api.$name", HostApis.find(api, name))
        assertEquals(contract.getJSONObject("ops").keys().asSequence().toSet(), HostApis.ops.filter { it.api == api }.map { it.op }.toSet())
    }

    @Test
    fun `take_request answers with exactly the contract's keys, in one of its modes`() {
        for (mode in listOf(WebSessionRequest.Mode.SIGN_IN, WebSessionRequest.Mode.CHECK, WebSessionRequest.Mode.OPEN)) {
            val request = WebSessionRequest("Site", mode, "https://site.example/", listOf("site.example"), null, doneCookie = "u", signInUrl = "https://site.example/login")
            val token = WebViewProviders.register(app.manifest.id, request, emptyMap()) {}
            val reply = WebViewProviders.take(app.manifest.id, token)
            assertEquals(op("take_request").getJSONArray("reply").strings(), reply.keys().asSequence().toSet())
            assertTrue(reply.getString("mode") in contract.getJSONArray("modes").strings())
        }
    }

    @Test
    fun `report_page and finish read the contract's args and answer its reply keys`() {
        val request = WebSessionRequest("Site", WebSessionRequest.Mode.OPEN, "https://site.example/", listOf("site.example"), null, doneCookie = null, maxDownloads = 2)
        val token = WebViewProviders.register(app.manifest.id, request, emptyMap()) {}
        WebViewProviders.take(app.manifest.id, token)
        val page = WebViewProviders.page(app.manifest.id, token, "https://site.example/", "Site", "a=1", null)
        assertEquals(op("report_page").getJSONArray("reply").strings(), page.keys().asSequence().toSet())

        // Every download field the contract names is read: a download carrying all of them keeps each one.
        val fields = contract.getJSONArray("download").strings()
        val download = JSONObject().apply {
            fields.forEach { key -> put(key, if (key == "size") 12L else if (key == "url") "https://files.example/a.zip" else "v-$key") }
        }
        val parsed = WebViewProviders.downloadsOf(JSONArray().put(download), 2).single()
        assertEquals("https://files.example/a.zip", parsed.url)
        assertEquals(12L, parsed.size)
        assertEquals("v-mimeType", parsed.mimeType)
        assertEquals("v-contentDisposition", parsed.contentDisposition)
        assertEquals("v-referer", parsed.referer)
        assertEquals("v-cookie", parsed.cookie)
        assertEquals("v-userAgent", parsed.userAgent)
        assertEquals(setOf("url", "size", "mimeType", "contentDisposition", "referer", "cookie", "userAgent"), fields)

        val args = JSONObject().put("token", token).put("completed", true).put("userAgent", "UA")
            .put("cookies", JSONObject().put("site.example", "a=1")).put("downloads", JSONArray().put(download))
        assertEquals(op("finish").getJSONArray("args").strings(), args.keys().asSequence().toSet())
        assertTrue(WebViewProviders.finish(null, app.manifest.id, token, args))
    }
}
