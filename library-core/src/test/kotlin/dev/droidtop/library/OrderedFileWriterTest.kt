package dev.droidtop.library

import java.io.File
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrderedFileWriterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun writerOn(file: File, maxBytes: Long = 1_000_000L): OrderedFileWriter =
        OrderedFileWriter(maxBytes).also { writer ->
            writer.run { writer.file = file }
        }

    @Test
    fun linesLandInTheOrderTheyWereHandedOver() {
        val file = folder.newFile("scan.log")
        val writer = writerOn(file)
        for (i in 1..500) writer.append("line $i\n")
        assertTrue(writer.flush(5_000))
        assertEquals((1..500).map { "line $it" }, file.readLines())
    }

    @Test
    fun aLineHandedOverBeforeTheFileIsSetIsDroppedAndOnesAfterItAreKept() {
        val file = folder.newFile("scan.log")
        val writer = OrderedFileWriter(1_000_000L)
        writer.append("too early\n")
        writer.run { writer.file = file }
        writer.append("in time\n")
        assertTrue(writer.flush(5_000))
        assertEquals(listOf("in time"), file.readLines())
    }

    @Test
    fun theOldFileIsKeptOnceWhenItGrowsPastTheLimit() {
        val file = folder.newFile("scan.log")
        val writer = writerOn(file, maxBytes = 20L)
        writer.append("0123456789012345678901234\n") // 26 bytes: past the limit, so the next line rotates it
        writer.append("second\n")
        writer.append("third\n")
        assertTrue(writer.flush(5_000))
        val previous = File(file.parentFile, "scan.log.1")
        assertEquals(listOf("0123456789012345678901234"), previous.readLines())
        assertEquals(listOf("second", "third"), file.readLines())
    }

    @Test
    fun flushGivesUpAfterItsTimeWhenTheWriterIsBusyAndSucceedsOnceItIsFree() {
        val file = folder.newFile("scan.log")
        val writer = writerOn(file)
        val release = CountDownLatch(1)
        writer.run { release.await() }
        writer.append("after the block\n")
        assertFalse(writer.flush(100))
        release.countDown()
        assertTrue(writer.flush(5_000))
        assertEquals(listOf("after the block"), file.readLines())
    }

    @Test
    fun aTaskThatThrowsDoesNotStopTheLinesBehindIt() {
        val file = folder.newFile("scan.log")
        val writer = writerOn(file)
        writer.run { error("boom") }
        writer.append("still written\n")
        assertTrue(writer.flush(5_000))
        assertEquals(listOf("still written"), file.readLines())
    }
}
