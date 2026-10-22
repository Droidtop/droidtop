package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.EsDeArtwork
import dev.droidtop.net.DownloadCategory
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.NetScope
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A picture a metadata plugin names (docs/plugin-api.md 3 A3, `cover`), fetched by droidtop itself through the download
 * engine: the plugin never writes to the media folder. The address and every redirect stay https and on the plugin's
 * own `net.domains`; the answer must be a PNG, JPEG, WebP or GIF of at most [MAX_BYTES], and it reaches the media
 * folder whole or not at all.
 */
internal object PluginImages {
    const val MAX_BYTES = 10L * 1024 * 1024

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

    /**
     * Fetches [url] into [destination] as an Art & info download (docs/SPEC.md 12a, "Categories and pools"): the download
     * engine fetches it under the pool's limits, every address limited to the plugin's [domains] and the size to
     * [MAX_BYTES]; the file is then checked to be a picture and moved into place whole. Throws with the reason when any
     * rule refuses it.
     */
    suspend fun download(context: Context, url: String, domains: List<String>, destination: File) {
        if (!allowed(url, domains)) throw IOException("the picture's address is not on the plugin's sites")
        val name = "art_${System.nanoTime()}.img"
        val result = DownloadJobs.run(
            context = context,
            title = "Picture: ${destination.nameWithoutExtension}",
            post = DownloadJobs.POST_KEEP,
            url = url,
            name = name,
            maxBytes = MAX_BYTES,
            category = DownloadCategory.ART,
            allowSites = domains,
        )
        val fetched = DownloadJobs.fileFor(context, name)
        try {
            if (!result.ok) throw IOException(result.error ?: "the picture could not be downloaded")
            withContext(Dispatchers.IO) { place(fetched, destination) }
        } finally {
            fetched.delete()
        }
    }

    /** Moves [fetched] to [destination] when its first bytes say it is a picture droidtop shows; throws otherwise. */
    internal fun place(fetched: File, destination: File) {
        val head = fetched.inputStream().use { input -> ByteArray(16).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }
        if (kindOf(head) == null) throw IOException("not a picture droidtop shows")
        if (fetched.length() > MAX_BYTES) throw IOException("larger than ${MAX_BYTES / (1024 * 1024)} MiB")
        destination.parentFile?.mkdirs()
        DownloadJobs.moveFile(fetched, destination)
        EsDeArtwork.mediaWritten(destination)
    }
}
