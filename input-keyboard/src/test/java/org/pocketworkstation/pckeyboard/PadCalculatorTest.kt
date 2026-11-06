package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The number pad's "=" (Droidtop/tracker#903 H12c). */
class PadCalculatorTest {
    private fun eval(sum: String): String? = PadCalculator.evaluate(sum)?.let(PadCalculator::format)

    @Test fun multiplicationAndDivisionComeBeforeAdditionAndSubtraction() {
        assertEquals("14", eval("2+3*4"))
        assertEquals("10", eval("2*3+4"))
        assertEquals("-1", eval("1-6/3"))
        assertEquals("20", eval("(2+3)*4"))
        assertEquals("3", eval("10-4-3"))
        assertEquals("1", eval("8/4/2"))
    }

    @Test fun thePrintedOperatorsMeanTheSame() {
        assertEquals("14", eval("2 + 3 × 4"))
        assertEquals("2.5", eval("5÷2"))
        assertEquals("1", eval("3−2"))
    }

    @Test fun decimalsAreDecimalNotBinary() {
        assertEquals("0.3", eval("0.1+0.2"))
        assertEquals("1.5", eval("1,5*1"))
        assertEquals("0.3333333333333333", eval("1/3"))
        assertEquals("2", eval("0.5*4"))
        assertEquals("1000000", eval("1000*1000"))
    }

    @Test fun divisionByZeroHasNoResult() {
        assertNull(eval("1/0"))
        assertNull(eval("5÷(2-2)"))
        assertNull(PadCalculator.answer("12/0"))
    }

    @Test fun malformedSumsAndLoneNumbersHaveNoResult() {
        assertNull(eval("2+"))
        assertNull(eval("*3"))
        assertNull(eval("1..2+1"))
        assertNull(eval("(1+2"))
        assertNull(eval("42"))
        assertNull(eval("-5"))
        assertNull(eval(""))
    }

    @Test fun aLeadingMinusIsANegativeNumber() {
        assertEquals("-7", eval("-3-4"))
        assertEquals("12", eval("-3*-4"))
    }

    @Test fun theSumIsTheTextJustBeforeTheCursor() {
        assertEquals("7", PadCalculator.answer("Total: 3+4"))
        assertEquals("4", PadCalculator.answer("page 3 2+2"))
        assertEquals("13", PadCalculator.answer("x 12+1"))
        // After an answer, "=" has no sum left to work out: it types a plain "=".
        assertNull(PadCalculator.answer("3+4=7"))
        assertNull(PadCalculator.answer("hello"))
        assertNull(PadCalculator.answer(null))
    }
}
