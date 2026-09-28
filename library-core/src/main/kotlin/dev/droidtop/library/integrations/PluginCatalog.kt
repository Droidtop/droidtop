package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.consoles.PlatformDatabaseSource
import dev.droidtop.library.consoles.PlatformDatabaseTransport
import dev.droidtop.pluginhost.PluginBundleInstaller
import dev.droidtop.pluginhost.PluginInstallResult
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.PluginOriginKeys
import dev.droidtop.pluginhost.PluginTrustState
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The catalog half of the plugin install story (docs/SPEC.md 12a "The
 * catalog"). The index it works from is droidtop-platforms'
 * `droidtop-plugins/index.json` -- one file, the same repository (and the
 * same base-URL override) as the platform databases, read through
 * [PlatformDatabaseTransport] like they are -- and what it does with it
 * is narrow: say which origin this build offers, which installed plugin
 * has an update, and download-verify-install one release. Trust is
 * decided nowhere in this file: the index's bytes are checked against
 * its own digests, then [PluginBundleInstaller] re-verifies the manifest
 * signature against the app's pinned key and every payload hash before
 * anything is on disk, and trust state moves only through that installer
 * (SPEC 12a checklist, point 4: an update signed by the same key the
 * plugin was approved under keeps its approval; everything else starts
 * PENDING).
 */
object PluginCatalog {
    /** Relative to the platform-database base URL (SPEC 12a "The index"). */
    const val INDEX_RELATIVE_PATH = "droidtop-plugins/index.json"

    /** The only stream droidtop offers (SPEC 12a "What the catalog offers"). */
    const val STREAM_STABLE = "stable"

    private const val CACHE_SUBDIR = "plugin-catalog"
    private const val CACHE_FILE = "index.json"
    /** Re-fetch on entry when the cache is missing or this old. */
    private const val REFETCH_AFTER_MS = 60 * 60 * 1000L
    /** Bundles are payload-plus-manifest tar.xz; this cap is a sanity limit on the download, not a substitute for the installer's own per-entry caps. */
    private const val MAX_BUNDLE_BYTES = 512L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    fun indexUrl(context: Context): String = PlatformDatabaseSource.urlFor(context, INDEX_RELATIVE_PATH)

    private fun cacheFile(context: Context): File = File(context.filesDir, "$CACHE_SUBDIR/$CACHE_FILE")

    /** The last successfully fetched index, no network. Null when there is none on file or it no longer parses (a corrupt cache is "no catalog", never a lie about one). */
    fun lastGoodIndex(context: Context): PluginCatalogIndex? {
        val text = runCatching { cacheFile(context).readText() }.getOrNull() ?: return null
        return PluginCatalogIndexParser.parse(text)
    }

    /** What the catalog screen shows: the cache when it is recent, otherwise a fresh fetch; a failed fetch falls back to the cache and the [Load.note] says so. Never throws. */
    suspend fun currentIndex(context: Context): Load = withContext(Dispatchers.IO) {
        val cached = lastGoodIndex(context)
        val stale = cached == null ||
            System.currentTimeMillis() - cacheFile(context).lastModified() >= REFETCH_AFTER_MS
        if (!stale) return@withContext Load(cached, null)
        try {
            fetchAndCache(context)
        } catch (failure: Exception) {
            Load(
                cached,
                if (cached != null) {
                    "The refresh just failed (${failure.message ?: "unknown error"}); this is the copy fetched earlier."
                } else {
                    "Couldn't load the catalog: ${failure.message ?: "unknown error"}."
                },
            )
        }
    }

    data class Load(val index: PluginCatalogIndex?, val note: String?)

    /**
     * The manual "Refresh catalog" row: always fetches (no cache-freshness
     * shortcut -- the tap IS the request), and reports what happened
     * instead of throwing, like every other long settings action.
     */
    suspend fun refresh(context: Context): String = withContext(Dispatchers.IO) {
        try {
            val parsed = fetchAndCache(context)
            "Catalog up to date: ${parsed.origins.sumOf { it.plugins.size }} plugin(s) listed."
        } catch (failure: Exception) {
            if (lastGoodIndex(context) != null) {
                "Couldn't refresh (${failure.message ?: "unknown error"}); the copy fetched earlier is still on file."
            } else {
                "Couldn't load the catalog: ${failure.message ?: "unknown error"}."
            }
        }
    }

    /**
     * Whether this build of droidtop will accept bundles for [origin]:
     * it pins a key for the origin AND the index's key document for it
     * names that same key (SPEC 12a "The index": "the key block is
     * cross-checked, not trusted"). A tampered index cannot rebind a
     * pinned origin to a new key, and an origin this build does not pin
     * is not installable no matter what the index says.
     */
    fun originOffered(origin: String, indexKeySha256: String?): Boolean {
        val pin = PluginOriginKeys.keyFingerprintFor(origin) ?: return false
        val declared = indexKeySha256?.uppercase() ?: return false
        return pin.equals(declared, ignoreCase = true)
    }

    /** The newest stable release of [plugin], or null when it has none. */
    fun latestStable(plugin: PluginCatalogPlugin): PluginCatalogRelease? =
        plugin.releases.filter { it.stream == STREAM_STABLE }.maxByOrNull { it.publishedAtMillis() }

    /** The update [record] has in [index]: the newest stable release of its id with a different manifest digest. Null when installed and current, or not listed. */
    fun updateFor(index: PluginCatalogIndex, record: PluginRecord): PluginCatalogRelease? {
        val plugin = index.pluginById(record.manifest.id) ?: return null
        val latest = latestStable(plugin) ?: return null
        return if (latest.manifestSha256.equals(record.archiveDigest, ignoreCase = true)) null else latest
    }

    /** Every plugin in [installed] that has an update in [index], in the given order. */
    fun updatesFor(installed: List<PluginRecord>, index: PluginCatalogIndex): List<Pair<PluginRecord, PluginCatalogRelease>> =
        installed.mapNotNull { record -> updateFor(index, record)?.let { record to it } }

    /**
     * The one install/update path (SPEC 12a "The flow"): download the
     * release's bundle, check the whole-file hash against the index's
     * own digest for it, then run [PluginBundleInstaller] in full -- a
     * catalog bundle gets no shortcut past signature or hash checks.
     * Trust is the installer's to move: a new id comes back PENDING, an
     * update under the same key keeps its approval. [onStatus] gets live
     * progress; the return is the user-facing result line.
     */
    suspend fun install(
        context: Context,
        plugin: PluginCatalogPlugin,
        release: PluginCatalogRelease,
        onStatus: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("plugin-catalog-", ".droidplugin.tar.xz", context.cacheDir)
        try {
            when (val download = download(release.bundle, tmp) { bytesRead, totalBytes ->
                onStatus(
                    if (totalBytes > 0) "Downloading... ${bytesRead * 100 / totalBytes}%" else "Downloading... ${bytesRead / 1024 / 1024} MB",
                )
            }) {
                is Download.Failed -> return@withContext "Couldn't download ${plugin.label}: ${download.reason}"
                is Download.Done -> {
                    onStatus("Checking the bundle...")
                    if (!download.sha256.equals(release.bundle.sha256, ignoreCase = true)) {
                        return@withContext "The downloaded bundle doesn't match the catalog's own hash for it -- not installed"
                    }
                    when (val result = PluginBundleInstaller.install(tmp, PluginStore.root(context))) {
                        is PluginInstallResult.Installed ->
                            if (result.record.trust == PluginTrustState.APPROVED) {
                                "Updated ${plugin.label} to ${release.version}"
                            } else {
                                "${plugin.label} ${release.version} installed -- approve it on the Plugins screen before it runs"
                            }
                        is PluginInstallResult.Refused -> "Not installed: ${result.error.reason}"
                    }
                }
            }
        } finally {
            tmp.delete()
        }
    }

    /**
     * "Update all": a fresh index fetch (never the cache alone -- this
     * is the action that acts on the index, and it acts on the newest
     * one), then the same [install] path for every installed plugin that
     * has an update in it. The return is the summary line.
     */
    suspend fun updateAll(context: Context, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        onStatus("Loading the catalog...")
        val index = try {
            fetchAndCache(context)
        } catch (failure: Exception) {
            val cached = lastGoodIndex(context)
            return@withContext if (cached != null) {
                "Couldn't refresh the catalog (${failure.message ?: "unknown error"}); nothing was compared or updated."
            } else {
                "Couldn't load the catalog: ${failure.message ?: "unknown error"}."
            }
        }
        val updates = updatesFor(PluginStore.installed(context), index)
        if (updates.isEmpty()) return@withContext "Everything installed is up to date."
        val failures = mutableListOf<String>()
        updates.forEachIndexed { i, (record, release) ->
            val plugin = index.pluginById(record.manifest.id) ?: return@forEachIndexed
            onStatus("Updating ${record.manifest.label} (${i + 1}/${updates.size})...")
            val outcome = install(context, plugin, release) { status -> onStatus("${record.manifest.label}: $status") }
            if (!outcome.startsWith("Updated")) {
                failures.add("${record.manifest.label}: $outcome")
            }
        }
        if (failures.isEmpty()) {
            "Updated ${updates.size} plugin${if (updates.size == 1) "" else "s"}: ${updates.joinToString(", ") { it.first.manifest.label }}"
        } else {
            "Updated ${updates.size - failures.size} of ${updates.size}: ${failures.joinToString("; ")}"
        }
    }

    /** Fetches the index fresh and replaces the cached copy only after it parsed (the platform databases' own validate-before-replace contract). */
    private fun fetchAndCache(context: Context): PluginCatalogIndex {
        val text = PlatformDatabaseTransport.get(indexUrl(context))
        val parsed = PluginCatalogIndexParser.parse(text)
            ?: error("the catalog index at ${indexUrl(context)} no longer has a format this build of droidtop reads")
        val dir = cacheFile(context).parentFile
        dir?.mkdirs()
        PlatformDatabaseTransport.write(cacheFile(context), text)
        return parsed
    }

    private sealed interface Download {
        data class Done(val sha256: String) : Download
        data class Failed(val reason: String) : Download
    }

    /** Streams [bundle] into [dest] (created fresh), enforcing the size cap and reporting progress; answers the file's own SHA-256. */
    private fun download(bundle: PluginCatalogBundle, dest: File, onProgress: (Long, Long) -> Unit): Download {
        val connection: HttpURLConnection = try {
            URL(bundle.url).openConnection() as HttpURLConnection
        } catch (failure: Exception) {
            return Download.Failed("the connection failed (${failure.message ?: "no network"})")
        }
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        return try {
            when (val code = connection.responseCode) {
                200 -> {
                    val totalBytes = connection.contentLength.toLong()
                    if (totalBytes > MAX_BUNDLE_BYTES) {
                        return Download.Failed("the bundle is larger than the ${MAX_BUNDLE_BYTES / 1024 / 1024} MiB cap")
                    }
                    var written = 0L
                    val digest = MessageDigest.getInstance("SHA-256")
                    connection.inputStream.use { input ->
                        FileOutputStream(dest).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                written += read
                                if (written > MAX_BUNDLE_BYTES) {
                                    return Download.Failed("the bundle is larger than the ${MAX_BUNDLE_BYTES / 1024 / 1024} MiB cap")
                                }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                                onProgress(written, totalBytes)
                            }
                        }
                    }
                    if (totalBytes > 0 && written != totalBytes) {
                        return Download.Failed("the download was interrupted (${written} of $totalBytes bytes)")
                    }
                    Download.Done(digest.digest().joinToString("") { "%02x".format(it) })
                }
                404, 410 -> Download.Failed("the server says the bundle is not there (HTTP $code)")
                else -> Download.Failed("the server answered HTTP $code")
            }
        } catch (failure: Exception) {
            Download.Failed("the download was interrupted (${failure.message ?: "no network"})")
        } finally {
            connection.disconnect()
        }
    }
}
