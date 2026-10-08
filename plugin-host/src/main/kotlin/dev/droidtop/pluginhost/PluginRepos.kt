package dev.droidtop.pluginhost

import dev.droidtop.runtime.util.Versions
import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONObject

/**
 * Plugin repositories (docs/SPEC.md 12a "Plugin repositories"): a GitHub
 * repository the person adds by name, whose published key droidtop trusts
 * once they confirm it, and whose releases then keep its installed plugins
 * up to date. This file holds the decisions and nothing with a network or
 * a screen in it, so they are unit-tested directly; the key file is the
 * same `droidtop-plugin-key.json` every plugin source publishes
 * ([PluginSourceKeys]), and the trust it creates is an ordinary entry in
 * [UserOriginKeys], with the repository's name beside it.
 */
object PluginRepos {
    private val OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
    private val NAME = Regex("[A-Za-z0-9._-]{1,100}")

    /**
     * "owner/name" from what a person types: the name itself, or the address a browser shows
     * (`https://github.com/owner/name`, with or without `.git` or a trailing path). Null when it is
     * neither. The case is kept as typed, for display; comparisons ignore it ([sameRepo]).
     */
    fun parse(input: String): String? {
        var text = input.trim().trimEnd('/')
        var isAddress = false
        for (prefix in listOf("https://github.com/", "https://www.github.com/", "github.com/")) {
            if (text.startsWith(prefix, ignoreCase = true)) {
                text = text.substring(prefix.length)
                isAddress = true
                break
            }
        }
        val parts = text.split('/').filter { it.isNotEmpty() }
        // A bare name has exactly two parts; an address may carry more (tree/main, releases).
        if (parts.size < 2 || (!isAddress && parts.size != 2)) return null
        val owner = parts[0]
        val name = parts[1].removeSuffix(".git")
        if (!OWNER.matches(owner) || !NAME.matches(name) || name == "." || name == "..") return null
        return "$owner/$name"
    }

    fun sameRepo(a: String?, b: String?): Boolean = a != null && b != null && a.equals(b, ignoreCase = true)

    /** The address the key and the releases are read from. */
    fun sourceUrl(repo: String): String = "https://github.com/$repo"

    /** Whether two SPKI blobs are the same key, compared by their decoded bytes. */
    fun sameKey(a: String, b: String): Boolean {
        val left = PluginOriginKeys.parseSpki(a)?.encoded ?: return false
        val right = PluginOriginKeys.parseSpki(b)?.encoded ?: return false
        return left.contentEquals(right)
    }

    /** What adding a repository may do with the key it publishes. */
    sealed interface TrustDecision {
        /** Nothing is trusted for this origin yet: show the confirmation, then trust. */
        data class Propose(val published: PluginSourceKeys.PublishedKey) : TrustDecision

        /** The same key is already trusted, from a source that was not a repository: confirm, then record the repository beside it. */
        data class Adopt(val published: PluginSourceKeys.PublishedKey) : TrustDecision

        data class AlreadyTrusted(val published: PluginSourceKeys.PublishedKey) : TrustDecision

        /** The repository now publishes a different key for an origin trusted before. Refused, never replaced here. */
        data class KeyChanged(val origin: String, val trustedFingerprint: String, val publishedFingerprint: String) : TrustDecision

        /** The repository was trusted under another origin id, and now names a new one: the same disguise as a changed key. */
        data class OriginChanged(val trustedOrigin: String, val publishedOrigin: String) : TrustDecision

        /** This origin id is already trusted for another repository. */
        data class OriginTaken(val origin: String, val byRepo: String) : TrustDecision
    }

    /**
     * What to do with the key [repo] published, given the keys already trusted. A changed key is
     * refused outright: unlike "Keys you trust" there is no replace here, because from a repository
     * the person added by name a different key is exactly what a hijacked repository looks like.
     * They remove the repository and add it again, which shows both fingerprints afresh.
     */
    fun decide(repo: String, published: PluginSourceKeys.PublishedKey, trusted: Map<String, UserOriginKey>): TrustDecision {
        trusted.values.firstOrNull { sameRepo(it.repo, repo) && it.origin != published.origin }?.let {
            return TrustDecision.OriginChanged(it.origin, published.origin)
        }
        val stored = trusted[published.origin] ?: return TrustDecision.Propose(published)
        if (!sameKey(stored.keyBase64, published.keyBase64)) {
            return TrustDecision.KeyChanged(
                published.origin,
                UserOriginKeys.fingerprint(stored.keyBase64) ?: "unreadable",
                UserOriginKeys.fingerprint(published.keyBase64) ?: "unreadable",
            )
        }
        return when {
            stored.repo == null -> TrustDecision.Adopt(published)
            sameRepo(stored.repo, repo) -> TrustDecision.AlreadyTrusted(published)
            else -> TrustDecision.OriginTaken(published.origin, stored.repo)
        }
    }

