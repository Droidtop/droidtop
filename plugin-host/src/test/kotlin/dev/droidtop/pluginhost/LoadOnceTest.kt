package dev.droidtop.pluginhost

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plugin is loaded once per process, not once per call (Droidtop/tracker#884: one search ran
 * NativePluginRunner.load once per source at the same time, on every call, on BlueStacks). Counts the loads.
 */
class LoadOnceTest {
    /** A process that stays up until [kill]. */
    private class Process {
        @Volatile var up = true
    }

    private val loads = LoadOnce<Process>()
    private val count = AtomicInteger()
    private var process = Process()

    private suspend fun call(id: String = "gamegrab.f95", key: String = "CONTAINED|abc"): Boolean =
        loads.load(id, key, alive = { it.up }) {
            count.incrementAndGet()
            delay(20) // a load takes time: callers at the same moment must wait for it, not start their own
            process
        }

    @Test
    fun `calls after the first load do not load again`() = runBlocking {
        repeat(10) { assertTrue(call()) }
        assertEquals(1, count.get())
    }

    @Test
    fun `a search's calls to one source at the same time share one load`() = runBlocking {
        val results = (1..6).map { async(Dispatchers.Default) { call() } }.awaitAll()
        assertTrue(results.all { it })
        assertEquals(1, count.get())
    }

    @Test
    fun `a search across sources loads each source once, then none on the next search`() = runBlocking {
        val sources = listOf("gamegrab.f95", "gamegrab.nyaa", "gamegrab.repacks")
        repeat(2) {
            sources.flatMap { id -> (1..3).map { async(Dispatchers.Default) { call(id) } } }.awaitAll()
        }
        assertEquals(sources.size, count.get())
    }

    @Test
    fun `a process that ended, a crash, a failed load or an update loads again`() = runBlocking {
        assertTrue(call())
        process.up = false
        process = Process()
        assertTrue(call())
        assertEquals(2, count.get())
        loads.forget("gamegrab.f95")
        assertTrue(call())
        assertEquals(3, count.get())
        assertTrue(call(key = "CONTAINED|def"))
        assertEquals(4, count.get())
        // A load that failed is never remembered.
        assertFalse(loads.load("gamegrab.bios", "CONTAINED|x", alive = { true }) { count.incrementAndGet(); null })
        assertTrue(call("gamegrab.bios", "CONTAINED|x"))
        assertEquals(6, count.get())
    }
}
