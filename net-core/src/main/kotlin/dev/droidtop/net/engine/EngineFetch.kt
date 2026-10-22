package dev.droidtop.net.engine

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The part of the download engine one transfer needs. [EngineHost] implements it over aria2; tests give it a fake. Blocking. */
interface TransferEngine {
    /** Adds a download of [url] with aria2's [options]; returns its gid. */
    fun add(url: String, options: Map<String, String>): String

    /** The download's state, or null when the engine no longer knows it. */
    fun status(gid: String): Transfer?

    /** Stops the download and drops it from the queue. Its partial file and aria2's control file stay, so a new `add` carries on. */
    fun remove(gid: String)
}

/** How a byte count reads in a status line; :net-core cannot reach the one formatter (ValueFormat, in :runtime-common), so the caller gives it. */
typealias SizeFormat = (bytes: Long) -> String

/** One download as the engine reports it: aria2's own state names (`active`, `waiting`, `paused`, `complete`, `error`, `removed`). */
data class Transfer(val state: String, val totalLength: Long, val completedLength: Long, val downloadSpeed: Long, val errorCode: Int)

/**
 * One report of a transfer: [percent] (-1 when the length is not known), the status line, the engine's numbers
 * ([transfer], null while there is no download in the engine), and whether the transfer is waiting for the connection.
 * The job turns it into its line, its numbers and its wait (docs/SPEC.md "Jobs and their results").
 */
data class EngineProgress(val percent: Int, val line: String, val transfer: Transfer? = null, val waiting: Boolean = false)

/** The engine cannot be reached (its process ended, or it is not started): the transfer waits and asks again. */
class EngineGoneException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A transfer that failed for good; [message] is a sentence a person reads. */
class TransferException(message: String) : Exception(message)

/**
 * One file to fetch: [url] with [headers] into [destination], whose folder is the engine's download folder. The engine
 * writes the file under its own name there, and its control file `<name>.aria2` beside it. [maxBytes] above 0 refuses a
 * longer file as soon as its length is known.
 */
class TransferRequest(
    val url: String,
    val headers: Map<String, String>,
    val destination: File,
    val maxBytes: Long = 0L,
) {
    /** The file the engine writes and the control file beside it. */
    fun files(): List<File> = listOf(destination, controlFile(destination))

    companion object {
        fun controlFile(file: File) = File(file.parentFile, file.name + ".aria2")
    }
}

/** What aria2 is told for a download, from the request. Pure. */
object EngineOptions {
    /**
     * The per-download options for [fileName] with [headers]. A `User-Agent` and a `Referer` among the headers become
     * aria2's own options of those names (it would otherwise send its own beside them); the rest go in one `header`
     * option, a line each. One connection per download: the sources droidtop gets files from share a session or a
     * single-use address between connections, and the earlier downloader used one.
     *
     * @throws TransferException when a header could carry another one (a line break) or has no usable name.
     */
    fun forRequest(fileName: String, headers: Map<String, String>): Map<String, String> {
        val options = linkedMapOf("out" to fileName, "split" to "1", "max-connection-per-server" to "1")
        val lines = mutableListOf<String>()
        for ((name, value) in headers) {
            if (name.isBlank() || ':' in name || name.any { it.isISOControl() || it == ' ' } || value.any { it == '\r' || it == '\n' }) {
                throw TransferException("a request header of this download is not valid")
            }
            when (name.lowercase()) {
                "user-agent" -> options["user-agent"] = value
                "referer" -> options["referer"] = value
                // aria2 manages these itself.
                "host", "range", "if-range", "connection", "content-length" -> Unit
                else -> lines += "$name: $value"
            }
        }
        if (lines.isNotEmpty()) options["header"] = lines.joinToString("\n")
        return options
    }
}

/** What an aria2 error code means for a transfer. Pure. */
object EngineErrors {
    sealed interface Verdict {
        /** The network failed; ask again from what is on disk. */
        object Retry : Verdict

        /** The server cannot carry on from the partial file; start the file again. */
        object Restart : Verdict

        class Fail(val message: String) : Verdict
    }

    /** aria2's exit-status numbers (`aria2c --help` "EXIT STATUS"). */
    fun classify(code: Int): Verdict = when (code) {
        2, 5, 6, 19, 29 -> Verdict.Retry
        8 -> Verdict.Restart
        3, 4 -> Verdict.Fail("the server does not have that file")
        9 -> Verdict.Fail("there is not enough space for this download")
        13 -> Verdict.Fail("a file of that name is already in droidtop's downloads folder")
        15, 16, 17, 18 -> Verdict.Fail("droidtop could not write the downloaded file")
        23 -> Verdict.Fail("the address redirects too many times")
        24 -> Verdict.Fail("the server refused the credentials")
        22 -> Verdict.Fail("the server refused the download")
        else -> Verdict.Fail("the download engine reported error $code")
    }
}

/**
 * The one runner for a single-file HTTP(S) transfer on the download engine (docs/SPEC.md 12a "One download engine"). It
 * adds the file to the engine, reports its progress, and ends in one of three ways:
 * - the file is whole: it returns;
 * - the job is cancelled, which is how a pause or a cancel reaches it: the download is removed from the engine and the
 *   partial file stays, so the next call carries on from it (aria2 `continue`, its control file beside the file);
 * - it failed: [TransferException] with a sentence.
 *
 * A network failure is asked again from the partial file with a backoff, each saying "Waiting for the connection"; a
 * failure that made progress since the last does not count, and six in a row without progress fail the transfer, as the
 * earlier downloader did. Pure over [TransferEngine] and a clock ([delay]), so it is unit tested on the JVM.
 */
