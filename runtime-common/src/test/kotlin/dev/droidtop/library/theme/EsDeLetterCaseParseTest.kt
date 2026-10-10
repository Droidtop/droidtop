package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [esDeLetterCase]: exactly four lowercase literals are accepted; anything else is null for the caller's default. */
class EsDeLetterCaseParseTest {
    @Test
    fun `the four real literals parse`() {
        assertEquals(EsDeLetterCase.UPPERCASE, esDeLetterCase("uppercase"))
        assertEquals(EsDeLetterCase.LOWERCASE, esDeLetterCase("lowercase"))
        assertEquals(EsDeLetterCase.CAPITALIZE, esDeLetterCase("capitalize"))
        assertEquals(EsDeLetterCase.NONE, esDeLetterCase("none"))
    }

    @Test
    fun `anything else is null`() {
        assertNull(esDeLetterCase(null))
        assertNull(esDeLetterCase(""))
        assertNull(esDeLetterCase("Uppercase"))
        assertNull(esDeLetterCase("upper"))
    }
}
