package dev.droidtop.library.diagnostics

import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

/**
 * Keeps the last lines a child process printed, bounded, and tells a listener about each line as it arrives.
 * Thread-safe: a process's stdout and stderr are read on separate threads. The one collector for a run's
 * output: the Wine guest feeds it from its output streams, and a problem report can feed it from any other
 * (docs/SPEC.md 10c, "Every run leaves a record").
 *
 * [echo] sees the first [echoLimit] lines (for logcat), then one notice, then nothing: a trace channel turned
 * up by hand must not flood the log buffer.
 */
class RunLogCollector(
    private val maxLines: Int = RunRecords.OUTPUT_LINES,
    private val maxLineChars: Int = RunRecords.LINE_CHARS,
    private val echoLimit: Int = ECHO_LINES,
    private val echo: (String) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val tail = ArrayDeque<String>()
    private var total = 0L
    private var lastLineAt = nowMs()

    /** Adds one line. */
    fun add(line: String) {
        val bounded = if (line.length > maxLineChars) line.take(maxLineChars) + "..." else line
        val toEcho: String?
        synchronized(tail) {
            tail.addLast(bounded)
            while (tail.size > maxLines) tail.removeFirst()
            total++
            lastLineAt = nowMs()
            toEcho = when {
                total <= echoLimit -> bounded
                total == echoLimit + 1L -> "more output follows; only the last $maxLines lines are kept in the run's record"
                else -> null
            }
        }
        toEcho?.let(echo)
    }

    /** The kept lines, oldest first, one per line. */
    fun text(): String = synchronized(tail) { tail.joinToString("\n") }

    /** How many lines were ever added (more than are kept). */
    fun linesSeen(): Long = synchronized(tail) { total }

    /**
     * Waits until no line has arrived for [quietMs], or [maxMs] has passed. A process's end is reported before
     * the thread reading its pipes has always delivered the last lines; this gives them time to arrive.
     * Blocks: never on the main thread.
     */
    fun awaitQuiet(quietMs: Long = 150, maxMs: Long = 1500) {
        val deadline = nowMs() + maxMs
        while (nowMs() < deadline) {
            val idle = synchronized(tail) { nowMs() - lastLineAt }
            if (idle >= quietMs) return
            try {
                Thread.sleep(20)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    companion object {
        /** Lines sent to the echo (logcat) per run. */
        const val ECHO_LINES = 3000
    }
}

/**
 * A run's record, and the short history of them in droidtop's logs folder (`ScanLog.logsDir`). One writer for
 * every kind of run, so Share diagnostics, which packs the folder, carries all of them, and a problem report
 * reads the same files. Blocking file work: call off the main thread.
 */
object RunRecords {
    /** Lines of the process's output a record keeps. */
    const val OUTPUT_LINES = 200

    /** Characters of one output line a record keeps. */
    const val LINE_CHARS = 600

    /** Bytes of one attached file (the end of it) a record keeps. */
    const val ATTACHMENT_BYTES = 64 * 1024

    /** Files one record attaches. */
    const val MAX_ATTACHMENTS = 6

    /** Records kept per game, and in all. */
    const val KEEP_PER_KEY = 3
    const val KEEP_TOTAL = 12

    /** A file that belongs to a run: its words, with where it was and whether this run wrote it. */
    data class Attachment(
        val label: String,
        val path: String,
        val modifiedMs: Long,
        val text: String,
        val truncated: Boolean,
        val fromThisRun: Boolean,
    )

    /**
     * Reads the end of [file] as an attachment, or null when it is missing, empty or unreadable. [runStartedMs]
     * (wall clock) decides whether the file was written during the run: a log older than that is still kept,
     * but says so, since the run may not have written one at all.
     */
    fun attach(label: String, file: File, runStartedMs: Long): Attachment? = runCatching {
        if (!file.isFile) return null
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length == 0L) return null
            val size = minOf(length, ATTACHMENT_BYTES.toLong()).toInt()
            raf.seek(length - size)
            val bytes = ByteArray(size).also { raf.readFully(it) }
            val modified = file.lastModified()
            Attachment(label, file.path, modified, decode(bytes), length > size, modified >= runStartedMs - CLOCK_SLACK_MS)
        }
    }.getOrNull()

    private const val CLOCK_SLACK_MS = 60_000L

    private fun decode(bytes: ByteArray): String {
        val utf16 = bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()
        return if (utf16) String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) else String(bytes, Charsets.UTF_8)
    }

    /**
     * The record of one run: the [heading] and the [facts] about how it ended, the last lines of its [output]
     * ([outputLabel] says whose), then each attached file. Pure.
     */
    fun render(
        heading: String,
        facts: List<Pair<String, String>>,
        outputLabel: String,
        output: String,
        attachments: List<Attachment>,
        nowMs: Long,
    ): String {
        val lines = output.lines().map { it.trimEnd().take(LINE_CHARS) }.filter(String::isNotBlank).takeLast(OUTPUT_LINES)
        return buildString {
            appendLine("$heading ended at ${Instant.ofEpochMilli(nowMs)}")
            facts.forEach { (name, value) -> appendLine("$name: $value") }
            appendLine("Last ${lines.size} lines $outputLabel:")
            lines.forEach { appendLine(it) }
            attachments.take(MAX_ATTACHMENTS).forEach { a ->
                appendLine()
                val age = if (a.fromThisRun) "written during this run" else "older than this run"
                val cut = if (a.truncated) ", start left out" else ""
                appendLine("==== ${a.label}: ${a.path} (modified ${Instant.ofEpochMilli(a.modifiedMs)}, $age$cut) ====")
                appendLine(a.text.trimEnd())
            }
        }
    }

    /** The largest a record can be, in bytes (UTF-8 at most three bytes a character), for the diagnostics cap. */
    const val MAX_RECORD_BYTES = OUTPUT_LINES * (LINE_CHARS + 1) * 3 + MAX_ATTACHMENTS * ATTACHMENT_BYTES + 4096

    /**
     * Writes [record] as `<prefix>-last.log` (the newest of its kind) and as its own file in the short history,
     * `<prefix>-run-<key>-<nowMs>.log`, then drops the history beyond [keepPerKey] runs of this [key] and
     * [keepTotal] runs in all. [key] is the game (its library id, or the program's name). Single files only.
     * Answers the history file.
     */
    fun save(
        dir: File,
        prefix: String,
        key: String,
        record: String,
        nowMs: Long,
        keepPerKey: Int = KEEP_PER_KEY,
        keepTotal: Int = KEEP_TOTAL,
    ): File {
        dir.mkdirs()
        val safe = safeKey(key)
        val history = File(dir, "$prefix-run-$safe-$nowMs.log")
        writeAtomically(history, record)
        writeAtomically(File(dir, "$prefix-last.log"), record)
        prune(dir, prefix, keepPerKey, keepTotal)
        return history
    }

    /** A game's key as a file-name part: letters, digits, dot, underscore, and dash; short. */
    fun safeKey(key: String): String =
        key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifBlank { "unknown" }

    private fun writeAtomically(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }

    private class Entry(val file: File, val key: String, val at: Long)

    private fun prune(dir: File, prefix: String, keepPerKey: Int, keepTotal: Int) {
        val pattern = Regex("^" + Regex.escape("$prefix-run-") + "(.+)-(\\d+)\\.log$")
        val entries = dir.listFiles().orEmpty().mapNotNull { f ->
            pattern.matchEntire(f.name)?.let { Entry(f, it.groupValues[1], it.groupValues[2].toLong()) }
        }.sortedByDescending { it.at }
        val perKey = HashMap<String, Int>()
        var kept = 0
        for (entry in entries) {
            val n = (perKey[entry.key] ?: 0) + 1
            perKey[entry.key] = n
            if (n > keepPerKey || kept >= keepTotal) entry.file.delete() else kept++
        }
    }
}
