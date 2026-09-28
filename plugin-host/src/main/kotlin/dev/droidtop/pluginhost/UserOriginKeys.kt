package dev.droidtop.pluginhost

import android.content.Context
import java.io.File
import java.security.PublicKey
import java.util.Base64
import org.json.JSONObject

/**
 * One origin the USER chose to trust (docs/SPEC.md 12a "Keys you trust"):
 * the origin's own id, its P-256 verification key (SPKI, base64 -- the
 * same shape [PluginOriginKeys.OFFICIAL_ORIGIN]'s pinned key uses), and
 * the source URL the key was fetched from when it was, so the row can
 * show provenance. Never the official origin: [UserOriginKeys.add]
 * refuses that id outright, and nothing here ever feeds
 * [PluginOriginKeys]'s pinned set.
 */
data class UserOriginKey(val origin: String, val keyBase64: String, val source: String?)

/** What [UserOriginKeys.add] (or [UserOriginKeys.replace]) decided, verbatim on the settings screen. */
sealed interface AddKeyOutcome {
    data class Added(val entry: UserOriginKey) : AddKeyOutcome
    object AlreadyTrustedSameKey : AddKeyOutcome
    data class KeyChanged(val stored: UserOriginKey, val proposed: UserOriginKey) : AddKeyOutcome
    data class Refused(val reason: String) : AddKeyOutcome
}

/**
 * The store of user-trusted plugin origin keys, in droidtop's own
 * private storage (`filesDir/plugin-user-keys.json`, [storeFile]) --
 * never in the official pinned set, never synced anywhere. One JSON
 * object keyed by origin id: `{ "<origin>": { "key": "<SPKI base64>",
 * "source": "<url, absent when pasted by hand>" } }`, written via a
 * temp file + rename so a failed write cannot leave a half-written
 * store that would silently untrust every third-party plugin.
 *
 * `add` never overwrites: a different key for an origin that is already
 * trusted is [AddKeyOutcome.KeyChanged] with NOTHING written -- the
 * changed-key rule (docs/SPEC.md 12a) makes the rotation-vs-compromise
 * call the user's, through the explicit "replace" confirmation the
 * screen offers; [replace] is that one confirmed path. Manual
 * paste/file goes through `add` only, so rotating by hand means
 * removing the origin and adding it again, two explicit steps.
 */
object UserOriginKeys {
    private const val STORE_NAME = "plugin-user-keys.json"
    private const val MAX_ORIGIN_ID_LEN = 64

    fun storeFile(context: Context): File = File(context.filesDir, STORE_NAME)

    /** Every stored entry, for the "Keys you trust" screen; empty when there is nothing to read. */
    fun load(file: File): Map<String, UserOriginKey> {
        if (!file.isFile) return emptyMap()
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyMap()
        return buildMap {
            for (key in json.keys()) {
                val entry = runCatching { json.optJSONObject(key) }.getOrNull() ?: continue
                val keyBase64 = entry.optString("key").takeIf { it.isNotBlank() } ?: continue
                val source = if (entry.isNull("source")) null else entry.optString("source").takeIf { it.isNotBlank() }
                put(key, UserOriginKey(origin = key, keyBase64 = keyBase64, source = source))
            }
        }
    }

    /** The same store as verification consumes it: origin -> SPKI base64, passed as [PluginOriginKeys.resolve]'s `userKeys`. */
    fun loadBase64(file: File): Map<String, String> = load(file).mapValues { it.value.keyBase64 }

    private fun save(file: File, keys: Map<String, UserOriginKey>) {
        val json = JSONObject()
        for ((origin, entry) in keys) {
            json.put(origin, JSONObject().put("key", entry.keyBase64).apply { entry.source?.let { put("source", it) } })
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            // Same-volume rename failing means the store cannot be
            // replaced this moment; the old store stays authoritative
            // rather than half of the new one.
            tmp.delete()
            throw java.io.IOException("couldn't replace ${file.name}")
        }
    }

    /**
     * Why an origin id can't be trusted, or null when it can. Lowercase
     * letters, digits and hyphens only: the id prefixes every plugin id
     * (`<origin>.<name>`), which [PluginManifest.structuralProblems]
     * already forces to lowercase -- an origin with a dot or an
     * uppercase letter could never name an installable plugin.
     */
    fun originProblem(origin: String): String? {
        val id = origin.trim()
        if (id.isEmpty()) return "origin id can't be blank"
        if (id == PluginOriginKeys.OFFICIAL_ORIGIN) {
            return "the origin id \"${PluginOriginKeys.OFFICIAL_ORIGIN}\" is official -- a third-party origin can't claim it"
        }
        if (!id.matches(Regex("[a-z0-9-]+"))) {
            return "origin id must be lowercase letters, digits and hyphens (it prefixes every plugin id, \"<origin>.<name>\")"
        }
        if (id.length > MAX_ORIGIN_ID_LEN) return "origin id must be at most $MAX_ORIGIN_ID_LEN characters"
        return null
    }

