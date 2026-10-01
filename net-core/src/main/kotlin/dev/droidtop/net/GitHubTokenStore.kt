package dev.droidtop.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the user's own GitHub token lives (docs/SPEC.md 12a "GitHub
 * token"): AES-256-GCM under a non-exportable Android Keystore key, the
 * ciphertext in a private preferences file of its own. It is deliberately
 * NOT in the shared settings preferences the settings backup writes: a
 * token is never part of a backup, and the Keystore key would not restore
 * onto another device anyway. droidtop never creates, fetches or fills one;
 * the person pastes it. If the key is lost (a restored device), [get]
 * answers null and the person pastes again.
 */
object GitHubTokenStore {
    private const val PREFS = "droidtop_github_token"
    private const val PREF_CIPHERTEXT = "token"
    private const val KEY_ALIAS = "droidtop.github.token"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    fun get(context: Context): String? {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_CIPHERTEXT, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val key = key(create = false) ?: return null
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    /** True when a token is stored (it may still fail to decrypt after a device restore). */
    fun isSet(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(PREF_CIPHERTEXT)

    /** Stores [token] (trimmed); a blank token removes it. Returns false when the Keystore refused, in which case nothing is stored. */
    fun set(context: Context, token: String): Boolean {
        val clean = token.trim()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (clean.isEmpty()) {
            prefs.edit().remove(PREF_CIPHERTEXT).apply()
            return true
        }
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true)!!)
            val encrypted = cipher.iv + cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(PREF_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
            true
        }.getOrDefault(false)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREF_CIPHERTEXT).apply()
    }

    /** The masked form shown in settings: the last four characters only. */
    fun masked(token: String): String = if (token.length <= 4) "****" else "****" + token.takeLast(4)

    /**
     * What the token can see, asked of api.github.com/user: the login it
     * belongs to and the request allowance it has. Blocking network; call
     * off the main thread. Never throws and never echoes the token.
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
                            append(", classic token scopes: ").append(scopes)
                        } else if (scopes != null) {
                            append(", classic token with no scopes (public data only)")
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

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
