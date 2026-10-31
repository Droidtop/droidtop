package dev.droidtop.library.theme

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A bundled theme whose extraction failed is not tried again until Try again clears it (Droidtop/tracker#849). */
class ExtractionFailuresTest {
    @Before
    @After
    fun reset() {
        ExtractionFailures.clear()
    }

    @Test
    fun `a theme that never failed is tried`() {
        assertTrue(ExtractionFailures.shouldTry("decaffe-es-de"))
    }

    @Test
    fun `a theme that failed is not tried again`() {
        ExtractionFailures.failed("decaffe-es-de")
        assertFalse(ExtractionFailures.shouldTry("decaffe-es-de"))
        assertFalse(ExtractionFailures.shouldTry("decaffe-es-de"))
    }

    @Test
    fun `one theme failing leaves the others to be tried`() {
        ExtractionFailures.failed("decaffe-es-de")
        assertTrue(ExtractionFailures.shouldTry("slate-es-de"))
    }

    @Test
    fun `clear lets every failed theme be tried again`() {
        ExtractionFailures.failed("decaffe-es-de")
        ExtractionFailures.failed("slate-es-de")
        ExtractionFailures.clear()
        assertTrue(ExtractionFailures.shouldTry("decaffe-es-de"))
        assertTrue(ExtractionFailures.shouldTry("slate-es-de"))
    }
}
