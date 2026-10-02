package dev.droidtop.pluginhost

import dev.droidtop.net.GitHubAuth
import java.io.ByteArrayOutputStream
import org.json.JSONArray

/**
 * Reads the key a plugin repository publishes (docs/SPEC.md 12a "Plugin
 * repositories"). The trusted reference is the `droidtop-plugin-key.json`
 * COMMITTED at the root of the repository's default branch (the same two-field
 * file every source publishes, [PluginSourceKeys]): a human-reviewed commit,
 * independent of the build, so a hijacked CI cannot choose the key. The copy
 * the repository's CI attaches to its releases (including `build-<branch>`
 * ones) is only a cross-check: it must be the same key and origin as the root
 * file, or the repository is refused ("the key published by the build does not
 * match the key committed to the repository"). A missing root file is refused
 * too; a release copy never stands in for it. Nothing returned here is
 * trusted by itself, and no key material is created or handled: this only
 * reads what is published.
 */
object PluginRepoKeys {
    /** How the build's copy of the key compared with the committed one. */
    sealed interface ReleaseCheck {
        /** The newest release that carries the file has the same origin and key. */
        data class Matches(val releaseTag: String) : ReleaseCheck

        /** No release carries the file: nothing to compare. */
        object NoCopy : ReleaseCheck
    }

    data class Found(val key: PluginSourceKeys.PublishedKey, val release: ReleaseCheck) {
        /** Where the key came from and whether the build agrees, for the confirmation screen. */
        fun describeSources(repo: String): String {
            val root = "Committed to the root of $repo (${PluginSourceKeys.KEY_FILE_NAME} on its default branch)"
            val build = when (release) {
                is ReleaseCheck.Matches -> "the copy attached to release ${release.releaseTag} matches it"
                ReleaseCheck.NoCopy -> "no release carries a copy to compare it with"
            }
            return "$root; $build"
        }
    }

    sealed interface Result {
        data class Fetched(val found: Found) : Result
        data class Failed(val reason: String) : Result
    }

    data class KeyAsset(val releaseTag: String, val apiUrl: String, val downloadUrl: String)

    /**
     * The key asset of the newest release (by publish time, the list's own order as the tie-break)
     * that has one. Drafts are skipped; pre-releases and `build-*` releases count. Null when there is none or the answer is unreadable.
     */
    fun pickKeyAsset(releasesJson: String): KeyAsset? {
        val array = runCatching { JSONArray(releasesJson) }.getOrNull() ?: return null
        val candidates = (0 until array.length()).mapNotNull { index ->
            val release = array.optJSONObject(index) ?: return@mapNotNull null
            if (release.optBoolean("draft")) return@mapNotNull null
            val assets = release.optJSONArray("assets") ?: return@mapNotNull null
            val asset = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name") == PluginSourceKeys.KEY_FILE_NAME } ?: return@mapNotNull null
            val apiUrl = asset.optString("url")
            val downloadUrl = asset.optString("browser_download_url")
            if (apiUrl.isBlank() && downloadUrl.isBlank()) return@mapNotNull null
            Triple(index, release.optString("published_at"), KeyAsset(release.optString("tag_name"), apiUrl, downloadUrl))
        }
        return candidates.sortedWith(compareByDescending<Triple<Int, String, KeyAsset>> { it.second }.thenBy { it.first })
            .firstOrNull()?.third
    }

    const val MISMATCH =
        "the key published by the build does not match the key committed to the repository: this can mean the build was tampered with"

    /**
     * The committed root file first and required, then the release copy as a cross-check. A release
     * copy that cannot be read, or whose origin or key differs, refuses the repository. [rootKey]
     * is the branch read, [getReleases] returns the releases JSON or null, [getAsset] the asset bytes or null.
     */
    fun fetch(
        repo: String,
        token: String?,
        rootKey: () -> PluginSourceKeys.FetchResult = { PluginSourceKeys.fetchKey(PluginRepos.sourceUrl(repo), token) },
        getReleases: (String) -> String? = { fetchText(it, token) },
        getAsset: (KeyAsset) -> ByteArray? = { fetchAsset(it, token) },
    ): Result {
        val committed = when (val root = rootKey()) {
            is PluginSourceKeys.FetchResult.Failed ->
                return Result.Failed("${PluginSourceKeys.KEY_FILE_NAME} is not committed at the root of $repo, so it can't be trusted (${root.reason})")
            is PluginSourceKeys.FetchResult.Fetched -> root.key
        }
        val asset = getReleases("https://api.github.com/repos/$repo/releases?per_page=30")?.let(::pickKeyAsset)
            ?: return Result.Fetched(Found(committed, ReleaseCheck.NoCopy))
        val copy = getAsset(asset)?.let { PluginSourceKeys.parsePublishedKey(it.toString(Charsets.UTF_8)) }
            ?: return Result.Failed("the copy of the key attached to release ${asset.releaseTag} can't be read, so it can't be compared: $MISMATCH")
        if (copy.origin != committed.origin || !PluginRepos.sameKey(copy.keyBase64, committed.keyBase64)) {
            return Result.Failed("${MISMATCH.replaceFirstChar { it.uppercase() }} (release ${asset.releaseTag}).")
        }
        return Result.Fetched(Found(committed, ReleaseCheck.Matches(asset.releaseTag)))
    }

    private const val MAX_BYTES = 1024 * 1024

    private fun fetchText(url: String, token: String?): String? = runCatching {
        val connection = GitHubAuth.open(url, token, 15_000, 30_000, mapOf("Accept" to "application/vnd.github+json"))
        try {
            if (connection.responseCode != 200) return@runCatching null
            readCapped(connection.inputStream, MAX_BYTES)?.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** The asset through its API address when there is a token (the only address a private repository serves), else the public one. */
    private fun fetchAsset(asset: KeyAsset, token: String?): ByteArray? = runCatching {
        val address = if (token != null && asset.apiUrl.isNotBlank()) asset.apiUrl else asset.downloadUrl
        val connection = GitHubAuth.open(address, token, 15_000, 30_000)
        try {
            if (connection.responseCode != 200) return@runCatching null
            readCapped(connection.inputStream, 64 * 1024)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun readCapped(input: java.io.InputStream, cap: Int): ByteArray? = input.use {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = it.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > cap) return@use null
        }
        out.toByteArray()
    }
}
