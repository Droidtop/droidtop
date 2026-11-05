package dev.droidtop.library.storage

import android.content.Context
import dev.droidtop.library.credentials.CredentialStore
import dev.droidtop.net.peer.AgentNative
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One of the person's own network shares or cloud storages (docs/SPEC.md "Network and cloud storage",
 * Droidtop/tracker#573). droidtop reaches it through lib-net's share client in its native library. The
 * password is never here: it is in the [CredentialStore] vault under [passwordKey].
 *
 * [trusted] is the person's answer to "Do you trust this share?" (owner, 2026-11-05: "That depends on their
 * contents, I suppose, and if the user trusts the share. Encryption should probably be toggleable"). On a trusted
 * share droidtop writes plain files; on one that is not, everything droidtop writes there is sealed.
 */
data class StorageShare(
    val id: String,
    val name: String,
    val kind: String,
    val url: String,
    val user: String,
    val allowPlainHttp: Boolean,
    val trusted: Boolean,
    val state: String = StorageShares.NOT_TESTED,
    /** The share's own words for the last failure, shown under the state. */
    val stateDetail: String? = null,
    val testedAtMs: Long = 0,
    /** S3: the bucket, its region, and the folder inside it the share starts at. [url] is the service's address. */
    val bucket: String = "",
    val region: String = "",
    val folder: String = "",
    /** S3: address the bucket as `<bucket>.<server>` (AWS's way) instead of `<server>/<bucket>` (most others). */
    val bucketInHost: Boolean = false,
) {
    val passwordKey: String get() = "droidtop_share_${id}_password"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("kind", kind).put("url", url).put("user", user)
        .put("allow_plain_http", allowPlainHttp).put("trusted", trusted)
        .put("state", state).put("state_detail", stateDetail).put("tested_at", testedAtMs)
        .put("bucket", bucket).put("region", region).put("folder", folder).put("bucket_in_host", bucketInHost)

    companion object {
        fun fromJson(o: JSONObject): StorageShare? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val url = o.optString("url").takeIf { it.isNotBlank() } ?: return null
            return StorageShare(
                id = id,
                name = o.optString("name").ifBlank { url },
                kind = o.optString("kind").ifBlank { StorageShares.WEBDAV },
                url = url,
                user = o.optString("user"),
                allowPlainHttp = o.optBoolean("allow_plain_http"),
                // A share whose answer is missing is not trusted: nothing is written there in plain by accident.
                trusted = o.optBoolean("trusted", false),
                state = o.optString("state").ifBlank { StorageShares.NOT_TESTED },
                stateDetail = o.optString("state_detail").takeIf { it.isNotBlank() && it != "null" },
                testedAtMs = o.optLong("tested_at"),
                bucket = o.optString("bucket"),
                region = o.optString("region"),
                folder = o.optString("folder"),
                bucketInHost = o.optBoolean("bucket_in_host"),
            )
        }
    }
}

/**
 * The person's shares (Settings > Accounts and sources > "Network and cloud storage"). Preferences and the
 * vault: never on the main thread. A share is added only by the person, with an address and sign-in they type,
 * and nothing is read from or written to it until they press Test or turn on something that uses it.
 */
object StorageShares {
    private const val PREFS = "droidtop_storage_shares"
    private const val KEY = "shares"

    const val WEBDAV = "webdav"
    const val S3 = "s3"

    const val NOT_TESTED = "not_tested"
    const val CONNECTED = "connected"
    const val UNREACHABLE = "unreachable"
    const val SIGN_IN_REFUSED = "sign_in_refused"
    const val NOT_ENCRYPTED = "not_encrypted"
    const val BAD_ADDRESS = "bad_address"
    const val REFUSED = "refused"

    /** The words for a share's state, as its row's value. */
    fun stateLabel(state: String): String = when (state) {
        CONNECTED -> "Connected"
        UNREACHABLE -> "Can't reach it"
        SIGN_IN_REFUSED -> "Sign-in refused"
        NOT_ENCRYPTED -> "Not encrypted"
        BAD_ADDRESS -> "Address not valid"
        REFUSED -> "Refused"
        else -> "Not tested"
    }

