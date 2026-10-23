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

    /**
     * Lets the engine reach [address] at [port] on the local network for an HTTP download droidtop sent there itself, and
     * takes that back ([LocalAddresses]). Counted. Without them a local address is refused, which is what a torrent's
     * peers always get.
     */
    fun allowLocal(address: java.net.InetAddress, port: Int) {}

    fun revokeLocal(address: java.net.InetAddress, port: Int) {}

    /**
     * The addresses the engine read from a torrent or Metalink droidtop added: each file's web seeds or mirrors and a
     * torrent's trackers. droidtop never reads those files itself; it vouches for the local ones ([allowLocal]).
     */
    fun reported(gid: String): List<String> = emptyList()

    /** Starts a download added with aria2's `pause` option, once droidtop has given its passes. */
    fun resume(gid: String) {}

    /** Adds a torrent, magnet or Metalink download whose files land in [folder], under the engine's folder; returns its gid. */
    fun addSwarm(source: SwarmSource, folder: File, options: Map<String, String>): String =
        throw TransferException("this download engine cannot fetch torrents")
}

/** What a download of several files starts from. The engine reads the bytes; droidtop never parses them. */
sealed interface SwarmSource {
    class Magnet(val uri: String) : SwarmSource

    class Torrent(val bytes: ByteArray) : SwarmSource

    class Metalink(val bytes: ByteArray) : SwarmSource

    companion object {
        private val MAGNET = Regex("(?i)^magnet:\\?(.*&)?xt=urn:bt(ih|mh):[^&]+")

        /** Whether [uri] is a BitTorrent magnet link (one naming its torrent), not just any `magnet:`. */
        fun isMagnet(uri: String): Boolean = MAGNET.containsMatchIn(uri)
    }
}

/** How a byte count reads in a status line; :net-core cannot reach the one formatter (ValueFormat, in :runtime-common), so the caller gives it. */
typealias SizeFormat = (bytes: Long) -> String

/** One download as the engine reports it: aria2's own state names (`active`, `waiting`, `paused`, `complete`, `error`, `removed`). */
data class Transfer(
    val state: String,
    val totalLength: Long,
    val completedLength: Long,
    val downloadSpeed: Long,
    val errorCode: Int,
    /** The downloads this one became when it finished: a magnet's torrent, a Metalink's files. */
    val followedBy: List<String> = emptyList(),
)

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

/**
 * A download of several files: [source]'s files land in [folder], a folder of their own in the engine's download folder,
 * which is the download's whole: moving [folder] moves it, and a new fetch into it carries on from what is there.
 * [maxBytes] above 0 refuses a larger download as soon as its length is known.
 */
