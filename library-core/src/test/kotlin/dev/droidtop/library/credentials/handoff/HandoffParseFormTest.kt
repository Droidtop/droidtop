package dev.droidtop.library.credentials.handoff

import org.junit.Assert.assertEquals
import org.junit.Test

/** [HandoffHttp.parseForm]: an application/x-www-form-urlencoded body into its fields. */
class HandoffParseFormTest {
    @Test
    fun `fields are split and percent decoded`() {
        assertEquals(
            mapOf("a" to "1", "b" to "two words", "c" to "x=y"),
            HandoffHttp.parseForm("a=1&b=two%20words&c=x%3Dy"),
        )
        assertEquals(mapOf("plus" to "a b"), HandoffHttp.parseForm("plus=a+b"))
    }

    @Test
    fun `a part without an equals sign is dropped and an empty value is kept`() {
        assertEquals(mapOf("a" to "1", "b" to "2"), HandoffHttp.parseForm("a=1&flag&b=2"))
        assertEquals(mapOf("k" to ""), HandoffHttp.parseForm("k="))
    }

    @Test
    fun `an empty body has no fields`() {
        assertEquals(emptyMap<String, String>(), HandoffHttp.parseForm(""))
    }
}
