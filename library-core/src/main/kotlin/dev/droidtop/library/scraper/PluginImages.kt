package dev.droidtop.library.scraper

import dev.droidtop.library.EsDeArtwork
import dev.droidtop.pluginhost.NetScope
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A picture a metadata plugin names (docs/plugin-api.md 3 A3, `cover`), fetched by droidtop itself: the plugin never
 * writes to the media folder. The address and every redirect stay https and on the plugin's own `net.domains`; the
 * answer must be a PNG, JPEG, WebP or GIF of at most [MAX_BYTES], and it reaches [destination] whole or not at all.
 */
internal object PluginImages {
    const val MAX_BYTES = 10L * 1024 * 1024
    private const val MAX_REDIRECTS = 3

    /** Whether [url] is one droidtop fetches for a plugin that declared [domains]: https, on one of them. */
    fun allowed(url: String, domains: List<String>): Boolean {
        if (!NetScope.isHttps(url)) return false
        val host = NetScope.hostOf(url) ?: return false
        return domains.any { NetScope.matches(host, it) }
    }

    /** The picture's kind from its first bytes, or null when it is not one droidtop shows. */
    fun kindOf(head: ByteArray): String? = when {
        head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte() -> "png"
        head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> "jpeg"
        head.size >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        head.size >= 6 && String(head, 0, 6, Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" } -> "gif"
        else -> null
    }

    /** Fetches [url] into [destination]; throws with the reason when any rule above refuses it. */
    fun download(url: String, domains: List<String>, destination: File) {
        var address = url
        var hops = 0
        while (true) {
            if (!allowed(address, domains)) throw IOException("the picture's address is not on the plugin's sites")
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val next = connection.getHeaderField("Location") ?: throw IOException("a redirect without an address")
                    if (++hops > MAX_REDIRECTS) throw IOException("too many redirects")
                    address = URL(URL(address), next).toString()
                    continue
                }
                if (status != 200) throw IOException("HTTP $status")
                if (connection.contentLengthLong > MAX_BYTES) throw IOException("larger than ${MAX_BYTES / (1024 * 1024)} MiB")
                save(connection, destination)
                return
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun save(connection: HttpURLConnection, destination: File) {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, ".${destination.name}.part")
        try {
            connection.inputStream.use { input ->
                // The first bytes say what the answer is, before anything is kept.
                val head = ByteArray(16)
                var have = 0
                while (have < head.size) {
                    val read = input.read(head, have, head.size - have)
                    if (read < 0) break
                    have += read
                }
                if (kindOf(head.copyOf(have)) == null) throw IOException("not a picture droidtop shows")
                partial.outputStream().use { output ->
                    output.write(head, 0, have)
                    val buffer = ByteArray(64 * 1024)
                    var total = have.toLong()
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw IOException("larger than ${MAX_BYTES / (1024 * 1024)} MiB")
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (!partial.renameTo(destination)) throw IOException("could not store the picture")
            EsDeArtwork.mediaWritten(destination)
        } finally {
            partial.delete()
        }
    }
}
