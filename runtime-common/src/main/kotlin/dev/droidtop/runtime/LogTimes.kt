package dev.droidtop.runtime

import java.io.File
import java.io.RandomAccessFile

/**
 * When a program's log got its lines, for `logs --since` and `--until` (Droidtop/tracker#715). The program writes
 * its output straight to its log, so its lines carry no times. Instead, while the program runs, droidtop notes the
 * log's length each second it grew, in a file beside it ([fileFor]): "at this time the log was this long". A time
 * then reads as a place in the log, to the second, and the program's output is left exactly as it wrote it.
 */
object LogTimes {
    /** The log was [offset] bytes long at [ms]. */
    data class Mark(val offset: Long, val ms: Long)

    /** At most this many marks are kept: over a day of a program writing every second. */
    const val MAX_MARKS = 100_000

    /** Past this size the times file keeps its last [MAX_MARKS], for a log that grows too slowly to be trimmed. */
    const val MAX_FILE_BYTES = 4L * 1024 * 1024

    /** How often a running program's log is looked at. */
    const val MARK_EVERY_MS = 1_000L

    fun fileFor(log: File): File = File(log.parentFile, log.name.removeSuffix(".log") + ".times")

    fun read(times: File): List<Mark> {
        if (!times.isFile) return emptyList()
        return times.readLines().mapNotNull { line ->
            val parts = line.split(' ')
            val offset = parts.getOrNull(0)?.toLongOrNull()
            val ms = parts.getOrNull(1)?.toLongOrNull()
            if (offset == null || ms == null || offset < 0) null else Mark(offset, ms)
        }
    }

    fun append(times: File, mark: Mark) {
        times.appendText("${mark.offset} ${mark.ms}\n")
    }

    /**
     * The log lost its first [removed] bytes to a trim ([ContainerProgram.trim]): the marks move with the bytes, the
     * marks of bytes now gone are dropped, and past [MAX_MARKS] the oldest go too.
     */
    fun shift(times: File, removed: Long, maxMarks: Int = MAX_MARKS) {
        if (!times.isFile) return
        val kept = shifted(read(times), removed).takeLast(maxMarks)
        times.writeText(kept.joinToString("") { "${it.offset} ${it.ms}\n" })
    }

    internal fun shifted(marks: List<Mark>, removed: Long): List<Mark> =
        marks.mapNotNull { m -> (m.offset - removed).takeIf { it >= 0 }?.let { Mark(it, m.ms) } }

    /**
     * The bytes of a log [length] long written from [sinceMs] up to [untilMs], either open: from the length the log
     * had at the last mark not after [sinceMs] (the start without one), to its length at the first mark not before
     * [untilMs] (the end without one). Each mark is a second apart at most, so the range is right to the second.
     */
    fun range(marks: List<Mark>, length: Long, sinceMs: Long?, untilMs: Long?): LongRange {
        val start = sinceMs?.let { s -> marks.lastOrNull { it.ms <= s }?.offset } ?: 0L
        val end = untilMs?.let { u -> marks.firstOrNull { it.ms >= u }?.offset } ?: length
        return start.coerceIn(0, length) until end.coerceIn(0, length)
    }

    /**
     * The whole lines of [log] in [range], at most the last [maxBytes] of it, cut at line starts: a range that
     * begins inside a line begins at the next one.
     */
    fun lines(log: File, range: LongRange, maxBytes: Int = 1 shl 20): List<String> {
        if (!log.isFile || range.isEmpty()) return emptyList()
        val bytes = RandomAccessFile(log, "r").use { file ->
            val end = minOf(range.last + 1, file.length())
            val from = maxOf(range.first, end - maxBytes)
            if (end <= from) return emptyList()
            // One byte before, to see whether the range starts a line.
            val readFrom = if (from > 0) from - 1 else 0
            ByteArray((end - readFrom).toInt()).also {
                file.seek(readFrom)
                file.readFully(it)
            }.let { if (from > 0) it.copyOfRange(skipToLine(it), it.size) else it }
        }
        if (bytes.isEmpty()) return emptyList()
        return String(bytes, Charsets.UTF_8).removeSuffix("\n").split('\n')
    }

    /** In bytes read from one before the range: where its first whole line starts. */
    private fun skipToLine(bytes: ByteArray): Int {
        if (bytes[0] == '\n'.code.toByte()) return 1
        val newline = bytes.indexOf('\n'.code.toByte())
        return if (newline < 0) bytes.size else newline + 1
    }
}
