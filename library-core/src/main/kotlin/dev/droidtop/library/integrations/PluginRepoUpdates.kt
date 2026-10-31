package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.net.GitHubAuth
import dev.droidtop.net.GitHubTokenStore
import dev.droidtop.net.Http
import dev.droidtop.pluginhost.PermissionDiff
import dev.droidtop.pluginhost.PluginBundleInstaller
import dev.droidtop.pluginhost.PluginInstallResult
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginRepos
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.PluginTrustState
import dev.droidtop.pluginhost.UserOriginKey
import dev.droidtop.pluginhost.UserOriginKeys
import dev.droidtop.shared.version.Versions
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray

/**
 * One bundle attached to a repository release: [apiUrl] is the asset's GitHub API address (the only
 * address a private repository serves, with the token), [downloadUrl] the public one.
 */
data class RepoBundleAsset(val name: String, val apiUrl: String, val downloadUrl: String, val size: Long, val sha256: String?)

data class RepoRelease(
    val id: Long,
    val tag: String,
    val prerelease: Boolean,
    val publishedAt: String?,
    val bundles: List<RepoBundleAsset>,
)

/**
 * One bundle of a repository's latest release as the last pass read it: the plugin its manifest names and that
 * plugin's version. Only a bundle signed for the repository's own origin with the key the person trusted is read so;
 * the rest are not this repository's to offer.
 */
data class RepoBundleRead(val assetName: String, val pluginId: String, val version: String, val sizeBytes: Long)

/** What the last pass read of one repository's latest release, kept in preferences so a screen lists it without the network. */
data class RepoReleaseRead(val tag: String, val prerelease: Boolean, val bundles: List<RepoBundleRead>)

/**
 * An installed plugin that a trusted repository's latest release, as last read, has a newer version of
 * ([PluginRepoUpdates.pendingUpdates]): what the Updates page lists as a plugin update, beside the catalogs' ones, and
 * what the repository's own screen marks as an update.
 */
data class RepoPluginUpdate(val installed: PluginRecord, val repo: UserOriginKey, val tag: String, val bundle: RepoBundleRead) {
    val version: String get() = bundle.version
}

/** How installing one bundle by hand ended: whether the plugin is now installed, and the line to show for it. */
data class RepoInstallOutcome(val installed: Boolean, val line: String)

/** What one plugin in one release came to; [describe] words it for the screen and the notification. */
sealed interface RepoUpdateOutcome {
    val label: String

    data class Updated(override val label: String, val version: String, val newAccess: PermissionDiff) : RepoUpdateOutcome
    data class NeedsApproval(override val label: String, val version: String) : RepoUpdateOutcome
    data class Offered(override val label: String, val version: String) : RepoUpdateOutcome

    /** A newer version of an installed plugin, left for the person because plugins ask before installing. */
    data class Available(override val label: String, val version: String) : RepoUpdateOutcome
    data class Refused(override val label: String, val reason: String) : RepoUpdateOutcome
}

/** What happened when one repository was checked. */
sealed interface RepoCheckResult {
    /** Nothing changed since the last look (GitHub answered 304, which costs no request allowance). */
    object Unchanged : RepoCheckResult

    /** The newest release was already looked at. */
    data class Seen(val tag: String) : RepoCheckResult

    data class NoBundles(val checked: Boolean) : RepoCheckResult

    /** A new release exists but its bundles were not fetched (a metered network): the next pass on an unmetered one does. */
    data class Deferred(val tag: String) : RepoCheckResult

    data class Processed(val tag: String, val outcomes: List<RepoUpdateOutcome>, val failures: List<String>) : RepoCheckResult

    /** GitHub said 401: the saved sign-in no longer works. */
    object SignInAgain : RepoCheckResult

    /** 404: the name is wrong, or the repository is private and nobody is signed in. */
    data class NotFound(val signedIn: Boolean) : RepoCheckResult

    data class RateLimited(val resetsAtEpochSeconds: Long?) : RepoCheckResult

    data class Failed(val reason: String) : RepoCheckResult
}