class SwarmRequest(val source: SwarmSource, val folder: File, val maxBytes: Long = 0L)

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
        /** A host's addresses, for the local-network pass ([LocalAddresses.passesFor]). */
        lookup: (String) -> List<java.net.InetAddress> = { java.net.InetAddress.getAllByName(it).toList() },
        /** Where an address ends up after its redirects, for saying why the engine refused it ([refusal]). */
        follow: (String) -> String = { RedirectGuard.resolve(it, emptyMap(), allow = {}).url },
    ) {
        var restarted = false
        retrying(
            attempt = { attempt(engine, request, restarted, report, size, resolve, io, lookup, follow) },
            restart = {
                withContext(io) { request.files().forEach { it.delete() } }
                restarted = true
            },
            report = report,
        )
    }

    /**
     * The runner for a torrent, a magnet or a Metalink ([SwarmRequest]): the same three endings and the same patience
     * with the network as [fetch]. A magnet's first download is only the torrent's details, and a Metalink's names its
     * files; the engine then names the downloads it became ([Transfer.followedBy]), and the transfer is whole when
     * every one of those is. A pause or a cancel removes them all; their files stay in the folder, so the next call
     * carries on. Nothing here walks redirects: the engine reaches the peers and the Metalink's mirrors itself.
     */
    suspend fun fetchSwarm(
        engine: TransferEngine,
        request: SwarmRequest,
        report: (EngineProgress) -> Unit,
        size: SizeFormat,
        io: CoroutineDispatcher = Dispatchers.IO,
        /** A host's addresses, for the local-network passes ([LocalAddresses.passesFor]). */
        lookup: (String) -> List<java.net.InetAddress> = { java.net.InetAddress.getAllByName(it).toList() },
    ) {
        val source = request.source
        if (source is SwarmSource.Magnet && !SwarmSource.isMagnet(source.uri)) throw TransferException("that is not a magnet link")
        retrying(
            attempt = { swarmAttempt(engine, request, report, size, io, lookup) },
            // aria2's "cannot resume" is about one server's file; a torrent's pieces are checked, not restarted.
            restart = { throw TransferException("the download could not carry on from what was already fetched") },
            report = report,
        )
    }

    /** Runs [attempt] until the file is whole, asking again after a network failure with a backoff. */
    private suspend fun retrying(attempt: suspend () -> End, restart: suspend () -> Unit, report: (EngineProgress) -> Unit) {
        var failures = 0
        var lastCompleted = -1L
        while (true) {
            currentCoroutineContext().ensureActive()
            val end = try {
                attempt()
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
                    restart()
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

    /** aria2's "unknown error", which is also what a connect the engine's broker refused comes back as. */
    private const val UNKNOWN_ERROR = 1

    /**
     * Why the engine's broker refused [url], in a sentence, when that is what an [UNKNOWN_ERROR] was; null otherwise.
     * The broker refuses every local address it holds no pass for (docs/SPEC.md 12a, "The local network"): a download
     * droidtop sent to the person's own server had one ([hadPass]), so its refusal means that server could not be
     * reached; one that had none and ends on the local network got there by a redirect aria2 followed, from a site
     * onto the person's own network, which droidtop never lets through.
     */
    internal fun refusal(url: String, hadPass: Boolean, lookup: (String) -> List<java.net.InetAddress>, follow: (String) -> String): String? {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        if (hadPass) return "droidtop could not reach $host on your local network"
        val end = runCatching { follow(url) }.getOrNull() ?: return null
        if (LocalAddresses.passesFor(end, lookup).isEmpty()) return null
        val local = runCatching { java.net.URI(end).host }.getOrNull() ?: end
        return "$host sent this download on to $local, an address on your local network, and droidtop does not follow a site onto your own network"
    }

    /** One add and watch. Null code: the file is whole. */
    private suspend fun attempt(
        engine: TransferEngine,
        request: TransferRequest,
        restarted: Boolean,
        report: (EngineProgress) -> Unit,
        size: SizeFormat,
        resolve: (String, Map<String, String>) -> RedirectGuard.Resolved,
        io: CoroutineDispatcher,
        lookup: (String) -> List<java.net.InetAddress>,
        follow: (String) -> String,
    ): End {
        val resolved = withContext(io) { resolve(request.url, request.headers) }
        val options = EngineOptions.forRequest(request.destination.name, resolved.headers)
        request.destination.parentFile?.let { withContext(io) { it.mkdirs() } }
        // The address droidtop sends this download to may be the person's own server (a NAS, a tailnet machine): the
        // engine gets a pass for exactly it while the download runs. Nothing else local is reachable, torrents included.
        val passes = withContext(io) { LocalAddresses.passesFor(resolved.url, lookup) }
        val opened = mutableListOf<Pair<java.net.InetAddress, Int>>()
        var gid: String? = null
        var settled = false
        try {
            withContext(io) { passes.forEach { pass -> engine.allowLocal(pass.first, pass.second).also { opened += pass } } }
            gid = withContext(io) { engine.add(resolved.url, options) }
            var completed = 0L
            var stalledPolls = 0
            while (true) {
                delay(POLL_MS)
                val transfer = withContext(io) { engine.status(gid!!) } ?: throw EngineGoneException("the engine no longer knows the download")
                when (transfer.state) {
                    "complete" -> {
                        settled = true
                        return End(null, transfer.completedLength)
                    }
                    "error" -> {
                        settled = true
                        if (transfer.errorCode == UNKNOWN_ERROR) {
                            withContext(io) { refusal(resolved.url, passes.isNotEmpty(), lookup, follow) }?.let { throw TransferException(it) }
                        }
                        return End(transfer.errorCode, maxOf(completed, transfer.completedLength))
                    }
                    "removed" -> throw EngineGoneException("the engine dropped the download")
                }
                if (request.maxBytes > 0 && transfer.totalLength > request.maxBytes) {
                    withContext(io) { engine.remove(gid!!) }
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
            withContext(NonCancellable + io) {
                // A pause or a cancel ends the coroutine here: the engine stops the transfer, the partial file stays.
                if (!settled) gid?.let { runCatching { engine.remove(it) } }
                opened.forEach { (address, port) -> runCatching { engine.revokeLocal(address, port) } }
            }
        }
    }

    /** One add and watch of a swarm download, following what it became. Null code: every file is whole. */
    private suspend fun swarmAttempt(
        engine: TransferEngine,
        request: SwarmRequest,
        report: (EngineProgress) -> Unit,
        size: SizeFormat,
        io: CoroutineDispatcher,
        lookup: (String) -> List<java.net.InetAddress>,
    ): End {
        withContext(io) { request.folder.mkdirs() }
        // droidtop vouches for every address it handed the engine (the owner: "droidtop can vouch for all local links"):
        // a magnet's own trackers and web seeds before it is added; a torrent's or Metalink's, which only the engine
        // reads, while it waits added but paused. What the swarm itself brings (DHT, PEX, a tracker's peers) gets none.
        val source = request.source
        val readByEngine = source !is SwarmSource.Magnet
        // On a new attempt the pieces already in the folder are checked and kept, not fetched again.
        val options = mapOf("check-integrity" to "true") + if (readByEngine) mapOf("pause" to "true") else emptyMap()
        val opened = mutableListOf<Pair<java.net.InetAddress, Int>>()
        val live = mutableListOf<String>()
        var settled = false
        try {
            suspend fun vouch(addresses: List<String>) = withContext(io) {
                addresses.flatMap { LocalAddresses.passesFor(it, lookup) }.distinct().filter { it !in opened }.forEach { pass ->
                    engine.allowLocal(pass.first, pass.second)
                    opened += pass
                }
            }
            if (source is SwarmSource.Magnet) vouch(LocalAddresses.magnetAddresses(source.uri))
            val gid = withContext(io) { engine.addSwarm(source, request.folder, options) }
            live += gid
            if (readByEngine) {
                vouch(withContext(io) { engine.reported(gid) })
                withContext(io) { engine.resume(gid) }
            }
            var completed = 0L
            var stalledPolls = 0
            while (true) {
                delay(POLL_MS)
                val now = live.toList().map { gid -> gid to (withContext(io) { engine.status(gid) } ?: throw EngineGoneException("the engine no longer knows the download")) }
                now.firstOrNull { it.second.state == "error" }?.let { (failedGid, failed) ->
                    settled = true
                    withContext(io) { live.filter { it != failedGid }.forEach { runCatching { engine.remove(it) } } }
                    return End(failed.errorCode, maxOf(completed, now.sumOf { it.second.completedLength }))
                }
                if (now.any { it.second.state == "removed" }) throw EngineGoneException("the engine dropped the download")
                for ((gid, transfer) in now) {
                    if (transfer.state == "complete" && transfer.followedBy.isNotEmpty()) {
                        live.remove(gid)
                        live.addAll(transfer.followedBy.filter { it !in live })
                    }
                }
                val leaves = now.filter { it.first in live }.map { it.second }
                // The poll a download became others: they are asked about on the next one.
                if (leaves.isEmpty()) continue
                if (leaves.size == live.size && leaves.all { it.state == "complete" }) {
                    settled = true
                    return End(null, leaves.sumOf { it.completedLength })
                }
                val total = Transfer(
                    "active",
                    leaves.sumOf { it.totalLength },
                    leaves.sumOf { it.completedLength },
                    leaves.sumOf { it.downloadSpeed },
                    0,
                )
                if (request.maxBytes > 0 && total.totalLength > request.maxBytes) {
                    settled = true
                    withContext(io) { live.forEach { runCatching { engine.remove(it) } } }
                    throw TransferException("the download is larger than the ${request.maxBytes / 1024 / 1024} MiB cap")
                }
                stalledPolls = if (total.completedLength > completed || total.downloadSpeed > 0) 0 else stalledPolls + 1
                completed = total.completedLength
                val waitingForDetails = request.source is SwarmSource.Magnet && live.size == 1 && leaves.singleOrNull()?.totalLength == 0L
                val stalled = stalledPolls >= STALL_POLLS
                val line = if (waitingForDetails) "Fetching the torrent's details…" else line(total, false, stalled, size)
                report(EngineProgress(percentOf(total), line, total, waiting = stalled && !waitingForDetails))
            }
        } finally {
            withContext(NonCancellable + io) {
                if (!settled) live.forEach { runCatching { engine.remove(it) } }
                opened.forEach { (address, port) -> runCatching { engine.revokeLocal(address, port) } }
            }
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
