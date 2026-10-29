package dev.droidtop.library

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One background thread that appends lines to one file, in the order they were handed over, so a caller (the main
 * thread at start-up, a scan) never waits on the disk (Droidtop/tracker#729). The file is rotated at [maxBytes]: the
 * old one is kept once as `<name>.1`. [flush] waits for everything handed over so far, bounded, for the end of a scan
 * and for a crash handler, which must see the last lines on disk before it reads them.
 *
 * A line handed over before [file] is set is dropped, as it was when the log was not installed yet.
 */
internal class OrderedFileWriter(private val maxBytes: Long) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "droidtop-scanlog").apply { isDaemon = true }
    }

    /** The file lines go to; set from [run], on the writer's thread, so the lines after it see it. */
    @Volatile
    var file: File? = null

    /** Appends [text] (a whole line, newline included) after everything handed over before it. */
    fun append(text: String) {
        executor.execute { appendNow(text) }
    }

    /** Runs [task] on the writer's thread, after everything handed over before it and before everything after. */
    fun run(task: () -> Unit) {
        executor.execute { runCatching(task) }
    }

    /** Writes [text] now. Only from the writer's thread. */
    fun appendNow(text: String) {
        val target = file ?: return
        runCatching {
            if (target.length() > maxBytes) {
                val previous = File(target.parentFile, target.name + ".1")
                previous.delete()
                target.renameTo(previous)
            }
            target.appendText(text)
        }
    }

    /** True when everything handed over before this call has been written; false when [timeoutMs] ran out first. */
    fun flush(timeoutMs: Long): Boolean {
        val done = CountDownLatch(1)
        executor.execute { done.countDown() }
        return done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
