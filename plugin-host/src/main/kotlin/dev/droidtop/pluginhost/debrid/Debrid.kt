package dev.droidtop.pluginhost.debrid

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/*
 * Debrid as a step of droidtop's download service (docs/SPEC.md "Download rules", Droidtop/tracker#638). The provider
 * flows are ported from romgi's Dart (lib/services/debrid/, Copyright (c) 2025 Christian Prado, MIT License; the
 * notice is in NOTICE.md), and widened from one file to a whole torrent.
 */

/**
 * The torrent a job asks a service for: its info hash (40 lower-case hex digits) and a magnet for it. [private]: its
 * `info` says `private` 1 (a private tracker's torrent, such as RPDL's), which is never handed to a debrid service.
 */
data class TorrentRef(val infoHash: String, val magnet: String, val private: Boolean = false)

/** One file a service has ready: its path inside the torrent, its size when known, and a direct address for it. */
data class DebridFile(val path: String, val size: Long?, val url: String)

/** Where a service stands with a torrent. */
sealed interface DebridState {
    /** Every file is ready to download from the service. */
    data class Ready(val files: List<DebridFile>) : DebridState

    /** The service is fetching it; [progress] 0 to 1 when it says. */
    data class Caching(val progress: Double?) : DebridState

    /** The service does not have it and will not fetch it (a dead torrent, or none asked: [DebridProvider.cached]). */
    data object NotCached : DebridState

    /**
     * A failure, in one sentence. [auth]: the key is wrong or expired; [permanent]: asking again cannot help; [limit]:
     * the account reached one of its limits, and rests until [restUntilMs] when the service says ([DebridChoice]).
     */
    data class Failed(
        val message: String,
        val auth: Boolean = false,
        val permanent: Boolean = false,
        val limit: Boolean = false,
        val restUntilMs: Long? = null,
    ) : DebridState
}

/**
 * One debrid service (TorBox, Real-Debrid, ...): behind this, every torrent job droidtop runs can be fetched from the
 * service instead of from peers. Blocking network calls: run off the main thread.
 */
interface DebridProvider {
    /** The id droidtop stores the account under ("torbox", "realdebrid"). */
    val id: String

    /** The service's own name, as the job line says it ("via TorBox"). */
    val name: String

    /** Whether [key] works: null when it does, else one sentence. */
    fun check(key: String): String?

    /** Whether the service already has [torrent] whole, without adding it. Null when the service cannot say. */
    fun cached(key: String, torrent: TorrentRef): Boolean?

    /** Adds [torrent] when the service does not have it yet (asking again is safe) and says where it stands. */
    fun request(key: String, torrent: TorrentRef): DebridState

    /**
     * Takes [torrent] back off the person's list on the service when droidtop put it there and the job did not use
     * it, so asking leaves nothing behind. One that was on the list before droidtop asked stays. Nothing by default.
     */
    fun release(key: String, torrent: TorrentRef) {}

    /**
     * The file-hoster domains the account can fetch a link from ("pixeldrain.com", "mega.nz"), as the service lists
     * them for this account now, never a list droidtop keeps. Empty for a service that fetches no hoster links.
     */
    fun hosts(key: String): Set<String> = emptySet()

    /** Whether the service already has [link]'s file, without asking it to fetch it. Null when it cannot say. */
    fun linkCached(key: String, link: String): Boolean? = null

    /** Whether the account's limit is torrents or downloads at once (slots), the kind spread mode shares out. */
    val slotLimited: Boolean get() = false

    /**
     * What serving a job costs the account now (a torrent, or a hoster [link]), from what the service reports of the
     * account, never from figures droidtop keeps. [DebridCost.Free] by default.
     */
    fun cost(key: String, link: String?): DebridCost = DebridCost.Free

    /** Asks the service for [link] on a file hoster (asking again is safe) and says where it stands: its files when ready. */
    fun unrestrict(key: String, link: String): DebridState = DebridState.Failed("$name does not fetch hoster links", permanent = true)
}

/** The HTTP the providers speak, replaceable for the tests. */
fun interface DebridTransport {
    /** [method] GET, POST or DELETE; [form] sent url-encoded for a POST. Returns the status and the body. */
    fun call(method: String, url: String, headers: Map<String, String>, form: Map<String, String>?): Pair<Int, String>

    /** A POST of [json] as the body, for an API that takes JSON (Mega's). A transport that does not send it says so. */
    fun postJson(url: String, json: String): Pair<Int, String> = throw IOException("This transport sends no JSON")

    companion object {
        val HTTP: DebridTransport = object : DebridTransport {
            override fun call(method: String, url: String, headers: Map<String, String>, form: Map<String, String>?) =
                exchange(
                    method, url, headers,
                    form?.let { fields ->
                        "application/x-www-form-urlencoded" to
                            fields.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
                    },
                )

            override fun postJson(url: String, json: String) = exchange("POST", url, emptyMap(), "application/json" to json)
        }

        /** One request: [body] is the content type and the text to send, or null for none. */
        private fun exchange(method: String, url: String, headers: Map<String, String>, body: Pair<String, String>?): Pair<Int, String> {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            try {
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", body.first)
                    connection.outputStream.use { it.write(body.second.toByteArray()) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val text = stream?.use { input -> readBounded(input) }.orEmpty()
                return status to text
            } catch (e: IOException) {
                throw IOException("The service could not be reached", e)
            } finally {
                connection.disconnect()
            }
        }

        private const val MAX_BODY = 4 * 1024 * 1024

        private fun readBounded(input: java.io.InputStream): String {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (out.size() < MAX_BODY) {
                val read = input.read(buffer, 0, minOf(buffer.size, MAX_BODY - out.size()))
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}

internal fun jsonOrNull(text: String): Any? = runCatching { JSONTokener(text).nextValue() }.getOrNull()

internal fun JSONObject.long(key: String): Long? = (opt(key) as? Number)?.toLong()

/** An ISO 8601 time ("2026-11-01T00:00:00Z", with an offset or fraction too) in ms, or null. */
internal fun isoMs(text: String?): Long? {
    if (text.isNullOrBlank() || text == "null") return null
    return runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

/** The services droidtop knows, in the order a new account list offers them. */
object DebridProviders {
    val all: List<DebridProvider> by lazy { listOf(TorBox(), RealDebrid()) }

    fun byId(id: String?): DebridProvider? = all.firstOrNull { it.id == id }
}
