package dev.droidtop.pluginhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HostCallRequestTest {
    @Test
    fun `parses a broker call and preserves args as JSON`() {
        val request = HostCallRequest.parse(
            """{"api":"host.info","version":1,"op":"info","args":{"detail":true}}""",
        )

        assertEquals("host.info", request.api)
        assertEquals(1, request.version)
        assertEquals("info", request.op)
        assertEquals(true, JSONObject(request.argsJson).getBoolean("detail"))
    }

    @Test
    fun `rejects malformed and incomplete broker calls`() {
        assertThrows(IllegalArgumentException::class.java) { HostCallRequest.parse(42) }
        assertThrows(Exception::class.java) { HostCallRequest.parse("not json") }
        assertThrows(IllegalArgumentException::class.java) {
            HostCallRequest.parse("""{"api":"host.info","version":1,"op":"info"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HostCallRequest.parse("""{"api":"host.info","version":0,"op":"info","args":{}}""")
        }
    }
}