    /**
     * Validates and canonicalizes a pasted/fetched key: parses it as a
     * P-256 SPKI (lenient about base64 line breaks a paste can carry)
     * and returns the SAME key re-encoded as single-line base64 -- so
     * the store only ever holds one form of a key and comparisons and
     * [PluginOriginKeys.resolve] can be exact. Null when the blob is
     * not a P-256 SPKI at all.
     */
    private fun canonicalKey(keyBase64: String): String? {
        val parsed = PluginOriginKeys.parseSpki(keyBase64) ?: return null
        return Base64.getEncoder().encodeToString(parsed.encoded)
    }

    /**
     * Trusts `keyBase64` for `origin`, never overwriting. Same key as
     * already trusted -> [AddKeyOutcome.AlreadyTrustedSameKey] (compared
     * by the decoded SPKI bytes, so a re-encoded paste of the same key
     * is the same key); a DIFFERENT key for a trusted origin ->
     * [AddKeyOutcome.KeyChanged] with nothing written.
     */
    fun add(file: File, origin: String, keyBase64: String, source: String?): AddKeyOutcome {
        val id = origin.trim()
        originProblem(id)?.let { return AddKeyOutcome.Refused(it) }
        val key = canonicalKey(keyBase64)
            ?: return AddKeyOutcome.Refused("that key isn't a valid P-256 public key (base64 of the X.509 SubjectPublicKeyInfo)")
        val stored = load(file)
        val existing = stored[id]
        if (existing != null) {
            return if (fingerprint(key) == fingerprint(existing.keyBase64)) {
                AddKeyOutcome.AlreadyTrustedSameKey
            } else {
                AddKeyOutcome.KeyChanged(existing, UserOriginKey(id, key, source))
            }
        }
        val entry = UserOriginKey(origin = id, keyBase64 = key, source = source?.trim()?.takeIf { it.isNotBlank() })
        runCatching { save(file, stored + (id to entry)) }
            .onFailure { return AddKeyOutcome.Refused("couldn't save the key: ${it.message}") }
        return AddKeyOutcome.Added(entry)
    }

    /**
     * The ONE path that overwrites a trusted origin's key -- reachable
     * only from the changed-key warning's explicit confirmation
     * (docs/SPEC.md 12a), which showed both fingerprints first. Same
     * validation and same-key shortcut as [add].
     */
    fun replace(file: File, origin: String, keyBase64: String, source: String?): AddKeyOutcome {
        val id = origin.trim()
        originProblem(id)?.let { return AddKeyOutcome.Refused(it) }
        val key = canonicalKey(keyBase64)
            ?: return AddKeyOutcome.Refused("that key isn't a valid P-256 public key (base64 of the X.509 SubjectPublicKeyInfo)")
        val stored = load(file)
        val existing = stored[id] ?: return AddKeyOutcome.Refused("no trusted key for origin \"$id\" to replace")
        if (fingerprint(key) == fingerprint(existing.keyBase64)) return AddKeyOutcome.AlreadyTrustedSameKey
        val entry = UserOriginKey(origin = id, keyBase64 = key, source = source?.trim()?.takeIf { it.isNotBlank() })
        runCatching { save(file, stored + (id to entry)) }
            .onFailure { return AddKeyOutcome.Refused("couldn't save the key: ${it.message}") }
        return AddKeyOutcome.Added(entry)
    }

    /** Stops trusting `origin`; false when it wasn't trusted to begin with. Plugins it signed stop verifying the moment this returns true. */
    fun remove(file: File, origin: String): Boolean {
        val stored = load(file)
        if (origin !in stored) return false
        runCatching { save(file, stored - origin) }.onFailure { return false }
        return true
    }

    /**
     * The eye-comparable form of a key for prompts and rows: SHA-256 of
     * the SPKI bytes, first 16 hex chars in groups of four, so a
     * changed-key warning can name both keys a person can actually
     * diff. Null when the blob isn't a parseable SPKI.
     */
    fun fingerprint(keyBase64: String): String? {
        val key: PublicKey = PluginOriginKeys.parseSpki(keyBase64) ?: return null
        return BundleSignature.sha256(key.encoded)
            .take(16)
            .chunked(4)
            .joinToString(" ")
    }
}
