package dev.droidtop.library.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one search's calculator row (Droidtop/tracker#704). */
class SearchCalculatorTest {
    @Test
    fun `sums answer with their value`() {
        assertEquals("42", SearchCalculator.answer("6*7"))
        assertEquals("7.75", SearchCalculator.answer("(12.5 + 3) / 2"))
        assertEquals("1024", SearchCalculator.answer("2^10"))
        assertEquals("14", SearchCalculator.answer("2 + 3 × 4"))
        assertEquals("-3", SearchCalculator.answer("-5 + 2"))
        assertEquals("0.3333333333", SearchCalculator.answer("1/3"))
        assertEquals("2.5", SearchCalculator.answer("5 ÷ 2"))
    }

    @Test
    fun `names, plain numbers and broken sums are not sums`() {
        assertNull(SearchCalculator.answer("Doom 2"))
        assertNull(SearchCalculator.answer("2077"))
        assertNull(SearchCalculator.answer("Half-Life 2"))
        assertNull(SearchCalculator.answer("3 +"))
        assertNull(SearchCalculator.answer("1/0"))
        assertNull(SearchCalculator.answer("(1+2"))
        assertNull(SearchCalculator.answer(""))
    }
}