object EngineFetch {
    const val POLL_MS = 1_000L
    const val MAX_FAILURES = 6

    /** Polls without a byte after which the status line says the connection is being waited for. */
    const val STALL_POLLS = 3

    private class End(val code: Int?, val completed: Long)

    suspend fun fetch(
        engine: TransferEngine,
        request: TransferRequest,
        report: (EngineProgress) -> Unit,
        size: SizeFormat,
        resolve: (String, Map<String, String>) -> RedirectGuard.Resolved = { url, headers -> RedirectGuard.resolve(url, headers) },
        io: CoroutineDispatcher = Dispatchers.IO,
    ) {
        var failures = 0
        var lastCompleted = -1L
        var restarted = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val end = try {
                attempt(engine, request, restarted, report, size, resolve, io)
            } catch (e: IOException) {
                // The engine's process ended, or the redirect walk found no network: ask again.
                currentCoroutineContext().ensureActive()
                End(RETRY_NETWORK, lastCompleted)
            }
            if (end.code == null) return
            val verdict = if (end.code == RETRY_NETWORK) EngineErrors.Verdict.Retry else EngineErrors.classify(end.code)
            when (verdict) {
                is EngineErrors.Verdict.Fail -> throw TransferException(verdict.message)
                EngineErrors.Verdict.Restart -> {
                    withContext(io) { request.files().forEach { it.delete() } }
                    restarted = true
                    failures = 0
                    lastCompleted = -1L
                }
                EngineErrors.Verdict.Retry -> {
                    failures = if (end.completed > lastCompleted) 1 else failures + 1
                    lastCompleted = maxOf(lastCompleted, end.completed)
                    if (failures > MAX_FAILURES) throw TransferException("the download failed: the connection kept failing")
                    report(EngineProgress(-1, "Waiting for the connection", waiting = true))
                    delay(backoff(failures))
                }
            }
        }
    }

    private const val RETRY_NETWORK = -1

    /** One add and watch. Null code: the file is whole. */
    private suspend fun attempt(
        engine: TransferEngine,
        request: TransferRequest,
        restarted: Boolean,
        report: (EngineProgress) -> Unit,
        size: SizeFormat,
        resolve: (String, Map<String, String>) -> RedirectGuard.Resolved,
        io: CoroutineDispatcher,
    ): End {
        val resolved = withContext(io) { resolve(request.url, request.headers) }
        val options = EngineOptions.forRequest(request.destination.name, resolved.headers)
        request.destination.parentFile?.let { withContext(io) { it.mkdirs() } }
        val gid = withContext(io) { engine.add(resolved.url, options) }
        var settled = false
        try {
            var completed = 0L
            var stalledPolls = 0
            while (true) {
                delay(POLL_MS)
                val transfer = withContext(io) { engine.status(gid) } ?: throw EngineGoneException("the engine no longer knows the download")
                when (transfer.state) {
                    "complete" -> {
                        settled = true
                        return End(null, transfer.completedLength)
                    }
                    "error" -> {
                        settled = true
                        return End(transfer.errorCode, maxOf(completed, transfer.completedLength))
                    }
                    "removed" -> throw EngineGoneException("the engine dropped the download")
                }
                if (request.maxBytes > 0 && transfer.totalLength > request.maxBytes) {
                    withContext(io) { engine.remove(gid) }
                    settled = true
                    withContext(io) { request.files().forEach { it.delete() } }
                    throw TransferException("the file is larger than the ${request.maxBytes / 1024 / 1024} MiB cap")
                }
                stalledPolls = if (transfer.completedLength > completed || transfer.downloadSpeed > 0) 0 else stalledPolls + 1
                completed = transfer.completedLength
                val stalled = stalledPolls >= STALL_POLLS
                report(EngineProgress(percentOf(transfer), line(transfer, restarted, stalled, size), transfer, waiting = stalled))
            }
        } finally {
            // A pause or a cancel ends the coroutine here: the engine stops the transfer, the partial file stays.
            if (!settled) withContext(NonCancellable + io) { runCatching { engine.remove(gid) } }
        }
    }

    internal fun percentOf(transfer: Transfer): Int =
        if (transfer.totalLength > 0) (transfer.completedLength * 100 / transfer.totalLength).toInt().coerceIn(0, 100) else -1

    /** The job's status line for a running transfer. */
    internal fun line(transfer: Transfer, restarted: Boolean, stalled: Boolean, size: SizeFormat): String {
        val prefix = if (restarted) "Started again: the server cannot resume. " else ""
        if (stalled) return prefix + "Waiting for the connection"
        if (transfer.completedLength == 0L && transfer.downloadSpeed == 0L) return prefix + "Connecting…"
        val done = size(transfer.completedLength)
        val sizes = if (transfer.totalLength > 0) "$done of ${size(transfer.totalLength)}" else done
        val speed = if (transfer.downloadSpeed > 0) " (${size(transfer.downloadSpeed)}/s)" else ""
        return "${prefix}Downloading… $sizes$speed"
    }

    /** 1 s doubling to 30 s, as the earlier downloader waited. */
    internal fun backoff(failures: Int): Long = (1_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(30_000L)
}
