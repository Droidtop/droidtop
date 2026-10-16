package dev.droidtop.library.integrations

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A source's failure reaches the list as a plain sentence (Droidtop/tracker#375, #485); what it said stays behind "Technical details". */
class SourceFailuresTest {
    private val lewdCorner = "JavaScript is disabled. For a better experience, please enable JavaScript in your browser before proceeding."

    @Test
    fun `a site page text becomes a sign-in sentence`() {
        assertEquals(SourceFailures.SIGN_IN, SourceFailures.plain(lewdCorner))
        assertEquals(SourceFailures.SIGN_IN, SourceFailures.plain("<html><title>Just a moment...</title></html>"))
        assertEquals(SourceFailures.SIGN_IN, SourceFailures.plain("HTTP 403 for https://example.test/search"))
    }

    @Test
    fun `a platform exception becomes not ready, not its text`() {
        val text = "PlatformException(channel-error, Unable to establish connection on channel: dev.flutter.pigeon.path_provider_android.PathProviderApi.getApplicationDocumentsPath, null, null)"
        assertEquals(SourceFailures.NOT_READY, SourceFailures.plain(text))
        assertEquals(SourceFailures.NOT_READY, SourceFailures.plain("IllegalStateException"))
    }

    @Test
    fun `network and timing failures name what to do`() {
        assertEquals(SourceFailures.OFFLINE, SourceFailures.plain("Unable to resolve host example.test: No address associated with hostname"))
        assertEquals(SourceFailures.SLOW_DOWN, SourceFailures.plain("HTTP 429 Too Many Requests"))
        assertTrue(SourceFailures.plain("did not answer in time (it may still be starting up); try again in a moment").contains("did not answer in time"))
    }

    @Test
    fun `markup and long unknown text never reach a row`() {
        assertEquals(SourceFailures.UNREADABLE, SourceFailures.plain("<div>unexpected</div>"))
        assertEquals(SourceFailures.UNREADABLE, SourceFailures.plain("x".repeat(500)))
        assertEquals(SourceFailures.UNREADABLE, SourceFailures.plain("line one\nline two"))
        assertEquals(SourceFailures.GENERIC, SourceFailures.plain("  "))
        assertEquals(SourceFailures.GENERIC, SourceFailures.plain(null))
    }

    @Test
    fun `a short plain reason is kept as it is`() {
        assertEquals("Broken failed", SourceFailures.plain("Broken failed"))
    }

    @Test
    fun `technical details are one bounded line without markup`() {
        assertEquals("Hello world", SourceFailures.technical("<p>Hello</p>\n<b>world</b>"))
        assertEquals(400, SourceFailures.technical("y".repeat(900))!!.length)
        assertNull(SourceFailures.technical(" <br> "))
    }

    @Test
    fun `a failing source outcome carries the plain sentence and keeps its own words`() = runBlocking {
        val source = object : GameSourceProvider {
            override val id = "lewd"
            override val label = "LewdCorner"
            override suspend fun search(context: android.content.Context, query: String, platform: String?): Result<List<AcquireContentResult>> =
                Result.failure(IllegalStateException(lewdCorner))
            override fun detailScreen(result: AcquireContentResult, systemId: String?, systemName: String?, destination: java.io.File?) =
                dev.droidtop.library.settings.CatalogScreen("d", result.title, groups = { emptyList() })
        }
        val outcome = PluginSearchAggregator.fanOutOutcomes(listOf(source)) { it.search(android.content.ContextWrapper(null), "q", null) }.single()
        assertEquals(SourceFailures.SIGN_IN, outcome.failure)
        assertNotNull(outcome.technical)
        assertTrue(outcome.technical!!.startsWith("JavaScript is disabled"))
        assertFalse(outcome.failure!!.contains("JavaScript"))
    }

    @Test
    fun `droidtop's own refusal names the permission and is not a site failure`() {
        // Rig 2026-10-15 (#485 step 5): LewdCorner's search line was the broker's own refusal, passed through.
        assertEquals(
            "It needs \u201cUse your sign-in on its sites\u201d, which is not allowed for it",
            SourceFailures.permissionOff("\"Use your sign-in on its sites\" is turned off for LewdCorner."),
        )
        assertEquals(
            "It needs \u201cUse your sign-in on its sites\u201d, which is not allowed for it",
            SourceFailures.permissionOff("LewdCorner needs you to allow \"Use your sign-in on its sites\"."),
        )
        assertEquals(SourceFailures.PERMISSION_OFF, SourceFailures.permissionOff("no", dev.droidtop.pluginhost.PluginErrorCode.PERMISSION_DENIED))
        assertNull(SourceFailures.permissionOff(lewdCorner))
    }
}
