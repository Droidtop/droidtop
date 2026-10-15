package dev.droidtop.runtime

import java.io.File
import java.io.RandomAccessFile

/**
 * An app container's own program, run the way Docker runs it and kept by droidtop (docs/SPEC.md 3 "The image's own
 * program", Droidtop/tracker#555 D6b): started and stopped from the container page, its output in a log of capped
 * size, restarted when it fails if the person wants that, and started with the desktop if they want that.
 */
object ContainerProgram {
    /** Where the logs live, under the app storage every container sees (`files/container-logs`). */
    const val LOG_DIR_NAME = "container-logs"

    /** The log is trimmed when it passes this... */
    const val LOG_MAX_BYTES = 1L * 1024 * 1024

    /** ...to its last this many bytes. */
    const val LOG_KEEP_BYTES = 512L * 1024

    /** How the waits between restarts grow: 2 s, 4 s, 8 s ... up to a minute. */
    fun restartDelayMs(failuresInARow: Int): Long = (2_000L shl (failuresInARow - 1).coerceIn(0, 5)).coerceAtMost(60_000L)

    /** A program that ran this long before it failed counts as having started, and its failure count starts over. */
    const val RAN_LONG_ENOUGH_MS = 30_000L

    /** After this many failures in a row, each sooner than [RAN_LONG_ENOUGH_MS], it is left stopped and says so. */
    const val MAX_FAILURES_IN_A_ROW = 5

    /** Whether to start the program again after it ended with [exitCode], [failuresInARow] quick failures so far. */
    fun restarts(restartOnFailure: Boolean, exitCode: Int, failuresInARow: Int): Boolean =
        restartOnFailure && exitCode != 0 && failuresInARow < MAX_FAILURES_IN_A_ROW

    fun hostLogFile(appStorageDir: File, containerId: String): File = File(File(appStorageDir, LOG_DIR_NAME), "$containerId.log")

    fun containerLogPath(containerId: String): String = "${ContainerLayout.APP_STORAGE_DIR}/$LOG_DIR_NAME/$containerId.log"

    /**
     * What runs in the container: the program in its working folder, with its output appended to the log. Through
     * `/bin/sh`, which an image without a shell (a distroless one) does not have; such an image cannot be run here yet.
     * The program and its arguments are argv entries, never shell text.
     */
    fun command(config: ImageRunConfig, containerId: String, args: List<String>? = null): List<String> =
        listOf(
            "/bin/sh",
            "-c",
            "log=\$1; shift; [ -n \"\$DROIDTOP_WORKDIR\" ] && cd \"\$DROIDTOP_WORKDIR\"; " +
                "echo \"--- started \$(date) ---\" >> \"\$log\"; exec \"\$@\" >> \"\$log\" 2>&1",
            "sh",
            containerLogPath(containerId),
        ) + config.program(args)

    /** The program's environment: the image's own `Env`, and its working folder for [command]. */
    fun environment(config: ImageRunConfig): Map<String, String> =
        config.environment() + (config.workingDir?.let { mapOf("DROIDTOP_WORKDIR" to it) } ?: emptyMap())

    /**
     * Keeps [log] under [LOG_MAX_BYTES]: past it, the file keeps its last [LOG_KEEP_BYTES], cut at a line start. The
     * file is rewritten in place, never replaced, because the running program holds it open for appending, and an
     * append always lands at the file's current end. Returns whether it trimmed.
     */
    fun trim(log: File, maxBytes: Long = LOG_MAX_BYTES, keepBytes: Long = LOG_KEEP_BYTES): Boolean {
        if (!log.isFile || log.length() <= maxBytes) return false
        RandomAccessFile(log, "rw").use { file ->
            val length = file.length()
            val tail = ByteArray(keepBytes.coerceAtMost(length).toInt())
            file.seek(length - tail.size)
            file.readFully(tail)
            val lineStart = tail.indexOf('\n'.code.toByte()).let { if (it < 0) 0 else it + 1 }
            file.seek(0)
            file.write(tail, lineStart, tail.size - lineStart)
            file.setLength((tail.size - lineStart).toLong())
        }
        return true
    }

    /** The log's last [lines] lines, for the container page. */
    fun lastLines(log: File, lines: Int = 40): List<String> {
        if (!log.isFile) return emptyList()
        val bytes = RandomAccessFile(log, "r").use { file ->
            val take = file.length().coerceAtMost(64L * 1024).toInt()
            ByteArray(take).also {
                file.seek(file.length() - take)
                file.readFully(it)
            }
        }
        return String(bytes, Charsets.UTF_8).trimEnd('\n').lines().takeLast(lines)
    }
}
