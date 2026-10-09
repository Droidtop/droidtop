package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.consoles.PlatformDatabaseSource
import dev.droidtop.library.consoles.PlatformDatabaseTransport
import dev.droidtop.pluginhost.AddKeyOutcome
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
import dev.droidtop.pluginhost.UserOriginKey
import dev.droidtop.pluginhost.UserOriginKeys
import dev.droidtop.runtime.util.CatalogSignature
import dev.droidtop.runtime.util.Sha256
import dev.droidtop.runtime.util.Versions
import java.io.File
import kotlin.math.sign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The catalog half of the plugin install story (docs/SPEC.md 12a "The
 * catalog", "Added catalogs"), over a list of catalog sources
 * ([PluginCatalogSources]): droidtop-platforms' `droidtop-plugins/index.json`
 * first -- one file, the same repository (and the same base-URL override)
 * as the platform databases, read through [PlatformDatabaseTransport] like
 * they are -- then every catalog the person added and accepted. What it does
 * with them is narrow: say which origin a catalog offers, which installed
 * plugin has an update, and download-verify-install one release. Trust is
 * decided nowhere in this file: the index's bytes are checked against its
 * own digests, then [PluginBundleInstaller] re-verifies the manifest
 * signature against the app's pinned key (or the key the person trusted for
 * an added catalog's origin) and every payload hash before anything is on
 * disk, and trust state moves only through that installer (SPEC 12a
 * checklist, point 4: an update signed by the same key the plugin was
 * approved under keeps its approval; everything else starts PENDING).
 */
object PluginCatalog {
    /** Relative to the platform-database base URL (SPEC 12a "The index"). */
    const val INDEX_RELATIVE_PATH = "droidtop-plugins/index.json"

    /** The master-signed revocation list, beside the index (SPEC 12a "Revocation"). */
    const val REVOCATIONS_RELATIVE_PATH = "droidtop-plugins/revocations.json"

    /** The only stream droidtop offers (SPEC 12a "What the catalog offers"). */
    const val STREAM_STABLE = "stable"

    private const val CACHE_SUBDIR = "plugin-catalog"
    private const val ADDED_SUBDIR = "added"
    private const val CACHE_FILE = "index.json"
    /** Re-fetch on entry when the cache is missing or this old. */
    private const val REFETCH_AFTER_MS = 60 * 60 * 1000L
    /** Bundles are payload-plus-manifest tar.xz; this cap is a sanity limit on the download, not a substitute for the installer's own per-entry caps. */
    private const val MAX_BUNDLE_BYTES = 512L * 1024 * 1024
    private const val DOWNLOAD_POST = "plugin_bundle"
    private const val ARG_LABEL = "label"
    private const val ARG_VERSION = "version"


    /** One catalog and the index last fetched from it. */
    data class Listing(val source: PluginCatalogSource, val index: PluginCatalogIndex)

    /** A release one catalog offers for one plugin. */
    data class Offer(val source: PluginCatalogSource, val plugin: PluginCatalogPlugin, val release: PluginCatalogRelease)

    fun indexUrl(context: Context, source: PluginCatalogSource = PluginCatalogSources.OFFICIAL): String =
        source.indexUrl ?: PlatformDatabaseSource.urlFor(context, INDEX_RELATIVE_PATH)

    /** The official catalog keeps the folder it always had; an added one has its own under it, named for its id. */
    private fun cacheDir(context: Context, source: PluginCatalogSource): File =
        if (source.official) {
            File(context.filesDir, CACHE_SUBDIR)
        } else {
            File(context.filesDir, "$CACHE_SUBDIR/$ADDED_SUBDIR/" + Sha256.hex(source.id.toByteArray()).take(16))
        }

    private fun cacheFile(context: Context, source: PluginCatalogSource): File = File(cacheDir(context, source), CACHE_FILE)

    private fun userKeys(context: Context): Map<String, UserOriginKey> = UserOriginKeys.load(UserOriginKeys.storeFile(context))

    private fun token(context: Context): String? = GitHubTokenStore.get(context)

    /** The last successfully fetched index of [source], no network. Null when there is none on file or it no longer parses (a corrupt cache is "no catalog", never a lie about one). */
    fun lastGoodIndex(context: Context, source: PluginCatalogSource = PluginCatalogSources.OFFICIAL): PluginCatalogIndex? {
        val text = runCatching { cacheFile(context, source).readText() }.getOrNull() ?: return null
        return PluginCatalogIndexParser.parse(text)
    }

    /**
     * Whether anything from [index] may be listed: always for the official catalog; for an added one only
     * while the person has accepted the disclaimer the index carries now (a newer version asks again).
     */
    fun listable(source: PluginCatalogSource, index: PluginCatalogIndex): Boolean =
        source.official || (index.disclaimer?.version ?: Int.MAX_VALUE) <= source.acceptedDisclaimer

    /** Every catalog's last fetched index that may be listed, the official one first. Disk only: what the Plugins screen and a plugin's page read. */
    fun listings(context: Context): List<Listing> =
        PluginCatalogSources.all(context).mapNotNull { source ->
            lastGoodIndex(context, source)?.takeIf { listable(source, it) }?.let { Listing(source, it) }
        }

    /** What a catalog screen shows: the cache when it is recent, otherwise a fresh fetch; a failed fetch falls back to the cache and the [Load.note] says so. Never throws. */
    suspend fun currentIndex(context: Context, source: PluginCatalogSource = PluginCatalogSources.OFFICIAL): Load = withContext(Dispatchers.IO) {
        val cached = lastGoodIndex(context, source)
        val stale = cached == null ||
            System.currentTimeMillis() - cacheFile(context, source).lastModified() >= REFETCH_AFTER_MS
        if (!stale) return@withContext Load(cached, null)
        try {
            Load(fetchAndCache(context, source), null)
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
    suspend fun refresh(context: Context, source: PluginCatalogSource = PluginCatalogSources.OFFICIAL): String = withContext(Dispatchers.IO) {
        try {
            val parsed = fetchAndCache(context, source)
            "Catalog up to date: ${parsed.origins.sumOf { it.plugins.size }} plugin(s) listed."
        } catch (failure: Exception) {
            if (lastGoodIndex(context, source) != null) {
                "Couldn't refresh (${describe(failure)}); the copy fetched earlier is still on file."
            } else if (failure is NotPublished) {
                "No plugin catalog is published yet. A plugin file can still be installed from the Plugins screen."
            } else {
                "Couldn't load the catalog: ${describe(failure)}."
            }
        }
    }

    /**
     * Whether this build of droidtop will accept bundles for [origin] from
     * the OFFICIAL catalog: it is the official origin AND the index's key
     * document for it names one of the keys this build pins for it -- the
     * legacy origin key or the plugin master (SPEC 12a "The index": "the
     * key block is cross-checked, not trusted"). A tampered index cannot
     * rebind the origin to a new key, and an origin this build does not pin
     * is not installable no matter what the index says.
     */
    fun originOffered(origin: String, indexKeySha256: String?): Boolean {
        if (!PluginOriginKeys.isOfficial(origin)) return false
        val declared = indexKeySha256?.lowercase() ?: return false
        return declared in PluginOriginKeys.officialAnchorFingerprints()
    }

    /** Where one origin of a catalog stands with the person's trust store (SPEC 12a "Added catalogs"). */
    sealed interface OriginState {
        /** Its plugins can be installed and updated from this catalog. */
        object Offered : OriginState

        /** An added catalog's origin nobody trusted yet: the person decides, seeing its fingerprint. */
        object NotTrusted : OriginState

        /** The person trusts a DIFFERENT key for this origin: nothing from this catalog is offered for it, and nothing is changed. */
        data class KeyChanged(val stored: UserOriginKey) : OriginState


        data class Unusable(val reason: String) : OriginState
    }

    /**
     * The one offer rule for every catalog. The official catalog offers only
     * the official origin under a pinned anchor ([originOffered]). An added
     * catalog offers an origin only when the person trusts exactly the key
     * the index names for it and no catalog revoked it: the index never
     * makes a trust decision, it can only match one the person made.
     */
    fun originState(source: PluginCatalogSource, origin: PluginCatalogOrigin, userKeys: Map<String, UserOriginKey>): OriginState {
        if (source.official) {
            return if (originOffered(origin.origin, origin.keySha256)) OriginState.Offered else OriginState.Unusable("its origin's key isn't pinned in this build")
        }
        if (PluginOriginKeys.isOfficial(origin.origin)) return OriginState.Unusable("an unofficial catalog cannot offer droidtop's own plugins")
        val declared = origin.keySha256?.lowercase() ?: return OriginState.Unusable("the catalog gives no usable key for it")
        val stored = userKeys[origin.origin] ?: return OriginState.NotTrusted
        if (UserOriginKeys.keySha256(stored.keyBase64) != declared) return OriginState.KeyChanged(stored)
        return OriginState.Offered
    }

    fun originOffered(source: PluginCatalogSource, origin: PluginCatalogOrigin, userKeys: Map<String, UserOriginKey>): Boolean =
        originState(source, origin, userKeys) == OriginState.Offered

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

    /** The update [record] has in the first of [listings] that lists it under an origin that catalog offers, or null. */
    fun offerFor(listings: List<Listing>, record: PluginRecord, userKeys: Map<String, UserOriginKey>): Offer? =
        listings.firstNotNullOfOrNull { listing ->
            val origin = listing.index.origins.firstOrNull { o -> o.plugins.any { it.id == record.manifest.id } }
                ?: return@firstNotNullOfOrNull null
            if (!originOffered(listing.source, origin, userKeys)) return@firstNotNullOfOrNull null
            val plugin = origin.plugins.first { it.id == record.manifest.id }
            updateFor(listing.index, record)?.let { Offer(listing.source, plugin, it) }
        }

    /** Every plugin in [installed] with an update in [listings], in the given order. */
    fun offersFor(installed: List<PluginRecord>, listings: List<Listing>, userKeys: Map<String, UserOriginKey>): List<Pair<PluginRecord, Offer>> =
        installed.mapNotNull { record -> offerFor(listings, record, userKeys)?.let { record to it } }

    /** [offerFor] over every catalog as last fetched: what the Plugins screen, a plugin's page and its panel read. Disk only. */
    fun cachedOfferFor(context: Context, record: PluginRecord): Offer? = offerFor(listings(context), record, userKeys(context))

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
            GitHubAuth.downloadRequestFor(release.bundle.url, token(context))
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
     * "Update all": a fresh fetch of every catalog (never the cache alone
     * -- this is the action that acts on the index, and it acts on the
     * newest one; a catalog whose refresh fails is left out of the
     * comparison and named in the summary), then the same [install] path
     * for every installed plugin that has an update in one of them. The
     * return is the summary line.
     */
    suspend fun updateAll(context: Context, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        onStatus("Loading the catalogs...")
        val fresh = mutableListOf<Listing>()
        val skipped = mutableListOf<String>()
        for (source in PluginCatalogSources.all(context)) {
            try {
                val index = fetchAndCache(context, source)
                // A source whose stored state changed during the fetch (a newly recorded signing key) is read again.
                val current = PluginCatalogSources.byId(context, source.id) ?: source
                if (listable(current, index)) fresh.add(Listing(current, index))
            } catch (failure: NotPublished) {
                if (!source.official) skipped.add("${source.name}: nothing is published at its address")
            } catch (failure: Exception) {
                skipped.add("${source.name}: couldn't refresh (${describe(failure)})")
            }
        }
        val notes = if (skipped.isEmpty()) "" else " Not compared: ${skipped.joinToString("; ")}."
        if (fresh.isEmpty()) {
            return@withContext if (skipped.isEmpty()) "No plugin catalog is published yet, so there is nothing to update from." else "Nothing was compared or updated.$notes"
        }
        val updates = offersFor(PluginStore.installed(context), fresh, userKeys(context))
        if (updates.isEmpty()) return@withContext "Everything installed is up to date.$notes"
        val failures = mutableListOf<String>()
        updates.forEachIndexed { i, (record, offer) ->
            onStatus("Updating ${record.manifest.label} (${i + 1}/${updates.size})...")
            val outcome = install(context, offer.plugin, offer.release) { status -> onStatus("${record.manifest.label}: $status") }
            if (!outcome.startsWith("Updated")) {
                failures.add("${record.manifest.label}: $outcome")
            }
        }
        if (failures.isEmpty()) {
            "Updated ${updates.size} plugin${if (updates.size == 1) "" else "s"}: ${updates.joinToString(", ") { it.first.manifest.label }}.$notes"
        } else {
            "Updated ${updates.size - failures.size} of ${updates.size}: ${failures.joinToString("; ")}.$notes"
        }
    }

    /** Fetches [source]'s index fresh and replaces the cached copy only after it parsed and, for an added catalog, passed [checkAdded] (the platform databases' own validate-before-replace contract). */
    private fun fetchAndCache(context: Context, source: PluginCatalogSource): PluginCatalogIndex {
        val url = indexUrl(context, source)
        val text = PlatformDatabaseTransport.getOrNull(url, token(context)) ?: throw NotPublished()
        if (source.official) {
            val parsed = PluginCatalogIndexParser.parse(text)
                ?: error("the catalog index at $url no longer has a format this build of droidtop reads")
            PlatformDatabaseTransport.write(cacheFile(context, source), text)
            refreshRevocations(context)
            return parsed
        }
        val checked = checkAdded(context, url, text, source)
        PlatformDatabaseTransport.write(cacheFile(context, source), text)
        val master = checked.index.catalog?.keyBase64
        if ((source.masterKeyBase64 == null && master != null) || (!source.indexSigned && checked.signed)) {
            // A master or an index signature that appeared since the catalog was accepted: recording them only
            // makes later fetches stricter. A master that CHANGED never gets here (checkAdded stops it).
            PluginCatalogSources.put(
                PluginCatalogSources.storeFile(context),
                source.copy(masterKeyBase64 = source.masterKeyBase64 ?: master, indexSigned = source.indexSigned || checked.signed),
            )
        }
        refreshAddedRevocations(context, PluginCatalogSources.byId(context, source.id) ?: source)
        return checked.index
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
            PlatformDatabaseTransport.getOrNull(PlatformDatabaseSource.urlFor(context, REVOCATIONS_RELATIVE_PATH), token(context))
        }.getOrNull() ?: return
        PluginRevocations.accept(PluginStore.root(context), text)
    }

    // ------------------------------------------------------------------
    // Added catalogs (SPEC 12a "Added catalogs").
    // ------------------------------------------------------------------

    /** An added catalog's index that passed [checkAdded]; [signed] when its index signature verified under the catalog's master. */
    internal class Checked(val index: PluginCatalogIndex, val signed: Boolean)

    /**
     * Everything an added catalog's index must be before droidtop keeps it:
     * readable, with both its `catalog` and `disclaimer` blocks, the same
     * catalog the person accepted ([expected], null while adding), its
     * organisation's origin listed under exactly the catalog's master, the
     * same master the person accepted (a changed master stops dead: the
     * person removes the catalog and adds it again to trust another one),
     * and, when an index signature is published or was seen before, signed
     * under that master ([CatalogSignature.verify], the component catalog's
     * own check, with this catalog's master and id).
     * Throws [IllegalStateException] with the sentence to show.
     */
    private fun checkAdded(context: Context, url: String, text: String, expected: PluginCatalogSource?): Checked {
        val token = token(context)
        return checkAddedIndex(url, text, expected, System.currentTimeMillis() / 1000) { name ->
            runCatching { PlatformDatabaseTransport.getOrNull(PluginCatalogSources.siblingUrl(url, name), token) }.getOrNull()
        }
    }

    /** [checkAdded] with the files beside the index read through [sibling] (by file name; null when missing), so a test can serve them. */
    internal fun checkAddedIndex(
        url: String,
        text: String,
        expected: PluginCatalogSource?,
        nowEpochSeconds: Long,
        sibling: (String) -> String?,
    ): Checked {
        val index = PluginCatalogIndexParser.parse(text)
            ?: error("the catalog at $url is not in a format this build of droidtop reads")
        val info = index.catalog
            ?: error("$url is a plugin index without a catalog block, so it can't be added as a catalog")
        if (index.disclaimer == null) error("the catalog at $url has no disclaimer, and droidtop shows one before it lists anything")
        if (expected != null && info.id != expected.id) {
            error("$url now serves another catalog (\"${info.id}\", not \"${expected.id}\"), so it was not read")
        }
        val accepted = expected?.masterKeyBase64
        val declared = info.keyBase64
        if (accepted != null) {
            if (declared == null) error("the catalog had a master key when you accepted it and this copy names none, so it was not read")
            if (UserOriginKeys.keySha256(declared) != UserOriginKeys.keySha256(accepted)) {
                error(
                    "the catalog's master key changed since you accepted it (you accepted ${UserOriginKeys.fingerprint(accepted)}, " +
                        "it now names ${UserOriginKeys.fingerprint(declared) ?: "an unreadable key"}). Nothing was changed; " +
                        "if the catalog really replaced its master, remove it and add it again",
                )
            }
        }
        if (declared == null) {
            if (info.origin != null) error("the catalog names its origin \"${info.origin}\" but no master key for it")
            return Checked(index, signed = false)
        }
        val masterSha256 = UserOriginKeys.keySha256(declared) ?: error("the catalog's master key is not a P-256 public key")
        info.origin?.let { own ->
            val listed = index.origins.firstOrNull { it.origin == own }
            if (listed != null && listed.keySha256?.lowercase() != masterSha256) {
                error("the catalog lists its own origin \"$own\" under a key other than its master, so it was not read")
            }
        }
        val master = PluginOriginKeys.parseSpki(declared)!!
        val signature = sibling(PluginCatalogSources.INDEX_SIGNATURE_FILE)
        if (signature == null) {
            if (expected?.indexSigned == true) error("the catalog's index was signed before and this copy is not, so it was not read")
            return Checked(index, signed = false)
        }
        val certificate = sibling(PluginCatalogSources.INDEX_CERTIFICATE_FILE)
        val verdict = CatalogSignature.verify(text.toByteArray(Charsets.UTF_8), signature.trim(), certificate, master, nowEpochSeconds, info.id)
        if (verdict is CatalogSignature.Verdict.Refused) error("the catalog's signature was refused: ${verdict.reason}")
        return Checked(index, signed = true)
    }

    /** What the person reviews before adding a catalog: who it says it is, its disclaimer, whether it is signed, and each origin's key. */
    data class Proposal(
        val indexUrl: String,
        val text: String,
        val info: PluginCatalogInfo,
        val disclaimer: PluginCatalogDisclaimer,
        val signed: Boolean,
        val origins: List<ProposedOrigin>,
    )

    /** One origin the catalog lists: its key as the index gives it, and the person's own entry for that origin when there is one. */
    data class ProposedOrigin(val origin: String, val keyBase64: String?, val existing: UserOriginKey?)

    sealed interface ProposeResult {
        data class Ready(val proposal: Proposal) : ProposeResult
        data class Failed(val reason: String) : ProposeResult
    }

    /**
     * The fetch half of adding a catalog: reads the index at the address the
     * person typed or scanned and checks it ([checkAdded]). Nothing is stored
     * or trusted: the person sees the [Proposal] and accepts it, or not.
     */
    suspend fun propose(context: Context, input: String): ProposeResult = withContext(Dispatchers.IO) {
        val url = PluginCatalogSources.indexUrlFor(input)
            ?: return@withContext ProposeResult.Failed(
                "That isn't a catalog address. Use the catalog's GitHub repository (https://github.com/<owner>/<repo>) " +
                    "or the https address of its index.json; plain http is refused",
            )
        if (url == indexUrl(context)) return@withContext ProposeResult.Failed("That is droidtop's own catalog, which is always there")
        val text = try {
            PlatformDatabaseTransport.getOrNull(url, token(context))
        } catch (failure: Exception) {
            return@withContext ProposeResult.Failed("Couldn't fetch $url: ${failure.message ?: "the connection failed"}")
        }
        if (text == null) return@withContext ProposeResult.Failed("Nothing is published at $url")
        val checked = try {
            checkAdded(context, url, text, expected = null)
        } catch (refusal: IllegalStateException) {
            return@withContext ProposeResult.Failed(refusal.message ?: "The catalog was refused")
        }
        val info = checked.index.catalog!!
        PluginCatalogSources.byId(context, info.id)?.let {
            return@withContext ProposeResult.Failed("\"${it.name}\" is already one of your catalogs")
        }
        val keys = userKeys(context)
        val origins = checked.index.origins
            .filterNot { PluginOriginKeys.isOfficial(it.origin) }
            .map { ProposedOrigin(it.origin, it.keyBase64, keys[it.origin]) }
        ProposeResult.Ready(Proposal(url, text, info, checked.index.disclaimer!!, checked.signed, origins))
    }

    /**
     * The confirm half: the person accepted [proposal]'s disclaimer. Stores
     * the catalog (its master when signed, the disclaimer version accepted),
     * keeps the index it was shown, and trusts each origin's key exactly as
     * "Keys you trust" does ([trustOrigin]). Returns the line to show.
     */
    suspend fun accept(context: Context, proposal: Proposal): String = withContext(Dispatchers.IO) {
        val store = PluginCatalogSources.storeFile(context)
        if (PluginCatalogSources.byId(context, proposal.info.id) != null) return@withContext "\"${proposal.info.name}\" is already one of your catalogs"
        val source = PluginCatalogSource(
            id = proposal.info.id,
            name = proposal.info.name,
            indexUrl = proposal.indexUrl,
            homepage = proposal.info.homepage,
            masterKeyBase64 = proposal.info.keyBase64,
            indexSigned = proposal.signed,
            acceptedDisclaimer = proposal.disclaimer.version,
        )
        try {
            PluginCatalogSources.put(store, source)
            PlatformDatabaseTransport.write(cacheFile(context, source), proposal.text)
        } catch (failure: Exception) {
            return@withContext "Couldn't save the catalog: ${failure.message}"
        }
        val lines = proposal.origins.map { trustOrigin(context, source, it.origin, it.keyBase64) }
        runCatching { refreshAddedRevocations(context, source) }
        "Added \"${source.name}\". " + lines.joinToString(" ")
    }

    /**
     * Trusts [origin]'s key from the added catalog [source], the "Keys you
     * trust" way: [UserOriginKeys.add] never overwrites, so a DIFFERENT key
     * for an origin the person already trusts is refused with both
     * fingerprints and nothing changes (that origin's plugins are simply not
     * offered from this catalog); the same key already trusted is recorded
     * as also coming from this catalog, which badges its plugins Unofficial.
     */
    fun trustOrigin(context: Context, source: PluginCatalogSource, origin: String, keyBase64: String?): String {
        if (keyBase64 == null) return "\"$origin\": the catalog gives no usable key for it, so its plugins are not offered."
        val file = UserOriginKeys.storeFile(context)
        return when (val outcome = UserOriginKeys.add(file, origin, keyBase64, source = source.indexUrl, catalog = source.id)) {
            is AddKeyOutcome.Added -> "Trusted \"$origin\" (${UserOriginKeys.fingerprint(keyBase64)})."
            AddKeyOutcome.AlreadyTrustedSameKey -> {
                if (UserOriginKeys.load(file)[origin]?.catalog == null) UserOriginKeys.attachCatalog(file, origin, source.id)
                "\"$origin\" was already trusted with this same key."
            }
            is AddKeyOutcome.KeyChanged ->
                "\"$origin\" NOT trusted from this catalog: you trust a different key for it (${UserOriginKeys.fingerprint(outcome.stored.keyBase64)}, " +
                    "this catalog names ${UserOriginKeys.fingerprint(keyBase64)}), so its plugins here are not offered."
            is AddKeyOutcome.Refused -> "\"$origin\" refused: ${outcome.reason}."
        }
    }

    /** The person accepted [version] of [source]'s disclaimer: its plugins are listed again. */
    fun acceptDisclaimer(context: Context, source: PluginCatalogSource, version: Int) {
        PluginCatalogSources.put(PluginCatalogSources.storeFile(context), source.copy(acceptedDisclaimer = maxOf(version, source.acceptedDisclaimer)))
    }

    /**
     * Removes the added catalog [source]: nothing is listed or updated from
     * it any more. The keys the person trusted through it stay (SPEC 12a
     * "Removal is real" is about keys, and removing a catalog is not
     * removing a key), so plugins installed from it keep running under the
     * same trust rules as any other user-trusted origin.
     */
    fun removeCatalog(context: Context, source: PluginCatalogSource): Boolean {
        if (source.official) return false
        val removed = PluginCatalogSources.remove(PluginCatalogSources.storeFile(context), source.id)
        cacheDir(context, source).deleteRecursively()
        return removed
    }

    /**
     * An added catalog's revocation list, beside its index
     * (`revocations.json`): the plugin master's own format and signed bytes
     * ([PluginRevocations]: `sequence`, `certIds`, `keySha256`, `signature`),
     * signed by THIS catalog's master. It is kept for each origin the person
     * trusts through this catalog ([PluginRevocations.acceptForOrigin]: only
     * when the master signed it and its sequence is higher than the one on
     * file) and checked by [dev.droidtop.pluginhost.BundleSignature.verifyBundle]
     * for that origin alone, so a catalog can withdraw only what it vouched
     * for, and droidtop's own list never reaches it. A catalog without a
     * master has no revocation list. A missing or refused list changes
     * nothing and never fails the fetch it rides along with.
     */
    private fun refreshAddedRevocations(context: Context, source: PluginCatalogSource) {
        val indexUrl = source.indexUrl ?: return
        val master = source.masterKeyBase64?.let(PluginOriginKeys::parseSpki) ?: return
        val url = PluginCatalogSources.siblingUrl(indexUrl, PluginCatalogSources.REVOCATIONS_FILE)
        val text = runCatching { PlatformDatabaseTransport.getOrNull(url, token(context)) }.getOrNull() ?: return
        val root = PluginStore.root(context)
        userKeys(context).values.filter { it.catalog == source.id }.forEach { entry ->
            PluginRevocations.acceptForOrigin(root, entry.origin, text, master)
        }
    }
}