    /** The trusted repositories: origin, key and the repository name, for the update pass and the screen. */
    fun trustedRepos(trusted: Map<String, UserOriginKey>): List<UserOriginKey> =
        trusted.values.filter { it.repo != null }.sortedBy { it.repo!!.lowercase() }

    // ----- Updates -----

    /**
     * A bundle's manifest and signature, read without extracting anything. No certificate: a
     * repository here is a user-trusted origin, never the official one, and a certificate only
     * speaks for the official origin ([BundleSignature.verifyBundle]).
     */
    class Peeked(val manifest: PluginManifest, val manifestBytes: ByteArray, val signatureBase64: String)

    /**
     * Reads `manifest.json` and `manifest.sig` out of a `.droidplugin.tar.xz` and stops; nothing is
     * written. The result is only a reason to look closer: [PluginBundleInstaller.install] still
     * verifies everything before anything is on disk. Null when the file is not a bundle.
     */
    fun peek(bundle: File): Peeked? = runCatching {
        var manifestBytes: ByteArray? = null
        var signature: String? = null
        TarArchiveInputStream(XZCompressorInputStream(bundle.inputStream().buffered())).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null && (manifestBytes == null || signature == null)) {
                if (!entry.isDirectory && entry.size <= MAX_MANIFEST_BYTES) {
                    when (File(entry.name).path) {
                        "manifest.json" -> manifestBytes = tar.readBytes()
                        "manifest.sig" -> signature = tar.readBytes().toString(Charsets.US_ASCII)
                    }
                }
                entry = tar.nextTarEntry
            }
        }
        val bytes = manifestBytes ?: return@runCatching null
        val manifest = PluginManifest.fromJson(JSONObject(bytes.toString(Charsets.UTF_8))) ?: return@runCatching null
        Peeked(manifest, bytes, signature ?: return@runCatching null)
    }.getOrNull()

    private const val MAX_MANIFEST_BYTES = 64L * 1024

    /** What an auto-update pass may do with one bundle from a trusted repository. */
    sealed interface UpdateDecision {
        /** Install it over [installed]; [newAccess] names what it adds, which the person is asked about afterwards. */
        data class Apply(val installed: PluginRecord, val newAccess: PermissionDiff) : UpdateDecision

        /** Not installed: offered to install by hand, never installed by the pass. */
        object NotInstalled : UpdateDecision

        object UpToDate : UpdateDecision

        /** [reason] is a sentence for the screen. */
        data class Refused(val reason: String) : UpdateDecision
    }

    /**
     * Whether a bundle found in a trusted repository's release may replace what is installed. The
     * bundle must be signed by the origin's trusted key AND be for the origin this repository was
     * trusted for (a repository cannot update another origin's plugins), the plugin must already be
     * installed from that origin, and the version must be newer. Approval itself is not decided here:
     * [PluginBundleInstaller.install] carries it over only for a same-key update, and
     * [PluginGrants.applyUpdate] leaves everything the update adds waiting at "ask".
     */
    fun decide(
        peeked: Peeked,
        repoOrigin: String,
        userKeys: Map<String, String>,
        installed: List<PluginRecord>,
        /** The signature check; replaceable so the eligibility rules are tested without any key material. */
        signatureValid: (Peeked) -> Boolean = {
            BundleSignature.verifyBundle(it.manifestBytes, it.signatureBase64, it.manifest.origin, it.manifest.id, null, userKeys) is BundleVerdict.Verified
        },
    ): UpdateDecision {
        val manifest = peeked.manifest
        if (manifest.origin != repoOrigin) {
            return UpdateDecision.Refused("signed for origin \"${manifest.origin}\", not \"$repoOrigin\" which this repository is trusted for")
        }
        if (!signatureValid(peeked)) {
            return UpdateDecision.Refused("its signature does not verify against the key you trusted for \"$repoOrigin\"")
        }
        val current = installed.firstOrNull { it.manifest.id == manifest.id } ?: return UpdateDecision.NotInstalled
        if (current.manifest.origin != manifest.origin) {
            return UpdateDecision.Refused("\"${manifest.id}\" is installed from origin \"${current.manifest.origin}\"")
        }
        if (Versions.compareLoose(manifest.version, current.manifest.version) <= 0) return UpdateDecision.UpToDate
        return UpdateDecision.Apply(current, PermissionDiff.between(current.manifest, manifest))
    }
}
