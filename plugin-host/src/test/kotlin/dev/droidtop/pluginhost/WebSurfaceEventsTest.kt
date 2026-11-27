package dev.droidtop.pluginhost

import org.junit.Assert.*
import org.junit.Test

class WebSurfaceEventsTest {
    @Test fun `only bounded structured HTTPS navigation crosses the surface boundary`() {
        assertNull(WebSurfaceEvents.parse("""{"type":"navigation","url":"javascript:alert(1)"}"""))
        assertNull(WebSurfaceEvents.parse("""{"type":"navigation","url":"https://user:password@example.com/"}"""))
        assertNull(WebSurfaceEvents.parse("""{"type":"script","body":"document.cookie"}"""))
        assertNull(WebSurfaceEvents.parse("x".repeat(8193)))
        val event = WebSurfaceEvents.parse("""{"type":"navigation","url":"https://example.com/","title":"Hello","canGoBack":true}""")!!
        assertEquals("https://example.com/", event.url)
        assertTrue(event.back)
    }
}
