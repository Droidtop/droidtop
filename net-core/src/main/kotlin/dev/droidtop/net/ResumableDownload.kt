package dev.droidtop.net

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * droidtop's one single-file downloader (docs/SPEC.md 12a, "Downloads"): a plain GET that carries on from a
 * `.part` file with a `Range` request, so a pause, a lost connection, a network change or a restart costs only the
 * bytes in flight. Blocking: call from an IO dispatcher.
 *
 * The partial file `<name>.part` has a small sidecar `<name>.part.meta` holding what the server said about the file
 * (its `ETag` and `Last-Modified`, and its length). A resume sends `Range: bytes=<have>-` with `If-Range` set to the
 * strong ETag, else the `Last-Modified`; a server that has the same file answers 206 and the bytes are appended, one
 * whose file changed or that does not do ranges answers 200 with the whole file and the download starts again from
 * zero, which [Result.restarted] and [Progress.restarted] say. With no validator at all nothing can be trusted and it
 * starts again. Credential headers are dropped from a redirect to another host.
 */
object ResumableDownload {
    private const val CHUNK = 64 * 1024
    private val SENSITIVE = setOf("authorization", "proxy-authorization", "cookie")

    /** Bytes written so far and the file's length when the server said (-1 otherwise). */
    class Progress(val bytes: Long, val total: Long, val restarted: Boolean)

    class Result(val file: File, val restarted: Boolean)

    class TooLargeException(val maxBytes: Long) : IOException("the file is larger than the ${maxBytes / 1024 / 1024} MiB cap")

    /** The caller's way to stop a transfer that is blocked on the network: [cancel] closes the live connection. */
    class Handle {
        @Volatile private var connection: HttpURLConnection? = null

        @Volatile var cancelled = false
            private set

        internal fun attach(c: HttpURLConnection?) {
            connection = c
            if (cancelled) c?.disconnect()
        }

        fun cancel() {
            cancelled = true
            connection?.disconnect()
        }
    }

    /** What the sidecar remembers about the partial file. */
    data class Meta(val etag: String?, val lastModified: String?, val total: Long) {
        /** The `If-Range` value: only a strong ETag or a date may be sent, a weak ETag may not. */
        fun validator(): String? = etag?.takeIf { !it.startsWith("W/") } ?: lastModified

        fun encode(): String = listOf(etag.orEmpty(), lastModified.orEmpty(), total.toString()).joinToString("\n")

        companion object {
            fun decode(text: String?): Meta? {
                val lines = text?.split("\n") ?: return null
                if (lines.size < 3) return null
                return Meta(lines[0].ifEmpty { null }, lines[1].ifEmpty { null }, lines[2].toLongOrNull() ?: return null)
            }
        }
    }

