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
 * the order a torrent job asks them (TorBox, then Real-Debrid), and whether a torrent no account has yet waits for the
 * service or falls back to peers (falls back unless the person chose to wait).
 */
object DebridAccounts {
    const val KEY_WAITS = "droidtop_debrid_waits"

    private val ORDER = listOf("torbox" to CredentialStore.TORBOX_API_KEY, "realdebrid" to CredentialStore.REALDEBRID_TOKEN)

    /** The connected accounts in their order, each a provider and its key. May refresh a sign-in's token: off the main thread. */
    fun connected(context: Context): List<Pair<DebridProvider, String>> = ORDER.mapNotNull { (id, storeKey) ->
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
    }

    /** Drops a device sign-in whose token is no longer the account's (a token pasted over it, or cleared). */
    fun forgetStaleSignIn(context: Context) {
        val saved = RealDebridSignIn.Credential.fromJson(CredentialStore.get(context, CredentialStore.REALDEBRID_SIGNIN)) ?: return
        if (saved.accessToken != CredentialStore.get(context, CredentialStore.REALDEBRID_TOKEN).trim()) {
            CredentialStore.put(context, CredentialStore.REALDEBRID_SIGNIN, "")
        }
    }

    fun waits(context: Context): Boolean = PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getBoolean(KEY_WAITS, false)

    fun setWaits(context: Context, on: Boolean) {
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).putBoolean(KEY_WAITS, on)
    }
}