/**
 * The update pass for trusted plugin repositories (docs/SPEC.md 12a "Plugin
 * repositories"): for each repository the person added, read its newest
 * release, and replace an installed plugin from it when the new bundle is
 * signed by the very key that was trusted, is newer, and passes the
 * installer's own checks. What an update adds in permissions still waits
 * for the person ([PluginBundleInstaller] and the grant store do that);
 * this file only decides what to fetch and tells the person afterwards.
 * When plugins ask before installing, the pass reads the release the same
 * way and installs nothing: what it read is [pendingUpdates], which the
 * Updates page lists (Droidtop/tracker#837).
 * Everything here is blocking: call it off the main thread.
 */
object PluginRepoUpdates {
    private const val PREFS = "plugin_repo_updates"
    private const val KEY_AUTO = "auto_update"
    private const val KEY_PRERELEASES = "include_prereleases"
    private const val MAX_BUNDLES_PER_RELEASE = 8
    private const val MAX_BUNDLE_BYTES = 512L * 1024 * 1024
    private const val BUNDLE_SUFFIX = ".droidplugin.tar.xz"
    private val running = AtomicBoolean(false)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Updating installed plugins from trusted repositories by itself (on by default; the owner asked for auto-update). Off, the pass still reads each release and Updates lists what it found. */
    fun autoUpdate(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoUpdate(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO, value).apply()

    /** Whether releases marked pre-release count (off: a pre-release is never fetched). */
    fun includePrereleases(context: Context): Boolean = prefs(context).getBoolean(KEY_PRERELEASES, false)

    fun setIncludePrereleases(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_PRERELEASES, value).apply()

    /** The last result line shown under a repository: when it was checked and what came of it. */
    fun lastResult(context: Context, repo: String): String? = prefs(context).getString(key("result", repo), null)

    /** The latest release as the last check saw it: its tag and its bundle file names, for the "install from this repository" rows. Preferences only, so a settings screen may read it on any thread. */
    fun cachedRelease(context: Context, repo: String): Pair<String, List<String>>? {
        val text = prefs(context).getString(key("release", repo), null) ?: return null
        val lines = text.split('\n').filter { it.isNotBlank() }
        return if (lines.size >= 2) lines.first() to lines.drop(1) else null
    }

    /** What the last pass read of [repo]'s latest release, bundle by bundle. Preferences only. */
    fun releaseRead(context: Context, repo: String): RepoReleaseRead? = prefs(context).getString(key("read", repo), null)?.let { decodeRead(it) }

    /**
     * Every installed plugin a trusted repository has a newer version of, from what the last pass read (preferences and
     * the key store only, no network): the one answer the Updates page and the repository's screen read. A plugin a
     * catalog also offers, in [catalogVersions] (plugin id to the catalog's version), is left to the catalog unless the
     * repository's version is higher. Blocking reads: off the main thread.
     */
    fun pendingUpdates(
        context: Context,
        installed: List<PluginRecord> = PluginStore.installed(context),
        trusted: Map<String, UserOriginKey> = UserOriginKeys.load(UserOriginKeys.storeFile(context)),
        catalogVersions: Map<String, String> = emptyMap(),
    ): List<RepoPluginUpdate> = pending(installed, trusted, { repo -> releaseRead(context, repo) }, includePrereleases(context), catalogVersions)

    private fun key(kind: String, repo: String) = "$kind:${repo.lowercase()}"

    /** Forgets what is remembered about [repo], when it is removed. */
    fun forget(context: Context, repo: String) {
        prefs(context).edit()
            .remove(key("etag", repo)).remove(key("seen", repo)).remove(key("result", repo)).remove(key("release", repo))
            .remove(key("read", repo)).remove(key("read_id", repo))
            .apply()
    }

    // ----- pure parts (unit-tested) -----

    /** Releases from GitHub's list endpoint; drafts are dropped, and only bundle assets are kept. Never throws: an unreadable answer is an empty list. */
    fun parseReleases(json: String): List<RepoRelease> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val release = array.optJSONObject(index) ?: return@mapNotNull null
            if (release.optBoolean("draft")) return@mapNotNull null
            val assets = release.optJSONArray("assets") ?: JSONArray()
            val bundles = (0 until assets.length()).mapNotNull { a ->
                val asset = assets.optJSONObject(a) ?: return@mapNotNull null
                val name = asset.optString("name")
                if (!name.endsWith(BUNDLE_SUFFIX)) return@mapNotNull null
                val apiUrl = asset.optString("url")
                val downloadUrl = asset.optString("browser_download_url")
                if (apiUrl.isBlank() && downloadUrl.isBlank()) return@mapNotNull null
                val digest = asset.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                RepoBundleAsset(name, apiUrl, downloadUrl, asset.optLong("size", -1L), digest)
            }
            RepoRelease(
                id = release.optLong("id"),
                tag = release.optString("tag_name"),
                prerelease = release.optBoolean("prerelease"),
                publishedAt = release.optString("published_at").takeIf { it.isNotBlank() },
                bundles = bundles,
            )
        }
    }

    /** The newest release that carries bundles, ignoring pre-releases unless [includePrereleases]. Newest by publish time, the list's own order as the tie-break. */
    fun newestWithBundles(releases: List<RepoRelease>, includePrereleases: Boolean): RepoRelease? =
        releases.withIndex()
            .filter { (_, r) -> r.bundles.isNotEmpty() && (includePrereleases || !r.prerelease) }
            .sortedWith(compareByDescending<IndexedValue<RepoRelease>> { it.value.publishedAt.orEmpty() }.thenBy { it.index })
            .firstOrNull()?.value

    /**
     * The pending updates in [installed] from what each trusted repository's latest release was read to carry ([reads]
     * by repository name). A bundle counts when its plugin is installed from the very origin the repository is trusted
     * for and its version is newer ([PluginRepos.isNewer], the rule [PluginRepos.decide] applies when the pass
     * installs); a pre-release counts only with [includePrereleases]. One per plugin, the highest version, and none where
     * [catalogVersions] has the same or a higher one (a catalog's row is the one shown then). In [installed]'s order. Pure.
     */
    fun pending(
        installed: List<PluginRecord>,
        trusted: Map<String, UserOriginKey>,
        reads: (repo: String) -> RepoReleaseRead?,
        includePrereleases: Boolean,
        catalogVersions: Map<String, String> = emptyMap(),
    ): List<RepoPluginUpdate> {
        val byId = installed.associateBy { it.manifest.id }
        val best = HashMap<String, RepoPluginUpdate>()
        for (entry in PluginRepos.trustedRepos(trusted)) {
            val read = entry.repo?.let(reads) ?: continue
            if (read.prerelease && !includePrereleases) continue
            for (bundle in read.bundles) {
                val record = byId[bundle.pluginId] ?: continue
                if (record.manifest.origin != entry.origin) continue
                if (!PluginRepos.isNewer(bundle.version, record)) continue
                val kept = best[bundle.pluginId]
                if (kept == null || Versions.compareLoose(bundle.version, kept.version) > 0) {
                    best[bundle.pluginId] = RepoPluginUpdate(record, entry, read.tag, bundle)
                }
            }
        }
        return installed.mapNotNull { record ->
            val update = best[record.manifest.id] ?: return@mapNotNull null
            val catalog = catalogVersions[record.manifest.id]
            update.takeIf { catalog == null || Versions.compareLoose(update.version, catalog) > 0 }
        }
    }

    /** [RepoReleaseRead] as one preference: the tag, "prerelease" or "release", then a tab-separated line per bundle. Pure. */
    internal fun encodeRead(read: RepoReleaseRead): String {
        fun clean(text: String) = text.none { it == '\t' || it == '\n' || it == '\r' }
        val bundles = read.bundles
            .filter { clean(it.assetName) && clean(it.pluginId) && clean(it.version) && it.assetName.isNotBlank() && it.pluginId.isNotBlank() && it.version.isNotBlank() }
            .map { "${it.assetName}\t${it.pluginId}\t${it.version}\t${it.sizeBytes}" }
        return (listOf(read.tag.replace(Regex("[\\r\\n]"), " "), if (read.prerelease) "prerelease" else "release") + bundles).joinToString("\n")
    }

    /** [encodeRead] read back; null for anything else. Pure. */
    internal fun decodeRead(text: String): RepoReleaseRead? {
        val lines = text.split('\n')
        if (lines.size < 2 || lines[0].isBlank()) return null
        val bundles = lines.drop(2).mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 4 || parts.take(3).any { it.isBlank() }) return@mapNotNull null
            RepoBundleRead(parts[0], parts[1], parts[2], parts[3].toLongOrNull() ?: -1L)
        }
        return RepoReleaseRead(lines[0], lines[1] == "prerelease", bundles)
    }

    /** One sentence for an outcome. */
    fun describe(outcome: RepoUpdateOutcome): String = when (outcome) {
        is RepoUpdateOutcome.Updated -> {
            val access = if (outcome.newAccess.isEmpty) "" else " It asks for new access, which you decide on its page."
            "Updated ${outcome.label} to ${outcome.version}.$access"
        }
        is RepoUpdateOutcome.NeedsApproval -> "${outcome.label} ${outcome.version} is installed; open its page to approve it before it runs."
        is RepoUpdateOutcome.Offered -> "${outcome.label} ${outcome.version} is available to install."
        is RepoUpdateOutcome.Available -> "${outcome.label} ${outcome.version} is available. Update it from Updates."
        is RepoUpdateOutcome.Refused -> "${outcome.label}: not installed, ${outcome.reason}."
    }

    /** The summary line for a whole check. */
    fun describe(result: RepoCheckResult): String = when (result) {
        RepoCheckResult.Unchanged -> "Nothing new."
        is RepoCheckResult.Seen -> "Nothing new since ${result.tag}."
        is RepoCheckResult.NoBundles -> "No plugin bundles in its latest release."
        is RepoCheckResult.Deferred -> "${result.tag} is out; it is fetched on an unmetered connection."
        is RepoCheckResult.Processed -> (result.outcomes.map { describe(it) } + result.failures).ifEmpty { listOf("Nothing to update in ${result.tag}.") }.joinToString(" ")
        RepoCheckResult.SignInAgain -> "GitHub no longer accepts the saved sign-in. Sign in again."
        is RepoCheckResult.NotFound ->
            if (result.signedIn) "GitHub can't find it with your sign-in. Check the name, and that your sign-in may read it."
            else "GitHub can't find it. If it is private, sign in to GitHub first."
        is RepoCheckResult.RateLimited -> "GitHub's request limit is used up for now; it tries again later. Signing in raises the limit."
        is RepoCheckResult.Failed -> "Couldn't check: ${result.reason}."
    }

    /** True when something happened worth a notification. */
    fun isNotable(result: RepoCheckResult): Boolean =
        result is RepoCheckResult.Processed && (result.outcomes.isNotEmpty() || result.failures.isNotEmpty())

    // ----- the pass -----

    /**
     * Checks every trusted repository. With auto-update on it installs what is newer; off, it only reads the release, so
     * Updates lists what is newer and the person installs it from there (it never ran at all before, and a repository's
     * update showed nowhere: Droidtop/tracker#837). [metered] defers bundle downloads (the release listing is one small
     * request and still runs). [onResult] hears each notable result, for the notification. Skips quietly when a pass is
     * already running.
     */
    fun runDue(context: Context, metered: Boolean, onResult: (repo: String, RepoCheckResult) -> Unit = { _, _ -> }) {
        if (!running.compareAndSet(false, true)) return
        try {
            val install = autoUpdate(context)
            val keys = UserOriginKeys.load(UserOriginKeys.storeFile(context))
            for (entry in PluginRepos.trustedRepos(keys)) {
                val repo = entry.repo ?: continue
                val result = check(context, entry, metered, install = install)
                if (isNotable(result)) onResult(repo, result)
            }
        } finally {
            running.set(false)
        }
    }

    /** The manual "Check now": the same check, not deferred on a metered network. */
    fun checkNow(context: Context, entry: UserOriginKey): RepoCheckResult {
        if (!running.compareAndSet(false, true)) return RepoCheckResult.Failed("another check is running")
        try {
            return check(context, entry, metered = false, install = true, force = true)
        } finally {
            running.set(false)
        }
    }

    private fun check(context: Context, entry: UserOriginKey, metered: Boolean, install: Boolean, force: Boolean = false): RepoCheckResult {
        val repo = entry.repo ?: return RepoCheckResult.Failed("not a repository")
        val token = GitHubTokenStore.get(context)
        val result = try {
            checkRepo(context, entry, repo, token, metered, install, force)
        } catch (failure: Exception) {
            RepoCheckResult.Failed(failure.message ?: "no network")
        }
        val stamp = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date())
        prefs(context).edit().putString(key("result", repo), "Checked $stamp. ${describe(result)}").apply()
        return result
    }

    private fun checkRepo(context: Context, entry: UserOriginKey, repo: String, token: String?, metered: Boolean, install: Boolean, force: Boolean): RepoCheckResult {
        val etag = if (force) null else prefs(context).getString(key("etag", repo), null)
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            put("User-Agent", Http.USER_AGENT)
            etag?.let { put("If-None-Match", it) }
        }
        val connection = GitHubAuth.open("https://api.github.com/repos/$repo/releases?per_page=10", token, 15_000, 30_000, headers)
        var listEtag: String? = null
        val releases: List<RepoRelease> = try {
            when (val status = connection.responseCode) {
                200 -> {
                    val parsed = parseReleases(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                    listEtag = connection.getHeaderField("ETag")
                    parsed
                }
                304 -> return RepoCheckResult.Unchanged
                401 -> return RepoCheckResult.SignInAgain
                404 -> return RepoCheckResult.NotFound(signedIn = token != null)
                403, 429 -> {
                    val remaining = connection.getHeaderField("X-RateLimit-Remaining")
                    return if (status == 429 || remaining == "0" || connection.getHeaderField("Retry-After") != null) {
                        RepoCheckResult.RateLimited(connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull())
                    } else {
                        RepoCheckResult.Failed("GitHub answered HTTP 403")
                    }
                }
                else -> return RepoCheckResult.Failed("GitHub answered HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
        // The list's ETag is kept only once its newest release is dealt with, so GitHub's 304 never hides one still to
        // fetch: before, a release deferred on a metered network was never fetched while the list stayed the same.
        fun keepEtag() = listEtag?.let { prefs(context).edit().putString(key("etag", repo), it).apply() }
        val release = newestWithBundles(releases, includePrereleases(context)) ?: run {
            keepEtag()
            return RepoCheckResult.NoBundles(checked = true)
        }
        prefs(context).edit().putString(key("release", repo), (listOf(release.tag) + release.bundles.map { it.name }).joinToString("\n")).apply()
        val seenKey = key("seen", repo)
        val readKey = key("read_id", repo)
        if (!force && prefs(context).getLong(seenKey, 0L) == release.id) {
            keepEtag()
            return RepoCheckResult.Seen(release.tag)
        }
        // Asking first: a release already read is not fetched again; what it carries is in Updates.
        if (!install && !force && prefs(context).getLong(readKey, 0L) == release.id) return RepoCheckResult.Seen(release.tag)
        if (metered && !force) return RepoCheckResult.Deferred(release.tag)
        val outcomes = mutableListOf<RepoUpdateOutcome>()
        val failures = mutableListOf<String>()
        val read = mutableListOf<RepoBundleRead>()
        var transientFailure = false
        for (asset in release.bundles.take(MAX_BUNDLES_PER_RELEASE)) {
            when (val one = processBundle(context, entry, asset, token, install)) {
                is Processed.Outcome -> {
                    one.outcome?.let(outcomes::add)
                    one.read?.let(read::add)
                }
                is Processed.Failure -> {
                    failures.add("${asset.name}: ${one.reason}.")
                    if (one.transient) transientFailure = true
                }
            }
        }
        // What each bundle carries, for pendingUpdates: the Updates page's rows for this repository.
        prefs(context).edit().putString(key("read", repo), encodeRead(RepoReleaseRead(release.tag, release.prerelease, read))).apply()
        // A release is marked looked-at only when every bundle got an answer; a dropped connection is tried again next pass.
        // Only a pass that installs marks it seen: a release only read is still installed once updates are automatic.
        if (!transientFailure) {
            if (install) {
                prefs(context).edit().putLong(seenKey, release.id).apply()
                keepEtag()
            } else {
                prefs(context).edit().putLong(readKey, release.id).apply()
            }
        }
        return RepoCheckResult.Processed(release.tag, outcomes, failures)
    }

    private sealed interface Processed {
        /** [outcome] is null for a bundle that needs no mention (already current); [read] is what the bundle carries, when it is this repository's to offer. */
        data class Outcome(val outcome: RepoUpdateOutcome?, val read: RepoBundleRead? = null) : Processed
        data class Failure(val reason: String, val transient: Boolean) : Processed
    }

    /**
     * Downloads one bundle, reads its manifest, and, when [PluginRepos.decide] allows and [install] is on, installs it
     * through the installer (which verifies all of it again). A bundle for a plugin that is not
     * installed is only offered: the pass never installs a plugin the person has not chosen.
     */
    private fun processBundle(context: Context, entry: UserOriginKey, asset: RepoBundleAsset, token: String?, install: Boolean): Processed {
        if (asset.size > MAX_BUNDLE_BYTES) return Processed.Failure("larger than the ${MAX_BUNDLE_BYTES / (1024 * 1024)} MiB limit", transient = false)
        val dir = File(context.cacheDir, "plugin-repo-updates").apply { mkdirs() }
        val file = File(dir, "bundle-${System.nanoTime()}.droidplugin.tar.xz")
        try {
            val address = if (token != null && asset.apiUrl.isNotBlank()) asset.apiUrl else asset.downloadUrl
            if (address.isBlank()) return Processed.Failure("it has no download address", transient = false)
            val (url, headers) = GitHubAuth.downloadRequestFor(address, token)
            val fetched = download(context, url, headers, asset.sha256, file, asset.name)
            if (!fetched.ok) {
                val mismatch = fetched.error == dev.droidtop.pluginhost.DownloadJobs.DIGEST_MISMATCH
                return Processed.Failure(if (mismatch) "the download doesn't match GitHub's checksum" else "the download failed: ${fetched.error}", transient = !mismatch)
            }
            val peeked = PluginRepos.peek(file) ?: return Processed.Failure("not a plugin bundle", transient = false)
            val userKeys = UserOriginKeys.loadBase64(UserOriginKeys.storeFile(context))
            val label = peeked.manifest.label
            val read = RepoBundleRead(asset.name, peeked.manifest.id, peeked.manifest.version, asset.size)
            return when (val decision = PluginRepos.decide(peeked, entry.origin, userKeys, PluginStore.installed(context))) {
                PluginRepos.UpdateDecision.UpToDate -> Processed.Outcome(null, read)
                PluginRepos.UpdateDecision.NotInstalled -> Processed.Outcome(RepoUpdateOutcome.Offered(label, peeked.manifest.version), read)
                // Another origin's, or not signed with the trusted key: not this repository's to offer, so not read.
                is PluginRepos.UpdateDecision.Refused -> Processed.Outcome(RepoUpdateOutcome.Refused(label, decision.reason))
                is PluginRepos.UpdateDecision.Apply -> if (!install) {
                    Processed.Outcome(RepoUpdateOutcome.Available(label, peeked.manifest.version), read)
                } else {
                    when (val result = PluginBundleInstaller.install(file, PluginStore.root(context), userKeys, hostPermissions = dev.droidtop.pluginhost.AndroidPermissions.heldBy(context), succeeds = dev.droidtop.pluginhost.UserOriginKeys.successorCheck(context))) {
                        // Still newer than what is installed, so Updates keeps offering it, and its Update says why it fails.
                        is PluginInstallResult.Refused -> Processed.Outcome(RepoUpdateOutcome.Refused(label, result.error.reason), read)
                        is PluginInstallResult.Installed ->
                            if (result.record.trust == PluginTrustState.APPROVED) {
                                Processed.Outcome(RepoUpdateOutcome.Updated(label, result.record.manifest.version, decision.newAccess), read)
                            } else {
                                Processed.Outcome(RepoUpdateOutcome.NeedsApproval(label, result.record.manifest.version), read)
                            }
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    /**
     * One bundle as a Downloads job (docs/SPEC.md 12a, "One download engine"): droidtop's one download path fetches it,
     * checks it against GitHub's [sha256] and moves it to [file]. The token header is held in memory by Downloads, never
     * written down. Not an automatic download yet: the pass waits on the job, and the update window could hold it for hours, so
     * the window applies once the pass hands its bundles over instead of waiting. Blocks: these passes run off the main
     * thread.
     */
    private fun download(context: Context, url: String, headers: Map<String, String>, sha256: String?, file: File, assetName: String) =
        kotlinx.coroutines.runBlocking {
            dev.droidtop.pluginhost.DownloadJobs.run(
                context = context,
                title = "Plugin update: $assetName",
                category = dev.droidtop.net.DownloadCategory.PLUGINS,
                post = dev.droidtop.pluginhost.DownloadJobs.POST_MOVE_TO,
                url = url,
                name = "bundle_${System.nanoTime()}.droidplugin.tar.xz",
                sha256 = sha256,
                headers = headers,
                extra = mapOf(dev.droidtop.pluginhost.DownloadJobs.ARG_TARGET_PATH to file.absolutePath),
            )
        }

    /**
     * Installs one bundle of the repository's latest release by hand: a plugin not installed yet, which arrives unapproved
     * like any new plugin, or an update the Updates page lists ([pendingUpdates]), which keeps its approval when it is
     * signed with the same key. [onInstalled] gets the id of a plugin that now waits for approval, for the caller to open
     * its page, where it is approved (Droidtop/tracker#759). Blocking: off the main thread.
     */
    fun installFromRepo(context: Context, entry: UserOriginKey, assetName: String, onInstalled: (pluginId: String) -> Unit = {}): RepoInstallOutcome {
        fun refused(line: String) = RepoInstallOutcome(installed = false, line = line)
        val repo = entry.repo ?: return refused("Not a repository.")
        val token = GitHubTokenStore.get(context)
        val release = latestRelease(context, repo, token) ?: return refused("Couldn't read the latest release.")
        val asset = release.bundles.firstOrNull { it.name == assetName } ?: return refused("That bundle is no longer in the latest release.")
        val dir = File(context.cacheDir, "plugin-repo-updates").apply { mkdirs() }
        val file = File(dir, "bundle-${System.nanoTime()}.droidplugin.tar.xz")
        try {
            val address = if (token != null && asset.apiUrl.isNotBlank()) asset.apiUrl else asset.downloadUrl
            val (url, headers) = GitHubAuth.downloadRequestFor(address, token)
            val fetched = download(context, url, headers, asset.sha256, file, asset.name)
            if (!fetched.ok) return refused("Couldn't download it: ${fetched.error}")
            val peeked = PluginRepos.peek(file) ?: return refused("That is not a plugin bundle.")
            if (peeked.manifest.origin != entry.origin) return refused("That bundle is for another origin than this repository is trusted for.")
            val userKeys = UserOriginKeys.loadBase64(UserOriginKeys.storeFile(context))
            return when (val result = PluginBundleInstaller.install(file, PluginStore.root(context), userKeys, hostPermissions = dev.droidtop.pluginhost.AndroidPermissions.heldBy(context), succeeds = dev.droidtop.pluginhost.UserOriginKeys.successorCheck(context))) {
                is PluginInstallResult.Refused -> refused("Not installed: ${result.error.reason}")
                is PluginInstallResult.Installed -> {
                    val installed = "${result.record.manifest.label} ${result.record.manifest.version} installed."
                    if (result.record.trust == PluginTrustState.APPROVED) {
                        RepoInstallOutcome(installed = true, line = installed)
                    } else {
                        onInstalled(result.record.manifest.id)
                        RepoInstallOutcome(installed = true, line = "$installed Open it to approve it before it runs.")
                    }
                }
            }
        } catch (failure: Exception) {
            return refused("Couldn't download it (${failure.message ?: "no network"}).")
        } finally {
            file.delete()
        }
    }

    /** The newest release with bundles, read fresh. Null on any failure. */
    fun latestRelease(context: Context, repo: String, token: String? = GitHubTokenStore.get(context)): RepoRelease? = runCatching {
        val connection = GitHubAuth.open(
            "https://api.github.com/repos/$repo/releases?per_page=10",
            token,
            15_000,
            30_000,
            mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"),
        )
        try {
            if (connection.responseCode != 200) return@runCatching null
            newestWithBundles(parseReleases(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }), includePrereleases(context))
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