    /** A `Content-Range` value: the first byte (-1 for the "unsatisfied" form a 416 sends) and the whole length (-1 if unknown). */
    fun parseContentRange(header: String?): Pair<Long, Long>? {
        val m = Regex("""^\s*bytes\s+(?:(\d+)-\d+|\*)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE).matchEntire(header ?: return null) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: -1L
        return start to (m.groupValues[2].toLongOrNull() ?: -1L)
    }

    fun partOf(file: File) = File(file.parentFile, file.name + ".part")

    fun metaOf(file: File) = File(file.parentFile, file.name + ".part.meta")

    /** Removes what a download of [file] left behind (the partial file and its sidecar). */
    fun discard(file: File) {
        partOf(file).delete()
        metaOf(file).delete()
    }

    /**
     * Downloads [url] to [file], resuming a partial one when [resume] and the server agrees. Retries a broken
     * connection or a 5xx/429 up to [retries] times without progress, resuming each time ([waiting] says why it waits
     * and [sleep] waits). [maxBytes] > 0 refuses a longer file. Throws [Http.HttpException] for any other HTTP
     * status, [TooLargeException], or an [IOException] when cancelled through [handle].
     */
    fun fetch(
        url: String,
        file: File,
        headers: Map<String, String> = emptyMap(),
        timeouts: Http.Timeouts = Http.Timeouts(20_000, 30_000),
        resume: Boolean = true,
        maxBytes: Long = 0L,
        handle: Handle = Handle(),
        retries: Int = 6,
        sleep: ((Long) -> Unit)? = null,
        waiting: (String) -> Unit = {},
        onProgress: (Progress) -> Unit = {},
    ): Result {
        file.parentFile?.mkdirs()
        val part = partOf(file)
        val meta = metaOf(file)
        var restarted = false
        var failures = 0
        var lastSize = -1L
        if (!resume) discard(file)
        // The default wait ends early when the transfer is cancelled, so a pause never waits out a backoff.
        val wait: (Long) -> Unit = sleep ?: { ms ->
            var left = ms
            while (left > 0 && !handle.cancelled) {
                Thread.sleep(minOf(200L, left))
                left -= 200L
            }
        }
        while (true) {
            if (handle.cancelled) throw IOException("download cancelled")
            try {
                val outcome = attempt(url, file, headers, timeouts, maxBytes, handle, restarted, onProgress)
                restarted = restarted || outcome.restarted
                if (outcome.done) return Result(file, restarted)
            } catch (e: TooLargeException) {
                discard(file)
                throw e
            } catch (e: Http.HttpException) {
                if (handle.cancelled) throw IOException("download cancelled")
                if (e.status != 429 && e.status < 500) {
                    if (e.status == 416) discard(file)
                    throw e
                }
                failures = nextFailure(failures, lastSize, part, retries, e)
                lastSize = part.length()
                waiting("Waiting to retry")
                wait(backoff(failures))
                continue
            } catch (e: IOException) {
                if (handle.cancelled) throw IOException("download cancelled")
                failures = nextFailure(failures, lastSize, part, retries, e)
                lastSize = part.length()
                waiting("Waiting for the connection")
                wait(backoff(failures))
                continue
            }
            // attempt() asked for another go (a changed file answered a range): start over from nothing.
            restarted = true
            discard(file)
            meta.delete()
        }
    }

    /** A failure that made progress since the last one does not count against the retries. */
    private fun nextFailure(failures: Int, lastSize: Long, part: File, retries: Int, cause: IOException): Int {
        val next = if (part.length() > lastSize) 1 else failures + 1
        if (next > retries) throw cause
        return next
    }

    internal fun backoff(failures: Int): Long = (1_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(30_000L)

    private class Outcome(val done: Boolean, val restarted: Boolean)

    private fun attempt(
        url: String,
        file: File,
        headers: Map<String, String>,
        timeouts: Http.Timeouts,
        maxBytes: Long,
        handle: Handle,
        alreadyRestarted: Boolean,
        onProgress: (Progress) -> Unit,
    ): Outcome {
        val part = partOf(file)
        val metaFile = metaOf(file)
        val known = Meta.decode(runCatching { metaFile.readText() }.getOrNull())
        var have = if (part.isFile && known != null) part.length() else 0L
        val validator = known?.validator()
        if (have > 0 && validator == null) have = 0L
        if (have == 0L) discard(file)

        val sent = HashMap(headers)
        if (have > 0) {
            sent["Range"] = "bytes=$have-"
            sent["If-Range"] = validator!!
        }
        val c = open(url, sent, timeouts, handle)
        try {
            val status = c.responseCode
            var restarted = false
            val total: Long
            val append: Boolean
            when {
                status == 206 -> {
                    val range = parseContentRange(c.getHeaderField("Content-Range"))
                    if (range == null || range.first != have) return Outcome(done = false, restarted = true)
                    total = range.second.takeIf { it >= 0 } ?: (known?.total ?: -1L)
                    append = true
                }
                status == 200 -> {
                    restarted = have > 0
                    total = c.contentLengthLong
                    append = false
                }
                status == 416 && have > 0 -> {
                    val whole = parseContentRange(c.getHeaderField("Content-Range"))?.second ?: -1L
                    if (whole == have) {
                        finish(file)
                        return Outcome(done = true, restarted = false)
                    }
                    return Outcome(done = false, restarted = true)
                }
                else -> throw Http.HttpException(
                    status,
                    c.headerFields.entries.mapNotNull { (name, values) ->
                        name?.takeIf { it.startsWith("X-RateLimit-", ignoreCase = true) }?.lowercase()?.let { it to values.firstOrNull() }
                    }.toMap(),
                    "HTTP $status from $url",
                )
            }
            if (maxBytes > 0 && total > maxBytes) throw TooLargeException(maxBytes)
            if (!append) part.delete()
            val etag = c.getHeaderField("ETag") ?: known?.etag.takeIf { append }
            val modified = c.getHeaderField("Last-Modified") ?: known?.lastModified.takeIf { append }
            metaFile.writeText(Meta(etag, modified, total).encode())

            var count = if (append) have else 0L
            val showRestart = alreadyRestarted || restarted
            onProgress(Progress(count, total, showRestart))
            RandomAccessFile(part, "rw").use { out ->
                if (append) out.seek(have) else out.setLength(0)
                c.inputStream.use { input ->
                    val buffer = ByteArray(CHUNK)
                    while (true) {
                        if (handle.cancelled) throw IOException("download cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        count += n
                        if (maxBytes > 0 && count > maxBytes) throw TooLargeException(maxBytes)
                        onProgress(Progress(count, total, showRestart))
                    }
                }
            }
            if (total >= 0 && count != total) throw IOException("the connection ended after $count of $total bytes")
            finish(file)
            return Outcome(done = true, restarted = restarted)
        } finally {
            handle.attach(null)
            c.disconnect()
        }
    }

    private fun finish(file: File) {
        val part = partOf(file)
        file.delete()
        if (!part.renameTo(file)) throw IOException("could not move the downloaded file into place")
        metaOf(file).delete()
    }

    /** One GET with redirects followed by hand, so credentials never travel to another host. */
    private fun open(url: String, headers: Map<String, String>, timeouts: Http.Timeouts, handle: Handle): HttpURLConnection {
        var current = url
        var sent = headers
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            handle.attach(c)
            c.instanceFollowRedirects = false
            c.connectTimeout = timeouts.connectMs
            c.readTimeout = timeouts.readMs
            c.setRequestProperty("User-Agent", Http.USER_AGENT)
            c.setRequestProperty("Accept-Encoding", "identity")
            sent.forEach(c::setRequestProperty)
            val status = c.responseCode
            val location = c.getHeaderField("Location")
            if (status !in 300..399 || status == 304 || location == null) return c
            c.disconnect()
            val next = URL(URL(current), location)
            if (next.host != URL(current).host) sent = sent.filterKeys { it.lowercase() !in SENSITIVE }
            current = next.toString()
        }
        throw IOException("more than 5 redirects from $url")
    }
}
