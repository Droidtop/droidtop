package dev.droidtop.net.peer

import android.content.Context
import android.util.Base64
import dev.droidtop.net.KeystoreSecretCipher

/**
 * This device's key for droidtop-agent (docs/SPEC.md 7o "Computers"): an
 * Ed25519 key made on the device the first time a computer is paired, never
 * shipped and never exported. Its 32-byte seed is kept sealed with the one
 * Keystore mechanism droidtop uses for secrets at rest ([KeystoreSecretCipher]),
 * in a private preferences file of its own that no settings backup carries.
 * The public half, the device id the computers pin, is kept beside it in plain.
 *
 * If the Keystore key is gone (a restored device), the seed cannot be opened
 * and a new key is made; the computers paired with the old one have to be
 * paired again, which [Computers] says when it finds none of them answer.
 */
object DeviceIdentity {
    private const val PREFS = "droidtop_agent_identity"
    private const val PREF_SEED = "seed"
    private const val PREF_ID = "id"
    private const val KEY_ALIAS = "droidtop.agent.identity"
    private val cipher = KeystoreSecretCipher(KEY_ALIAS)

    /** The seed in hex, made and sealed on first use. Disk and Keystore work: never on the main thread. */
    @Synchronized
    fun seed(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(PREF_SEED, null)?.let { stored ->
            runCatching { String(cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8) }.getOrNull()?.let { return it }
        }
        val made = AgentNative.call("identity_new")
        val seed = made.optString("seed").takeIf { it.length == 64 } ?: return null
        val sealed = runCatching { cipher.encrypt(seed.toByteArray(Charsets.UTF_8)) }.getOrNull() ?: return null
        prefs.edit()
            .putString(PREF_SEED, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .putString(PREF_ID, made.optString("id"))
            .apply()
        return seed
    }

    /** This device's id (its public key in hex), when one was made. Preferences only. */
    fun id(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_ID, null)
}
