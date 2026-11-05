package dev.droidtop.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The display burst a hot-plug sends reaches the second-screen pass once, settled (Droidtop/tracker#785). */
@OptIn(ExperimentalCoroutinesApi::class)
class SettledTest {

    @Test
    fun `the first value goes out at once`() = runTest {
        val seen = flow { emit(1) }.settled(400).toList()
        assertEquals(listOf(1), seen)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a burst is delivered once, as its last value`() = runTest {
        val seen = flow {
            emit(listOf(0))
            delay(1000)
            // A hot-plug: the new screen appears, drops out of the list and comes back, all within the window.
            emit(listOf(0, 5))
            delay(50)
            emit(listOf(0))
            delay(120)
            emit(listOf(0, 5))
        }.settled(400).toList()
        assertEquals(listOf(listOf(0), listOf(0, 5)), seen)
    }

    @Test
    fun `a repeat of the same displays is dropped`() = runTest {
        val seen = flow {
            emit(1)
            delay(1000)
            emit(1)
            delay(1000)
            emit(2)
        }.settled(400).toList()
        assertEquals(listOf(1, 2), seen)
    }
}
