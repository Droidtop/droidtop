package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.consoles.PlatformDatabaseSource
import dev.droidtop.library.consoles.PlatformDatabaseTransport
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.net.GitHubAuth
import dev.droidtop.net.GitHubTokenStore
import dev.droidtop.pluginhost.PluginBundleInstaller
import dev.droidtop.pluginhost.PluginInstallResult
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.PluginOriginKeys
import dev.droidtop.pluginhost.PluginRevocations
import dev.droidtop.pluginhost.PluginTrustState
import dev.droidtop.pluginhost.UserOriginKeys
import dev.droidtop.runtime.util.Versions
import java.io.File
import kotlin.math.sign
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

    /** The master-signed revocation list, beside the index (SPEC 12a "Revocation"). */
    const val REVOCATIONS_RELATIVE_PATH = "droidtop-plugins/revocations.json"

    /** The only stream droidtop offers (SPEC 12a "What the catalog offers"). */
    const val STREAM_STABLE = "stable"

    private const val CACHE_SUBDIR = "plugin-catalog"
    private const val CACHE_FILE = "index.json"
    /** Re-fetch on entry when the cache is missing or this old. */
    private const val REFETCH_AFTER_MS = 60 * 60 * 1000L
    /** Bundles are payload-plus-manifest tar.xz; this cap is a sanity limit on the download, not a substitute for the installer's own per-entry caps. */
    private const val MAX_BUNDLE_BYTES = 512L * 1024 * 1024
    private const val DOWNLOAD_POST = "plugin_bundle"
    private const val ARG_LABEL = "label"
    private const val ARG_VERSION = "version"

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
            Load(fetchAndCache(context), null)
        } catch (failure: NotPublished) {
            Load(cached, null, published = cached != null)
        } catch (failure: Exception) {
            Load(
                cached,
                if (cached != null) {
                    "The refresh just failed (${describe(failure)}); this is the copy fetched earlier."
                } else {
                    "Couldn't load the catalog: ${describe(failure)}."
                },
            )
        }
    }

    /** [published] is false when the host answered that the index file does not exist: no catalog yet, which is a state to say plainly, not an error to print. */
    data class Load(val index: PluginCatalogIndex?, val note: String?, val published: Boolean = true)

    /** The index file is not there (HTTP 404/410): nothing has been published to browse yet. */
    private class NotPublished : Exception("No plugin catalog is published yet")

    /** One sentence for a failed fetch, never a raw status line for the unpublished case. */
    private fun describe(failure: Exception): String =
        if (failure is NotPublished) "No plugin catalog is published yet" else failure.message ?: "unknown error"

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
                "Couldn't refresh (${describe(failure)}); the copy fetched earlier is still on file."
            } else if (failure is NotPublished) {
                "No plugin catalog is published yet. A plugin file can still be installed from the Plugins screen."
            } else {
                "Couldn't load the catalog: ${describe(failure)}."
            }
        }
    }

    /**
     * Whether this build of droidtop will accept bundles for [origin]:
     * it is the official origin AND the index's key document for it
     * names one of the keys this build pins for it -- the legacy origin
     * key or the plugin master (SPEC 12a "The index": "the key block is
     * cross-checked, not trusted"). A tampered index cannot rebind the
     * origin to a new key, and an origin this build does not pin is not
     * installable no matter what the index says.
     */
    fun originOffered(origin: String, indexKeySha256: String?): Boolean {
        if (!PluginOriginKeys.isOfficial(origin)) return false
        val declared = indexKeySha256?.lowercase() ?: return false
        return declared in PluginOriginKeys.officialAnchorFingerprints()
    }

    /**
     * The newest stable release of [plugin], or null when it has none, or
     * when its stable releases disagree about which is newest.
     *
     * Newest is decided by BOTH the version string and `publishedAt`
     * (SPEC 12a "What the catalog offers"): where one of them ties (or a
     * date is missing), the other decides; where both speak, they must
     * agree. A newer version published earlier than an older one is not
     * something a publisher does on purpose; it means the index was built
     * wrong, and droidtop offers nothing for that plugin rather than guess
     * ([hasOrderConflict]).
     */
    fun latestStable(plugin: PluginCatalogPlugin): PluginCatalogRelease? {
        val stable = plugin.releases.filter { it.stream == STREAM_STABLE }
        if (hasOrderConflict(stable)) return null
        return stable.maxWithOrNull { a, b -> compareReleases(a, b) ?: 0 }
    }

    /** Whether [plugin]'s stable releases disagree between version order and publish order (see [latestStable]). */
    fun hasOrderConflict(plugin: PluginCatalogPlugin): Boolean =
        hasOrderConflict(plugin.releases.filter { it.stream == STREAM_STABLE })

    private fun hasOrderConflict(releases: List<PluginCatalogRelease>): Boolean =
        releases.indices.any { i -> (i + 1 until releases.size).any { j -> compareReleases(releases[i], releases[j]) == null } }

    /** Version and date together: positive when [a] is newer, null when the two orders disagree. */
    private fun compareReleases(a: PluginCatalogRelease, b: PluginCatalogRelease): Int? {
        val byVersion = Versions.compareLoose(a.version, b.version).sign
        val byDate = if (a.publishedAt != null && b.publishedAt != null) {
            a.publishedAtMillis().compareTo(b.publishedAtMillis()).sign
        } else {
            0
        }
        return when {
            byVersion == 0 -> byDate
            byDate == 0 -> byVersion
            byVersion == byDate -> byVersion
            else -> null
        }
    }

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
        // A token host is resolved to its signed address here (see GitHubAuth.downloadRequestFor);
        // the transfer itself is one DownloadManager job in "Downloads and installs".
        val (url, headers) = try {
            GitHubAuth.downloadRequestFor(release.bundle.url, GitHubTokenStore.get(context))
        } catch (failure: Exception) {
            return@withContext "Couldn't download ${plugin.label}: the connection failed (${failure.message ?: "no network"})"
        }
        val result = DownloadJobs.run(
            context = context,
            title = "${plugin.label} ${release.version}",
            post = DOWNLOAD_POST,
            url = url,
            name = "plugin-" + "${plugin.id}-${release.version}".replace(Regex("[^A-Za-z0-9._-]"), "_") + ".droidplugin.tar.xz",
            sha256 = release.bundle.sha256,
            maxBytes = MAX_BUNDLE_BYTES,
            headers = headers,
            extra = mapOf(ARG_LABEL to plugin.label, ARG_VERSION to release.version),
            onStatus = onStatus,
        )
        when {
            result.ok -> result.values["summary"].orEmpty()
            // A catalog bundle gets no shortcut past its hash: a mismatch is never installed.
            result.error == DownloadJobs.DIGEST_MISMATCH -> "The downloaded bundle doesn't match the catalog's own hash for it -- not installed"
            else -> "Couldn't download ${plugin.label}: ${result.error}"
        }
    }

    /**
     * What happens to a downloaded, hash-checked bundle: [PluginBundleInstaller] in full (signature
     * and hash checks), whose outcome line is the job's summary. Registered at process start so a
     * download that finished while the process was gone is still installed.
     */
    fun registerDownloadPost() {
        DownloadJobs.registerPost(DOWNLOAD_POST) { context, file, args ->
            val label = args[ARG_LABEL] ?: "The plugin"
            val version = args[ARG_VERSION].orEmpty()
            // The user-trusted keys too: a bundle from an origin the person trusted verifies like an official one.
            when (val result = PluginBundleInstaller.install(file, PluginStore.root(context), UserOriginKeys.loadBase64(UserOriginKeys.storeFile(context)), hostPermissions = dev.droidtop.pluginhost.AndroidPermissions.heldBy(context))) {
                is PluginInstallResult.Installed ->
                    if (result.record.trust == PluginTrustState.APPROVED) {
                        "Updated $label to $version"
                    } else {
                        "$label $version installed. Approve it on the Plugins screen before it runs"
                    }
                is PluginInstallResult.Refused -> "Not installed: ${result.error.reason}"
            }
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
                "Couldn't refresh the catalog (${describe(failure)}); nothing was compared or updated."
            } else if (failure is NotPublished) {
                "No plugin catalog is published yet, so there is nothing to update from."
            } else {
                "Couldn't load the catalog: ${describe(failure)}."
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
        val text = PlatformDatabaseTransport.getOrNull(indexUrl(context), GitHubTokenStore.get(context)) ?: throw NotPublished()
        val parsed = PluginCatalogIndexParser.parse(text)
            ?: error("the catalog index at ${indexUrl(context)} no longer has a format this build of droidtop reads")
        val dir = cacheFile(context).parentFile
        dir?.mkdirs()
        PlatformDatabaseTransport.write(cacheFile(context), text)
        refreshRevocations(context)
        return parsed
    }

    /**
     * The plugin master's revocation list, published beside the index
     * (SPEC 12a "Revocation") and fetched with it. [PluginRevocations.accept]
     * keeps it only when the master signed it and its sequence is newer than
     * the one on file; a missing, unreadable or unsigned list changes
     * nothing, and never fails the catalog fetch it rides along with.
     */
    private fun refreshRevocations(context: Context) {
        val text = runCatching {
            PlatformDatabaseTransport.getOrNull(PlatformDatabaseSource.urlFor(context, REVOCATIONS_RELATIVE_PATH), GitHubTokenStore.get(context))
        }.getOrNull() ?: return
        PluginRevocations.accept(PluginStore.root(context), text)
    }
}
