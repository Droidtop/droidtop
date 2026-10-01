package dev.droidtop.library.scraper

import dev.droidtop.library.scraper.LibraryScrapeJob.Position
import dev.droidtop.library.scraper.LibraryScrapeJob.StepResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library scrape job's checkpoint and resume rules (Droidtop/tracker#174):
 * the checkpoint is the position in the scrape queue, a paused or restarted
 * run carries on from it, and nothing before it is asked again.
 */
class LibraryScrapeJobTest {
    private val queue = listOf("/g/nes" to "NES", "/g/snes" to "SNES", "/g/gba" to "GBA")

    private class Recorder {
        val checkpoints = mutableListOf<String?>()
        val steps = mutableListOf<Pair<Int, String?>>()
        val report: (Int, String, String?) -> Unit = { _, _, checkpoint -> checkpoints += checkpoint }
    }

    @Test
    fun positionSurvivesEncodingAndRejectsGarbage() {
        val position = Position("/g/snes", "/g/snes/Mario.sfc")
        assertEquals(position, Position.decode(position.encode()))
        assertNull(Position.decode(null))
        assertNull(Position.decode("not json"))
    }

    @Test
    fun aFreshRunReportsAPositionPerSystemAndPerFinishedRom() = runBlocking {
        val recorder = Recorder()
        val summary = LibraryScrapeJob.runQueue(queue, from = null, step = { index, resumeAfter, romDone, _ ->
            recorder.steps += index to resumeAfter
            romDone("/g/${queue[index].second.lowercase()}/a.rom")
            StepResult("${queue[index].second}: done")
        }, report = recorder.report)

        assertEquals(listOf(0 to null, 1 to null, 2 to null), recorder.steps)
        assertEquals("Scraped 3 systems\nNES: done\nSNES: done\nGBA: done", summary)
        // Each system starts at its own folder with no ROM finished, then moves to its last finished ROM.
        assertEquals(Position("/g/nes", "").encode(), recorder.checkpoints.first())
        assertTrue(recorder.checkpoints.contains(Position("/g/snes", "/g/snes/a.rom").encode()))
    }

    @Test
    fun aRunCancelledMidQueueResumesAtTheSystemAndRomAfterItsCheckpoint() = runBlocking {
        val first = Recorder()
        val reached = CompletableDeferred<Unit>()
        val job = launch {
            LibraryScrapeJob.runQueue(queue, from = null, step = { index, _, romDone, _ ->
                if (index == 1) {
                    romDone("/g/snes/b.rom")
                    reached.complete(Unit)
                    awaitCancellation()
                }
                StepResult("${queue[index].second}: done")
            }, report = first.report)
        }
        reached.await()
        job.cancel()
        job.join()
        val stored = first.checkpoints.filterNotNull().last()
        assertEquals(Position("/g/snes", "/g/snes/b.rom"), Position.decode(stored))

        val second = Recorder()
        val summary = LibraryScrapeJob.runQueue(queue, from = Position.decode(stored), step = { index, resumeAfter, _, _ ->
            second.steps += index to resumeAfter
            StepResult("${queue[index].second}: done")
        }, report = second.report)

        // The NES folder is not asked again; SNES carries on after the ROM it had finished; GBA starts fresh.
        assertEquals(listOf(1 to "/g/snes/b.rom", 2 to null), second.steps)
        assertEquals("Scraped 2 systems\nSNES: done\nGBA: done", summary)
    }

    @Test
    fun aCheckpointForAFolderNoLongerInTheQueueStartsFromTheTop() = runBlocking {
        val recorder = Recorder()
        LibraryScrapeJob.runQueue(queue, from = Position("/g/gone", "/g/gone/x.rom"), step = { index, resumeAfter, _, _ ->
            recorder.steps += index to resumeAfter
            StepResult("ok")
        }, report = recorder.report)
        assertEquals(listOf(0 to null, 1 to null, 2 to null), recorder.steps)
    }

    @Test
    fun aSourceThatRefusedEverythingStopsTheQueue() = runBlocking {
        val recorder = Recorder()
        val summary = LibraryScrapeJob.runQueue(queue, from = null, step = { index, resumeAfter, _, _ ->
            recorder.steps += index to resumeAfter
            StepResult("refused", stopQueue = index == 1)
        }, report = recorder.report)
        assertEquals(listOf(0 to null, 1 to null), recorder.steps)
        assertEquals("Stopped at system 2 of 3\nrefused", summary)
    }

    @Test
    fun progressWithinASystemMapsOntoTheWholeQueue() = runBlocking {
        val percents = mutableListOf<Int>()
        LibraryScrapeJob.runQueue(listOf("/g/a" to "A", "/g/b" to "B"), from = null, step = { index, _, _, progress ->
            if (index == 1) progress(1, 2)
            StepResult("ok")
        }, report = { percent, _, _ -> percents += percent })
        // Second of two systems, half done: 50% + (50% * 1/2).
        assertTrue(percents.contains(75))
    }
}
