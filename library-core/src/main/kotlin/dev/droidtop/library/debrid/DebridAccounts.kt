package dev.droidtop.library.debrid

import android.content.Context
import dev.droidtop.library.credentials.CredentialStore
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.pluginhost.debrid.DebridProvider
import dev.droidtop.pluginhost.debrid.DebridProviders
import dev.droidtop.pluginhost.debrid.RealDebridSignIn
import dev.droidtop.runtime.prefs.PrefsFile

/**
 * The person's debrid accounts (Droidtop/tracker#638): the keys they entered under Settings > Accounts and sources, in
 * the person's order (TorBox, then Real-Debrid, until moved), which settles a tie between accounts a job costs the
 * same ([dev.droidtop.pluginhost.debrid.DebridChoice]); whether each is used for downloads; spread mode; and whether a
 * torrent no account has yet waits for the service or falls back to peers (falls back unless the person chose to wait).
 */
object DebridAccounts {
    const val KEY_WAITS = "droidtop_debrid_waits"
    const val KEY_ORDER = "droidtop_debrid_order"
    const val KEY_SPREAD = "droidtop_debrid_spread"

    private val ORDER = listOf("torbox" to CredentialStore.TORBOX_API_KEY, "realdebrid" to CredentialStore.REALDEBRID_TOKEN)

    /** The services' ids in the person's order: the stored order, then any service it does not name yet. Pure, for the tests. */
    fun ordered(stored: String?): List<String> {
        val known = ORDER.map { it.first }
        val named = stored.orEmpty().split(',').map { it.trim() }.filter { it in known }.distinct()
        return named + known.filter { it !in named }
    }

    fun order(context: Context): List<String> = ordered(PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getString(KEY_ORDER, null))

    /** Moves [id] one place earlier ([earlier]) or later in the person's order. Pure, for the tests. */
    fun moved(order: List<String>, id: String, earlier: Boolean): List<String> {
        val at = order.indexOf(id)
        val to = if (earlier) at - 1 else at + 1
        if (at < 0 || to !in order.indices) return order
        return order.toMutableList().apply { add(to, removeAt(at)) }
    }

    fun move(context: Context, id: String, earlier: Boolean) {
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).putString(KEY_ORDER, moved(order(context), id, earlier).joinToString(","))
    }

    private fun usedKey(id: String) = "droidtop_debrid_used_$id"

    /** Whether the account is used for downloads: on until the person turns it off, so it stays signed in but unused. */
    fun used(context: Context, id: String): Boolean = PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getBoolean(usedKey(id), true)

    fun setUsed(context: Context, id: String, on: Boolean) {
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).putBoolean(usedKey(id), on)
    }

    /** Spread mode: a tie between slot-limited accounts goes to the one with the fewest of droidtop's fetches running. Off by default. */
    fun spread(context: Context): Boolean = PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getBoolean(KEY_SPREAD, false)

    fun setSpread(context: Context, on: Boolean) {
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).putBoolean(KEY_SPREAD, on)
    }

    /** The connected accounts used for downloads, in the person's order, each a provider and its key. May refresh a sign-in's token: off the main thread. */
    fun connected(context: Context): List<Pair<DebridProvider, String>> = order(context).mapNotNull { id ->
        if (!used(context, id)) return@mapNotNull null
        val storeKey = ORDER.first { it.first == id }.second
        val key = (if (id == "realdebrid") realDebridToken(context) else CredentialStore.get(context, storeKey)).trim()
        if (key.isEmpty()) return@mapNotNull null
        DebridProviders.byId(id)?.let { it to key }
    }

    /**
     * The Real-Debrid token to use: a pasted one as it is, or a device sign-in's, refreshed first when it ends within
     * [RealDebridSignIn.REFRESH_BEFORE_MS]. A refresh Real-Debrid refuses leaves the old token, which then fails its
     * check and says to sign in again; one that could not be asked (offline) is tried on the next use.
     */
    fun realDebridToken(context: Context, signIn: RealDebridSignIn = RealDebridSignIn(), now: () -> Long = System::currentTimeMillis): String {
        val token = CredentialStore.get(context, CredentialStore.REALDEBRID_TOKEN)
        val saved = RealDebridSignIn.Credential.fromJson(CredentialStore.get(context, CredentialStore.REALDEBRID_SIGNIN)) ?: return token
        // A token pasted over a sign-in is the person's choice: the sign-in no longer applies to it.
        if (saved.accessToken != token.trim()) return token
        if (saved.expiresAtMs - now() > RealDebridSignIn.REFRESH_BEFORE_MS) return token
        synchronized(this) {
            val again = RealDebridSignIn.Credential.fromJson(CredentialStore.get(context, CredentialStore.REALDEBRID_SIGNIN)) ?: return token
            if (again.accessToken != saved.accessToken) return again.accessToken // refreshed meanwhile
            val fresh = runCatching { signIn.refresh(again, now) }.getOrNull() ?: return token
            saveRealDebrid(context, fresh)
            return fresh.accessToken
        }
    }

    /** Keeps a device sign-in: its token becomes the account's, replacing any pasted one. */
    fun saveRealDebrid(context: Context, credential: RealDebridSignIn.Credential) {
        CredentialStore.put(context, CredentialStore.REALDEBRID_SIGNIN, credential.toJson())
        CredentialStore.put(context, CredentialStore.REALDEBRID_TOKEN, credential.accessToken)
        clearRefusal(context, "realdebrid")
    }

    /** Drops a device sign-in whose token is no longer the account's (a token pasted over it, or cleared). */
    fun forgetStaleSignIn(context: Context) {
        val saved = RealDebridSignIn.Credential.fromJson(CredentialStore.get(context, CredentialStore.REALDEBRID_SIGNIN)) ?: return
        if (saved.accessToken != CredentialStore.get(context, CredentialStore.REALDEBRID_TOKEN).trim()) {
            CredentialStore.put(context, CredentialStore.REALDEBRID_SIGNIN, "")
        }
    }

    /** The row's word for an account whose key a service refused (TorBox rotates a key after an abuse warning). */
    const val SIGN_IN_AGAIN = "Sign in again"

    private fun refusedKey(id: String) = "droidtop_debrid_refused_$id"

    /** Notes that the service refused [id]'s key, with its sentence; the account's row says so until a new key is set. */
    fun refused(context: Context, id: String, message: String) {
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).putString(refusedKey(id), message)
    }

    /** The sentence of the last refusal of [id]'s key, or null. */
    fun refusal(context: Context, id: String): String? =
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getString(refusedKey(id), "")?.takeIf { it.isNotBlank() }

    /** A new key or sign-in for [id]: the refusal no longer applies. */
    fun clearRefusal(context: Context, id: String) {
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).putString(refusedKey(id), "")
    }

    fun waits(context: Context): Boolean = PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getBoolean(KEY_WAITS, false)

    fun setWaits(context: Context, on: Boolean) {
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).putBoolean(KEY_WAITS, on)
    }
}
