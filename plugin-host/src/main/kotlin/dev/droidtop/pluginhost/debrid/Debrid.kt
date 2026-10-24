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

/** The torrent a job asks a service for: its info hash (40 lower-case hex digits) and a magnet for it. */
data class TorrentRef(val infoHash: String, val magnet: String)

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

    /** A failure, in one sentence. [auth]: the key is wrong or expired; [permanent]: asking again cannot help. */
    data class Failed(val message: String, val auth: Boolean = false, val permanent: Boolean = false) : DebridState
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
}

/** The HTTP the providers speak, replaceable for the tests. */
fun interface DebridTransport {
    /** [method] GET, POST or DELETE; [form] sent url-encoded for a POST. Returns the status and the body. */
    fun call(method: String, url: String, headers: Map<String, String>, form: Map<String, String>?): Pair<Int, String>

    companion object {
        val HTTP = DebridTransport { method, url, headers, form ->
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            try {
                if (form != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val body = form.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val text = stream?.use { input -> readBounded(input) }.orEmpty()
                status to text
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

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

/** The services droidtop knows, in the order a new account list offers them. */
object DebridProviders {
    val all: List<DebridProvider> by lazy { listOf(TorBox(), RealDebrid()) }

    fun byId(id: String?): DebridProvider? = all.firstOrNull { it.id == id }
}
