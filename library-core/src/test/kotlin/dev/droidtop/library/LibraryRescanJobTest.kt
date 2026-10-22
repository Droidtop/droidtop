package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.settings.LibraryRescan
import dev.droidtop.pluginhost.PluginJobsCenter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Droidtop/tracker#595: a rescan is a job in the one jobs center, and the row that started it still hears it. */
class LibraryRescanJobTest {
    /** The walk never touches the Context here; it only has to be its own application. */
    private class TestContext : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @After
    fun tearDown() {
        LibraryRescan.asJob = null
        LibraryRescan.cancelJob = null
        LibraryRescan.jobRunning = null
        LibraryRescan.handler = null
    }

    @Test
    fun aRescanRunsAsOneJobAndItsRowHearsItsLines() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        LibraryRescan.handler = { _, onStatus ->
            onStatus("Looking at your games")
            gate.await()
            "Rescan finished: 3 games."
        }
        val ctx = TestContext()
        LibraryRescanJob.register(ctx)
        val heard = mutableListOf<String>()
        val answer = async(Dispatchers.Default) { LibraryRescan.run(ctx) { synchronized(heard) { heard += it } } }
        withTimeout(5_000) { while (!LibraryRescan.isRunning) kotlinx.coroutines.delay(10) }
        val job = PluginJobsCenter.entries().value.single { it.nativeKind == LibraryRescanJob.KIND && !it.done }
        assertEquals("Rescan library", job.title)
        gate.complete(Unit)
        assertEquals("Rescan finished: 3 games.", withTimeout(5_000) { answer.await() })
        assertTrue(synchronized(heard) { heard.toList() }.contains("Looking at your games"))
        assertTrue(PluginJobsCenter.find(job.jobId)!!.done)
    }

    @Test
    fun theJobIsNamedForWhatItWalks() {
        assertEquals("Rescan library", LibraryRescanJob.title(pcOnly = false))
        assertEquals("Rescan PC game folders", LibraryRescanJob.title(pcOnly = true))
    }
}
