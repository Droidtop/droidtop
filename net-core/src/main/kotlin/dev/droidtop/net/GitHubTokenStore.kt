package dev.droidtop.net

import android.content.Context
import android.util.Base64

/**
 * Where the user's own GitHub credential lives (docs/SPEC.md 12a "GitHub
 * token"): the token AES-256-GCM under a non-exportable Android Keystore
 * key, the ciphertext in a private preferences file of its own, with the
 * login, how it was obtained and the sign-in scope beside it in plain
 * (none of those is a secret). It is deliberately NOT in the shared
 * settings preferences the settings backup writes: a token is never part
 * of a backup or an export, and the Keystore key would not restore onto
 * another device anyway. The token is either pasted or granted by the
 * device-flow sign-in ([GitHubAccount]); droidtop never fills one in by
 * itself. If the key is lost (a restored device), [get] answers null and
 * the person signs in again.
 */
object GitHubTokenStore {
    private const val PREFS = "droidtop_github_token"
    private const val PREF_CIPHERTEXT = "token"
    private const val PREF_LOGIN = "login"
    private const val PREF_ORIGIN = "origin"
    private const val PREF_SCOPE = "scope"
    private const val KEY_ALIAS = "droidtop.github.token"
    private val cipher = KeystoreSecretCipher(KEY_ALIAS)

    fun get(context: Context): String? = credential(context)?.token

    /** True when a token is stored (it may still fail to decrypt after a device restore). */
    fun isSet(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(PREF_CIPHERTEXT)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(PREF_CIPHERTEXT).remove(PREF_LOGIN).remove(PREF_ORIGIN).remove(PREF_SCOPE).apply()
    }

    /** The login the stored token belongs to, when known. Preferences only (nothing is decrypted), so a settings row may read it on the main thread. */
    fun login(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_LOGIN, null)

    /** The masked form shown in settings: the last four characters only. */
    fun masked(token: String): String = if (token.length <= 4) "****" else "****" + token.takeLast(4)

    /** The [GitHubCredentialStore] over this device's Keystore, for [GitHubAccount]. */
    fun store(context: Context): GitHubCredentialStore {
        val appContext = context.applicationContext ?: context
        return object : GitHubCredentialStore {
            override fun load(): GitHubCredential? = credential(appContext)
            override fun save(credential: GitHubCredential): Boolean = write(appContext, credential)
            override fun clear() = GitHubTokenStore.clear(appContext)
            override fun hasStoredToken(): Boolean = isSet(appContext)
        }
    }

    /** The account over this device's store and the real GitHub, for the sign-in screen. */
    fun account(context: Context): GitHubAccount = GitHubAccount(store(context), ::lookupLogin)

    /** The stored credential, or null when none is stored or the Keystore key is gone. */
    fun credential(context: Context): GitHubCredential? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_CIPHERTEXT, null) ?: return null
        val token = runCatching {
            String(cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val origin = runCatching { GitHubTokenOrigin.valueOf(prefs.getString(PREF_ORIGIN, null).orEmpty()) }
            .getOrDefault(GitHubTokenOrigin.PASTED)
        return GitHubCredential(token, prefs.getString(PREF_LOGIN, null), origin, prefs.getString(PREF_SCOPE, null))
    }

    private fun write(context: Context, credential: GitHubCredential): Boolean {
        val clean = credential.token.trim()
        if (clean.isEmpty()) return false
        return runCatching {
            val encrypted = cipher.encrypt(clean.toByteArray(Charsets.UTF_8))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(PREF_LOGIN, credential.login)
                .putString(PREF_ORIGIN, credential.origin.name)
                .putString(PREF_SCOPE, credential.scope)
                .apply()
            true
        }.getOrDefault(false)
    }

    /**
     * Asks api.github.com/user whose [token] this is. Blocking network; call off the main thread.
     * Never throws and never echoes the token.
     */
    fun lookupLogin(token: String): LoginLookup = runCatching {
        val connection = GitHubAuth.open("https://api.github.com/user", token, 15_000, 30_000)
        try {
            when (val code = connection.responseCode) {
                200 -> {
                    val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    val login = org.json.JSONObject(body).optString("login")
                    if (login.isBlank()) LoginLookup.Unreachable("GitHub did not name an account") else LoginLookup.Known(login)
                }
                401 -> LoginLookup.Rejected
                else -> LoginLookup.Unreachable("GitHub answered HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }.getOrElse { LoginLookup.Unreachable(it.message ?: "no network") }

    /**
     * What the token can see, for the Test row: the login it belongs to, and the request allowance
     * it has. Blocking network; call off the main thread. Never throws and never echoes the token.
     */
    fun test(token: String): String = runCatching {
        val connection = GitHubAuth.open("https://api.github.com/user", token, 15_000, 30_000)
        try {
            when (val code = connection.responseCode) {
                200 -> {
                    val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    val login = org.json.JSONObject(body).optString("login").ifBlank { "an account" }
                    val limit = connection.getHeaderField("X-RateLimit-Limit")
                    val scopes = connection.getHeaderField("X-OAuth-Scopes")
                    buildString {
                        append("Works: signed in to GitHub as ").append(login)
                        if (limit != null) append(", ").append(limit).append(" requests per hour")
                        if (!scopes.isNullOrBlank()) {
                            append(", token scopes: ").append(scopes)
                        } else if (scopes != null) {
                            append(", no scopes (public data only)")
                        }
                    }
                }
                401 -> "GitHub rejected this token (expired, revoked or mistyped)"
                else -> "GitHub answered HTTP $code"
            }
        } finally {
            connection.disconnect()
        }
    }.getOrElse { "Couldn't reach GitHub (${it.message ?: "no network"})" }
}
