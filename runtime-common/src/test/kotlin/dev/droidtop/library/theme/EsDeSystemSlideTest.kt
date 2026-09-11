package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EsDeSystemSlideTest {

    @Test
    fun `a horizontal carousel slides along x, everything else along y`() {
        assertTrue(EsDeSystemSlide.slidesHorizontally("carousel", "horizontal"))
        assertTrue(EsDeSystemSlide.slidesHorizontally("carousel", "horizontalWheel"))
        // ES-DE's own default type, and its unmatched branch, are both horizontal.
        assertTrue(EsDeSystemSlide.slidesHorizontally("carousel", null))
        assertTrue(EsDeSystemSlide.slidesHorizontally("carousel", "nonsense"))
        assertFalse(EsDeSystemSlide.slidesHorizontally("carousel", "vertical"))
        assertFalse(EsDeSystemSlide.slidesHorizontally("carousel", "verticalWheel"))
        assertFalse(EsDeSystemSlide.slidesHorizontally("grid", null))
        assertFalse(EsDeSystemSlide.slidesHorizontally("textlist", null))
    }

    @Test
    fun `at rest only the system under the camera is drawn`() {
        assertEquals(listOf(3), EsDeSystemSlide.renderedIndices(3f, 6, animating = false))
    }

    @Test
    fun `mid-move both neighbours are drawn as well`() {
        assertEquals(listOf(2, 3, 4), EsDeSystemSlide.renderedIndices(3.4f, 6, animating = true))
        // The window follows the truncated offset, so it flips a whole
        // entry at a time as ES-DE's own (int) cast does.
        assertEquals(listOf(3, 4, 5), EsDeSystemSlide.renderedIndices(4.0f, 6, animating = true))
    }

    @Test
    fun `a single system has no neighbours to draw`() {
        assertEquals(listOf(0), EsDeSystemSlide.renderedIndices(0f, 1, animating = true))
        assertEquals(emptyList<Int>(), EsDeSystemSlide.renderedIndices(0f, 0, animating = true))
    }

    @Test
    fun `an index outside the list folds back into it`() {
        assertEquals(5, EsDeSystemSlide.wrap(-1, 6))
        assertEquals(0, EsDeSystemSlide.wrap(6, 6))
        assertEquals(3, EsDeSystemSlide.wrap(3, 6))
    }

    @Test
    fun `displacement is the distance from the camera, in views`() {
        assertEquals(0f, EsDeSystemSlide.displacement(3, 3f), 0.0001f)
        assertEquals(-0.4f, EsDeSystemSlide.displacement(3, 3.4f), 0.0001f)
        assertEquals(0.6f, EsDeSystemSlide.displacement(4, 3.4f), 0.0001f)
        assertEquals(-1.4f, EsDeSystemSlide.displacement(2, 3.4f), 0.0001f)
    }

    @Test
    fun `the carousel counts as moving only off a whole entry`() {
        assertFalse(EsDeSystemSlide.animating(3f))
        assertFalse(EsDeSystemSlide.animating(0f))
        assertTrue(EsDeSystemSlide.animating(3.4f))
        assertTrue(EsDeSystemSlide.animating(2.999f))
    }
}