    fun all(context: Context): List<StorageShare> = read(context)

    fun get(context: Context, id: String): StorageShare? = read(context).firstOrNull { it.id == id }

    sealed interface Added {
        data class Share(val share: StorageShare) : Added
        data class Refused(val reason: String) : Added
    }

    /**
     * Why [url] can't be a share's address, or null when it can (pure, so it is unit tested). The address must be
     * https, or http when the person allowed unencrypted for this share, and must carry no user, password, query
     * or fragment: a sign-in goes in its own fields, never in an address that logs and history keep.
     */
    fun addressProblem(url: String, allowPlainHttp: Boolean): String? {
        val trimmed = url.trim()
        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
        val rest = trimmed.substringAfter("://", missingDelimiterValue = "")
        val host = rest.substringBefore('/')
        return when {
            scheme.isEmpty() || rest.isEmpty() -> "Type the whole address, starting with https://"
            scheme == "http" && !allowPlainHttp -> "That address is not encrypted (http). Use https, or allow unencrypted for this share"
            scheme != "https" && scheme != "http" -> "A WebDAV address starts with https://"
            host.isEmpty() -> "The address has no server name"
            '@' in host -> "Put the user name and password in their own fields, not in the address"
            '?' in rest || '#' in rest -> "The address can't have ? or # in it"
            else -> null
        }
    }

    /** Adds a WebDAV share; its password goes to the vault. */
    fun addWebDav(
        context: Context,
        name: String,
        url: String,
        user: String,
        password: String,
        allowPlainHttp: Boolean,
        trusted: Boolean,
    ): Added {
        addressProblem(url, allowPlainHttp)?.let { return Added.Refused(it) }
        val share = StorageShare(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            name = name.trim().ifBlank { url.trim().substringAfter("://").substringBefore('/') },
            kind = WEBDAV,
            url = url.trim(),
            user = user.trim(),
            allowPlainHttp = allowPlainHttp,
            trusted = trusted,
        )
        if (password.isNotEmpty()) CredentialStore.put(context, share.passwordKey, password)
        write(context, read(context) + share)
        return Added.Share(share)
    }

    /**
     * Adds an S3-compatible share (AWS, Backblaze B2, Wasabi, Cloudflare R2, MinIO, Garage): the service's address, a
     * bucket and the person's own access key, whose secret goes to the vault.
     */
    fun addS3(
        context: Context,
        name: String,
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: String,
        secretKey: String,
        folder: String,
        bucketInHost: Boolean,
        allowPlainHttp: Boolean,
        trusted: Boolean,
    ): Added {
        addressProblem(endpoint, allowPlainHttp)?.let { return Added.Refused(it.replace("A WebDAV address", "An S3 address")) }
        bucketProblem(bucket)?.let { return Added.Refused(it) }
        if (accessKey.isBlank() || secretKey.isBlank()) return Added.Refused("Type the access key and its secret")
        val share = StorageShare(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            name = name.trim().ifBlank { bucket.trim() },
            kind = S3,
            url = endpoint.trim(),
            user = accessKey.trim(),
            allowPlainHttp = allowPlainHttp,
            trusted = trusted,
            bucket = bucket.trim(),
            region = region.trim(),
            folder = folder.trim().trim('/'),
            bucketInHost = bucketInHost,
        )
        CredentialStore.put(context, share.passwordKey, secretKey)
        write(context, read(context) + share)
        return Added.Share(share)
    }

    /** Why [bucket] can't be an S3 bucket's name, or null when it can (S3's naming rules, loosely). Pure. */
    fun bucketProblem(bucket: String): String? {
        val b = bucket.trim()
        return when {
            b.isEmpty() -> "Type the bucket's name"
            b.length < 3 || b.length > 63 -> "A bucket's name is 3 to 63 characters long"
            !b.all { it.isLowerCase() || it.isDigit() || it == '-' || it == '.' } -> "A bucket's name has only small letters, digits, - and ."
            else -> null
        }
    }

