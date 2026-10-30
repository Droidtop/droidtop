package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.AcquireDownloadDescriptor
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.SplitArchives
import dev.droidtop.pluginhost.WebSessionDownload
import dev.droidtop.pluginhost.WebSessions
import java.io.File
import org.json.JSONObject

/**
 * A source's links, kept and opened by droidtop (docs/SPEC.md 7g, "Getting an update"; Droidtop/tracker#599): the
 * owner, "We shouldn't be fetching links again ... we should cache links as well, and then use the webview to open
 * them. The user can start the download, and it'll be kicked over automatically."
 */
object UpdateLinks {
    /**
     * Whether files of [names] are unpacked: one archive, or every part of one split archive, numbered parts or RAR
     * volumes alike (DownloadJobs.placeSplitSet unpacks both since a3a51932). Pure.
     */
    fun unpackFor(names: List<String>): Boolean = when {
        names.isEmpty() -> false
        names.size == 1 -> DownloadJobs.archiveStem(names.single()) != null
        else -> SplitArchives.detect(names) != null
    }

    /** The files of a release in parts, gathered from wherever each came, unpacked by the one rule over all their names. */
    fun joined(descriptors: List<AcquireDownloadDescriptor>): List<AcquireDownloadDescriptor> {
        val unpack = unpackFor(descriptors.map { it.fileName })
        return descriptors.map { it.copy(unpack = unpack) }
    }

    /**
     * Every one of [parts], a release in parts, gathered as one set to place as one Downloads job (docs/plugin-api.md
     * 1.10, `download` `parts`): a part with a page opens in droidtop's web view, where the person starts it as for any
     * link (#599), and a part with a token goes to [byToken], the source's own job, given the token and which part it is.
     * The one path the update record page and a source's item page both take. Nothing is placed until every part is in
     * hand: one that brings nothing fails it all, saying which. The files come back joined ([joined]). A part whose page
     * says its link has expired is asked of the source again through [again], the same release's parts in the record
     * asked again (docs/plugin-api.md 1.11, "One link path: the record"), once.
     */
    suspend fun gatherParts(
        context: android.content.Context,
        record: dev.droidtop.pluginhost.PluginRecord,
        parts: List<dev.droidtop.library.UpdateSources.Download.Part>,
        onStatus: (String) -> Unit,
        again: (suspend () -> List<dev.droidtop.library.UpdateSources.Download.Part>?)? = null,
        byToken: suspend (link: String, which: String) -> List<AcquireDownloadDescriptor>,
    ): Result<List<AcquireDownloadDescriptor>> {
        val gathered = mutableListOf<AcquireDownloadDescriptor>()
        for ((index, part) in parts.withIndex()) {
            val which = "Part ${index + 1} of ${parts.size}"
            val url = part.url
            val got = if (url != null) {
                onStatus("$which: opening the link from ${record.manifest.label}")
                val refresh = again?.let { ask ->
                    {
                        onStatus("$which: that link had expired. Asking ${record.manifest.label} for a current one")
                        kotlinx.coroutines.runBlocking { ask() }?.let { partAgain(parts, index, it) }
                    }
                }
                descriptorsFor(dev.droidtop.pluginhost.PluginWebAccount.openForDownloads(context, record, url, 1, refresh))
            } else {
                byToken(part.link.orEmpty(), which)
            }
            if (got.isEmpty()) return Result.failure(IllegalStateException("$which (${part.label}) was not downloaded, so nothing was added"))
            gathered += got
        }
        return Result.success(joined(gathered))
    }

    /**
     * Part [index]'s address in [fresh], the same release's parts in the record asked again: null when the release now
     * has a different number of parts (the source changed it, so which part is which is not known) or the address is
     * the one that expired. Pure.
     */
    fun partAgain(
        parts: List<dev.droidtop.library.UpdateSources.Download.Part>,
        index: Int,
        fresh: List<dev.droidtop.library.UpdateSources.Download.Part>,
    ): String? {
        if (fresh.size != parts.size) return null
        return fresh[index].url?.takeIf { it != parts[index].url }
    }

    /**
     * What the web view captured as the downloads droidtop runs: each file under the name the site gave it (made safe for
     * a file system), with the cookies, user agent and referrer the page used, held in memory by Downloads. A zip, 7z or
     * rar, or the numbered parts of one split archive, is unpacked into the new version's folder. Pure, for the tests.
     */
    fun descriptorsFor(captured: List<WebSessionDownload>): List<AcquireDownloadDescriptor> {
        val names = captured.map { WebSessions.safeFileName(WebSessions.fileNameFor(it.url, it.contentDisposition)) }
        val unpack = unpackFor(names)
        return captured.mapIndexed { index, download ->
            AcquireDownloadDescriptor(
                url = download.url,
                headers = buildMap {
                    // A value with a line break would add a header of its own: such a value is dropped.
                    fun add(name: String, value: String?) {
                        value?.trim()?.takeIf { it.isNotEmpty() && '\n' !in it && '\r' !in it }?.let { put(name, it) }
                    }
                    add("Cookie", download.cookie)
                    add("User-Agent", download.userAgent)
                    add("Referer", download.referer)
                    download.headers.forEach { (name, value) -> add(name, value) }
                },
                fileName = names[index],
                sha256 = download.sha256,
                size = download.size?.takeIf { it > 0 },
                unpack = unpack,
            )
        }
    }
}

/**
 * The last page each source gave for a record (`library.updates` `record`), kept so the record page shows at once and
 * its links are the ones the person picks from, never fetched again to download (Droidtop/tracker#599). One small
 * file per record under [dir]; a record the source can no longer give is shown from here, with when it was saved.
 */
class RecordCache(private val dir: File) {
    data class Saved(val record: JSONObject, val savedAtMs: Long)

    private fun fileFor(source: String, id: String): File? {
        val safe = Regex("[A-Za-z0-9._-]{1,120}")
        if (!source.matches(safe) || !id.matches(safe) || source.startsWith(".") || id.startsWith(".")) return null
        return File(File(dir, source), "$id.json")
    }

    fun save(source: String, id: String, record: JSONObject, nowMs: Long = System.currentTimeMillis()) {
        val file = fileFor(source, id) ?: return
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, "${file.name}.part")
        part.writeText(JSONObject().put("savedAt", nowMs).put("record", record).toString())
        if (!part.renameTo(file)) part.delete()
    }

    fun load(source: String, id: String): Saved? {
        val file = fileFor(source, id)?.takeIf { it.isFile } ?: return null
        return runCatching {
            val root = JSONObject(file.readText())
            Saved(root.getJSONObject("record"), root.optLong("savedAt"))
        }.getOrNull()
    }
}
