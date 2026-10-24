package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.obj
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Droidtop/tracker#600: a host that has shown a browser check is read by droidtop's web view, never its HTTP client. */
class WebFetchRoutingTest {
    private val record = TestPlugins.record(
        TestPlugins.manifest(id = "acme.checked") {
            it.put("permissions", JSONArray().put(obj("id" to "net.domains", "domains" to JSONArray(listOf("forum.example")))))
        },
    )

    private val challenge = HttpAnswer(403, "https://forum.example/threads/1/", mapOf("content-type" to "text/html", "cf-mitigated" to "challenge"), "<title>Just a moment...</title>".toByteArray(), false)

    private fun call(env: BrokerEnvironment, url: String) = PluginReply.parse(
        BrokerCore("acme.checked", env).call(obj("api" to "net", "version" to 1, "op" to "http", "args" to obj("url" to url)).toString()),
    )

    private fun withWebView(fake: FakeEnv, answer: (String) -> WebFetch.Result?): Pair<BrokerEnvironment, MutableList<String>> {
        val asked = mutableListOf<String>()
        return object : BrokerEnvironment by fake {
            override fun webFetch(url: String, cookies: String?, timeoutMs: Long, maxBytes: Int): WebFetch.Result? {
                asked += url
                return answer(url)
            }
        } to asked
    }

    @After fun forget() = SiteChecks.passed("acme.checked", "forum.example")

    @Test fun `after a check, a GET to that host is the web view's page and droidtop's client is not asked`() {
        val fake = FakeEnv(record).apply { httpAnswer = challenge }
        val (env, asked) = withWebView(fake) { url ->
            WebFetch.Result(HttpAnswer(200, url, mapOf("content-type" to "text/html; charset=utf-8"), "<html>thread</html>".toByteArray(), false), false, "cf_clearance=x")
        }
        assertEquals(PluginErrorCode.BROWSER_CHECK_REQUIRED, call(env, "https://forum.example/threads/1/").code)
        assertEquals(1, fake.httpCalls.size)

        val read = call(env, "https://forum.example/threads/2/")
        assertTrue(read.message, read.ok)
        assertEquals("<html>thread</html>", read.data.getString("body"))
        assertEquals(listOf("https://forum.example/threads/2/"), asked)
        assertEquals("the client was not asked again", 1, fake.httpCalls.size)
    }

    @Test fun `a check the web view cannot pass by itself is still the person's`() {
        val fake = FakeEnv(record).apply { httpAnswer = challenge }
        val (env, _) = withWebView(fake) { WebFetch.Result(null, challenged = true, cookies = null) }
        call(env, "https://forum.example/threads/1/")
        assertEquals(PluginErrorCode.BROWSER_CHECK_REQUIRED, call(env, "https://forum.example/threads/3/").code)
    }

    @Test fun `with no web view, the request goes through the client as before`() {
        val fake = FakeEnv(record).apply { httpAnswer = challenge }
        call(fake, "https://forum.example/threads/1/")
        assertEquals(PluginErrorCode.BROWSER_CHECK_REQUIRED, call(fake, "https://forum.example/threads/4/").code)
        assertEquals(2, fake.httpCalls.size)
    }
}
