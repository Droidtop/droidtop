package dev.droidtop.library.credentials.web

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-app key pages: where they may load, where they may be read, and what counts as a key (docs/SPEC.md 7h). */
class KeyPagesTest {
    private val json = """
        {"version":1,"pages":{"SVC":{
          "startUrl":"https://svc.example/keys","hosts":["svc.example","*.login.example"],
          "steps":[
            {"label":"Sign in","hint":"h","urlMatch":"^https://svc\\.example/login"},
            {"label":"Open keys","urlMatch":"^https://svc\\.example/keys"}],
          "capture":{"host":"svc.example","pathMatch":"^/keys/?$","fields":[
            {"id":"key","selectors":["input#k"],"labels":["api key"],"pattern":"[a-f0-9]{8}"}]}}}}
    """.trimIndent()
    private val page = KeyPages.parse(json).getValue("SVC")

    @Test
    fun `the page loads from the data`() {
        assertEquals("https://svc.example/keys", page.startUrl)
        assertEquals(2, page.steps.size)
        assertEquals("h", page.steps[0].hint)
        assertNull(page.steps[1].hint)
        assertFalse(page.verified)
    }

    @Test
    fun `the pane loads only https pages of its hosts`() {
        assertTrue(KeyPages.canLoad(page, "https://svc.example/login"))
        assertTrue(KeyPages.canLoad(page, "https://id.login.example/auth"))
        assertFalse(KeyPages.canLoad(page, "http://svc.example/login"))
        assertFalse(KeyPages.canLoad(page, "https://evil.example/"))
        assertFalse(KeyPages.canLoad(page, "https://svc.example.evil.test/"))
        assertFalse(KeyPages.canLoad(page, "https://login.example/"))
        assertFalse(KeyPages.canLoad(page, "https://user@svc.example/"))
        assertFalse(KeyPages.canLoad(page, "intent://svc.example/#Intent;end"))
    }

    @Test
    fun `fields may be read only on the exact host and path`() {
        assertTrue(KeyPages.captureAllowed(page, "https://svc.example/keys"))
        assertTrue(KeyPages.captureAllowed(page, "https://svc.example/keys/?x=1"))
        assertFalse(KeyPages.captureAllowed(page, "https://svc.example/keys/other"))
        assertFalse(KeyPages.captureAllowed(page, "https://svc.example/login"))
        assertFalse(KeyPages.captureAllowed(page, "https://id.login.example/keys"))
        assertFalse(KeyPages.captureAllowed(page, "http://svc.example/keys"))
        assertFalse(KeyPages.captureAllowed(page, "https://svc.example:8443/keys"))
        assertFalse(KeyPages.captureAllowed(page, null))
    }

    @Test
    fun `the step follows the address and keeps the last one between steps`() {
        assertEquals(0, KeyPages.stepIndex(page, "https://svc.example/login?next=1", 1))
        assertEquals(1, KeyPages.stepIndex(page, "https://svc.example/keys", 0))
        assertEquals(1, KeyPages.stepIndex(page, "https://svc.example/elsewhere", 1))
        assertEquals(1, KeyPages.stepIndex(page, null, 9))
    }

    @Test
    fun `only a value that fits the pattern is accepted`() {
        val field = page.capture!!.fields.first()
        assertEquals("abcdef12", KeyPages.accept(field, " abcdef12 "))
        assertNull(KeyPages.accept(field, "abcdef1"))
        assertNull(KeyPages.accept(field, "ABCDEF12"))
        assertNull(KeyPages.accept(field, "abcdef12 and more page text"))
        assertNull(KeyPages.accept(field, null))
    }

    @Test
    fun `the answer of the page script is opened and checked`() {
        val capture = page.capture!!
        assertEquals(mapOf("key" to "abcdef12"), KeyPages.parseResult(capture, "\"{\\\"key\\\":\\\"abcdef12\\\"}\""))
        assertEquals(emptyMap<String, String>(), KeyPages.parseResult(capture, "\"{\\\"key\\\":\\\"nope\\\"}\""))
        assertEquals(emptyMap<String, String>(), KeyPages.parseResult(capture, "null"))
        assertEquals(emptyMap<String, String>(), KeyPages.parseResult(capture, "not json"))
        assertEquals(emptyMap<String, String>(), KeyPages.parseResult(capture, null))
    }

    @Test
    fun `the script names only the configured selectors and labels`() {
        val script = KeyPages.script(page.capture!!)
        assertTrue(script.contains("input#k"))
        assertTrue(script.contains("api key"))
        assertFalse(script.contains("localStorage"))
        assertFalse(script.contains("document.cookie"))
        assertFalse(script.contains("fetch("))
    }

    @Test
    fun `the shipped data file parses and names only https hosts it covers`() {
        val file = listOf("app/src/main/assets/key-pages.json", "../app/src/main/assets/key-pages.json")
            .map(::File).firstOrNull { it.isFile }
        assertNotNull("key-pages.json not found from ${File(".").absolutePath}", file)
        val pages = KeyPages.parse(file!!.readText())
        assertEquals(setOf("IGDB", "STEAMGRIDDB", "THEGAMESDB", "SCREENSCRAPER"), pages.keys)
        for ((name, spec) in pages) {
            assertTrue(name, KeyPages.canLoad(spec, spec.startUrl))
            assertTrue(name, spec.steps.size in 3..5)
            spec.capture?.let { capture ->
                assertTrue(name, spec.hosts.any { it == capture.host || (it.startsWith("*.") && capture.host.endsWith(it.drop(1))) })
                capture.fields.forEach { Regex(it.pattern) }
            }
        }
        assertNull(pages.getValue("SCREENSCRAPER").capture)
    }
}
