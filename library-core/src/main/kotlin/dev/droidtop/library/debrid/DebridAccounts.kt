package dev.droidtop.library.debrid

import android.content.Context
import dev.droidtop.library.credentials.CredentialStore
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.pluginhost.debrid.DebridProvider
import dev.droidtop.pluginhost.debrid.DebridProviders
import dev.droidtop.runtime.prefs.PrefsFile

/**
 * The person's debrid accounts (Droidtop/tracker#638): the keys they entered under Settings > Accounts and sources, in
 * the order a torrent job asks them (TorBox, then Real-Debrid), and whether a torrent no account has yet waits for the
 * service or falls back to peers (falls back unless the person chose to wait).
 */
object DebridAccounts {
    const val KEY_WAITS = "droidtop_debrid_waits"

    private val ORDER = listOf("torbox" to CredentialStore.TORBOX_API_KEY, "realdebrid" to CredentialStore.REALDEBRID_TOKEN)

    /** The connected accounts in their order, each a provider and its key. */
    fun connected(context: Context): List<Pair<DebridProvider, String>> = ORDER.mapNotNull { (id, storeKey) ->
        val key = CredentialStore.get(context, storeKey).trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        DebridProviders.byId(id)?.let { it to key }
    }

    fun waits(context: Context): Boolean = PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getBoolean(KEY_WAITS, false)

    fun setWaits(context: Context, on: Boolean) {
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).putBoolean(KEY_WAITS, on)
    }
}
