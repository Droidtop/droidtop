package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plugin API contract gaps droidtop now draws itself (docs/plugin-api.md 1.3, C2, G3; Droidtop/tracker#544). */
class PluginContractGapsTest {
    @Test
    fun `an error's detail and BROWSER_CHECK_REQUIRED cross the wire`() {
        val reply = PluginReply.failed(PluginErrorCode.BROWSER_CHECK_REQUIRED, "The site checks the browser first", "Just a moment... (cf-chl)")
        val back = PluginReply.parse(reply.toJson().toString())
        assertEquals(PluginErrorCode.BROWSER_CHECK_REQUIRED, back.code)
        assertEquals("Just a moment... (cf-chl)", back.detail)
        assertEquals("The site checks the browser first", back.message)
        assertNull(PluginReply.parse(PluginReply.error(PluginErrorCode.FAILED, "x").toJson().toString()).detail)
    }

    @Test
    fun `sign-in and browser-check errors become droidtop's own action, and Info shows the detail`() {
        val signIn = PluginViewCall.errorNotice(PluginReply.error(PluginErrorCode.SIGN_IN_REQUIRED, "Sign in to search"), emptySet(), "Couldn't finish")
        assertEquals(PluginWebAccount.Need.SIGN_IN, signIn.opensAccount)
        val check = PluginViewCall.errorNotice(
            PluginReply.failed(PluginErrorCode.BROWSER_CHECK_REQUIRED, "The site checks the browser first", "HTTP 403 cf-mitigated"),
            emptySet(), "Couldn't finish",
        )
        assertEquals(PluginWebAccount.Need.BROWSER_CHECK, check.opensAccount)
        assertEquals("BROWSER_CHECK_REQUIRED: HTTP 403 cf-mitigated", check.details)
        assertNull(PluginViewCall.errorNotice(PluginReply.error(PluginErrorCode.FAILED, "no network"), emptySet(), "Couldn't finish").opensAccount)
    }

    private fun tile(extra: JSONObject) = ProvidedPoint("ui.quick_tile", id = "t", extra = extra.toString())

    @Test
    fun `a tile can be scoped to a running game, or to one app's`() {
        val always = tile(JSONObject())
        assertTrue(PluginTileProtocol.forGame(always, gameRunning = false, runningPackage = null))
        val whileGame = tile(JSONObject().put("whileGame", true))
        assertFalse(PluginTileProtocol.forGame(whileGame, false, null))
        assertTrue(PluginTileProtocol.forGame(whileGame, true, "org.ppsspp.ppsspp"))
        val retroArch = tile(JSONObject().put("gamePackages", JSONArray().put("com.retroarch")))
        assertFalse(PluginTileProtocol.forGame(retroArch, false, null))
        assertFalse(PluginTileProtocol.forGame(retroArch, true, "org.ppsspp.ppsspp"))
        assertTrue(PluginTileProtocol.forGame(retroArch, true, "com.retroarch"))
    }

    private fun webPlugin(entry: JSONObject) = TestPlugins.record(
        TestPlugins.manifest(id = "acme.web") { it.put("permissions", JSONArray().put(entry)) },
    )

    @Test
    fun `the declared sign-in page must be https on a declared site`() {
        val good = webPlugin(JSONObject().put("id", "web.session").put("domains", JSONArray().put("forum.example")).put("signIn", "https://forum.example/login/"))
        assertEquals("https://forum.example/login/", PluginWebAccount.signInUrl(good))
        assertEquals("https://forum.example/", PluginWebAccount.checkUrl(good))
        val offSite = webPlugin(JSONObject().put("id", "web.session").put("domains", JSONArray().put("forum.example")).put("signIn", "https://evil.example/login"))
        assertNull(PluginWebAccount.signInUrl(offSite))
        assertEquals("https://forum.example/", PluginWebAccount.checkUrl(offSite))
        val plain = webPlugin(JSONObject().put("id", "web.session").put("domains", JSONArray().put("forum.example")).put("signIn", "http://forum.example/login"))
        assertNull(PluginWebAccount.signInUrl(plain))
        val none = TestPlugins.record(TestPlugins.manifest(id = "acme.none"))
        assertNull(PluginWebAccount.checkUrl(none))
    }

    @Test
    fun `an entry's title follows the point's name and is cut`() {
        assertEquals("Status tiles and widgets", ProvidedPoint("ui.status_tile").titled("Status tiles and widgets"))
        assertEquals("Status tiles and widgets", ProvidedPoint("ui.status_tile", label = " status tiles and widgets ").titled("Status tiles and widgets"))
        assertEquals("Status tiles and widgets: Save state", ProvidedPoint("ui.status_tile", label = "Save state").titled("Status tiles and widgets"))
        val long = ProvidedPoint("ui.status_tile", label = "x".repeat(60)).titled("Tiles")
        assertEquals("Tiles: ".length + ProvidedPoint.LABEL_MAX, long.length)
        assertTrue(long.endsWith("\u2026"))
    }

    @Test
    fun `a view's empty sentence is read and cut`() {
        val view = PluginView.parse(JSONObject().put("view", 1).put("sections", JSONArray()).put("empty", "Saves you make appear here"))!!
        assertEquals("Saves you make appear here", view.empty)
        assertNull(PluginView.parse(JSONObject().put("view", 1).put("sections", JSONArray()))!!.empty)
        val long = PluginView.parse(JSONObject().put("view", 1).put("sections", JSONArray()).put("empty", "y".repeat(400)))!!
        assertEquals(PluginViewLimits.length("noticeSubtitle"), long.empty!!.length)
    }
}
