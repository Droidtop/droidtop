package dev.droidtop.shell.gamepad

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search field takes focus on open (console, 0.2.0-dev.2127): a request that Compose refuses without throwing,
 * as an unattached FocusRequester does in Compose 1.9, is asked again next frame instead of read as taken.
 */
class FocusRetryTest {
    @Test
    fun `a refused request is asked again each frame until focus is taken`() = runBlocking {
        val answers = ArrayDeque(listOf(false, false, true))
        var frames = 0
        val taken = retryEachFrame(attempts = 60, attempt = { answers.removeFirst() }, nextFrame = { frames++ })
        assertTrue(taken)
        assertEquals(2, frames)
    }

    @Test
    fun `it gives up after its attempts without waiting a frame past the last`() = runBlocking {
        var tries = 0
        var frames = 0
        val taken = retryEachFrame(attempts = 5, attempt = { tries++; false }, nextFrame = { frames++ })
        assertFalse(taken)
        assertEquals(5, tries)
        assertEquals(4, frames)
    }

    @Test
    fun `focus taken at once waits no frame`() = runBlocking {
        var frames = 0
        assertTrue(retryEachFrame(attempts = 60, attempt = { true }, nextFrame = { frames++ }))
        assertEquals(0, frames)
    }
}