    /** Changes [id] with [change]; a change to where it is or how it signs in clears its state, so it is tested again. */
    fun update(context: Context, id: String, change: (StorageShare) -> StorageShare) {
        write(context, read(context).map { old ->
            if (old.id != id) return@map old
            val new = change(old).copy(id = old.id)
            val reached = new.url != old.url || new.user != old.user || new.allowPlainHttp != old.allowPlainHttp ||
                new.bucket != old.bucket || new.region != old.region || new.folder != old.folder || new.bucketInHost != old.bucketInHost
            if (reached) new.copy(state = NOT_TESTED, stateDetail = null, testedAtMs = 0) else new
        })
    }

    /** A new password for [id]: kept in the vault, and the share is tested again. */
    fun setPassword(context: Context, id: String, password: String) {
        val share = get(context, id) ?: return
        CredentialStore.put(context, share.passwordKey, password)
        update(context, id) { it.copy(state = NOT_TESTED, stateDetail = null, testedAtMs = 0) }
    }

    /** Takes [id] off the list and forgets its password. Nothing on the share is touched. */
    fun remove(context: Context, id: String) {
        val share = get(context, id) ?: return
        CredentialStore.put(context, share.passwordKey, "")
        write(context, read(context).filterNot { it.id == id })
    }

    /** What droidtop's native library is given to reach [share], with its password from the vault. Never logged. */
    fun nativeArgs(context: Context, share: StorageShare): JSONObject =
        nativeArgsFor(share, CredentialStore.get(context, share.passwordKey))

    /** [nativeArgs] with the password or secret given. Pure. */
    fun nativeArgsFor(share: StorageShare, secret: String): JSONObject = when (share.kind) {
        S3 -> JSONObject()
            .put("kind", S3)
            .put("endpoint", share.url)
            .put("bucket", share.bucket)
            .put("region", share.region.ifBlank { "us-east-1" })
            .put("access_key", share.user)
            .put("secret_key", secret)
            .put("folder", share.folder)
            .put("path_style", !share.bucketInHost)
            .put("allow_plain_http", share.allowPlainHttp)
        else -> JSONObject()
            .put("kind", WEBDAV)
            .put("url", share.url)
            .put("user", share.user)
            .put("password", secret)
            .put("allow_plain_http", share.allowPlainHttp)
    }

    /**
     * Asks [share] whether it answers with its sign-in (its top folder is listed), keeps the state, and returns the
     * line to show. Network work: never on the main thread.
     */
    fun test(context: Context, share: StorageShare): String {
        val reply = AgentNative.call("remote_test", JSONObject().put("share", nativeArgs(context, share)))
        val (state, detail) = outcome(reply)
        update(context, share.id) { it.copy(state = state, stateDetail = detail, testedAtMs = System.currentTimeMillis()) }
        return when (state) {
            CONNECTED -> "Connected to ${share.name}"
            else -> "${stateLabel(state)}: ${detail ?: share.name}"
        }
    }

    /** A `remote_test` reply as a state and the share's own words. Pure. */
    fun outcome(reply: JSONObject): Pair<String, String?> = when {
        reply.has("unreachable") -> UNREACHABLE to reply.optString("unreachable")
        reply.has("error") -> when (reply.optString("code")) {
            SIGN_IN_REFUSED -> SIGN_IN_REFUSED
            NOT_ENCRYPTED -> NOT_ENCRYPTED
            BAD_ADDRESS -> BAD_ADDRESS
            else -> REFUSED
        } to reply.optString("error")
        else -> CONNECTED to null
    }

    private fun read(context: Context): List<StorageShare> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching { parse(text) }.getOrDefault(emptyList())
    }

    /** The stored list. Pure. */
    fun parse(text: String): List<StorageShare> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(StorageShare::fromJson) }
    }

    private fun write(context: Context, shares: List<StorageShare>) {
        val array = JSONArray().apply { shares.forEach { put(it.toJson()) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
