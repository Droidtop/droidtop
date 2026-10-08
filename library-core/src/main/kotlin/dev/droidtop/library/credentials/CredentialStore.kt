package dev.droidtop.library.credentials

import android.content.Context
import android.content.SharedPreferences
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.net.KeystoreSecretCipher

private class PrefsStringStore(private val prefs: SharedPreferences) : StringStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

/**
 * The one place droidtop's user-supplied credentials live: a [CredentialVault]
 * over a Keystore-backed cipher, in its own preferences file (the app opts out
 * of backups, so sealed values never leave the device). The first access also
 * moves the values older builds kept in plain launcher preferences into it
 * ([KEYS]). Opened values are cached in memory, so the Keystore is asked once
 * per value per process.
 */
object CredentialStore {
    private const val FILE = "droidtop_credentials"

    const val IGDB_CLIENT_ID = "droidtop_igdb_client_id"
    const val IGDB_CLIENT_SECRET = "droidtop_igdb_client_secret"
    const val STEAMGRIDDB_API_KEY = "droidtop_steamgriddb_apikey"
    const val THEGAMESDB_API_KEY = "droidtop_thegamesdb_apikey"
    const val SCREENSCRAPER_USER = "droidtop_screenscraper_ssid"
    const val SCREENSCRAPER_PASSWORD = "droidtop_screenscraper_sspassword"

    /** Every key the vault holds; also the set a settings backup exports when the person opts in. */
    val KEYS: Set<String> = setOf(
        IGDB_CLIENT_ID, IGDB_CLIENT_SECRET, STEAMGRIDDB_API_KEY,
        THEGAMESDB_API_KEY, SCREENSCRAPER_USER, SCREENSCRAPER_PASSWORD,
    )

    @Volatile private var vault: CredentialVault? = null

    private fun vault(context: Context): CredentialVault {
        vault?.let { return it }
        synchronized(this) {
            vault?.let { return it }
            val app = context.applicationContext
            val created = CredentialVault(PrefsStringStore(app.getSharedPreferences(FILE, Context.MODE_PRIVATE)), KeystoreSecretCipher("droidtop_credentials_v1"))
            created.migrateFrom(PrefsStringStore(app.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)), KEYS)
            vault = created
            return created
        }
    }

    fun get(context: Context, key: String): String = vault(context).get(key)

    fun put(context: Context, key: String, value: String) = vault(context).put(key, value)

    fun putAll(context: Context, values: Map<String, String>) = values.forEach { (key, value) -> put(context, key, value) }

    fun snapshot(context: Context): Map<String, String> = vault(context).snapshot(KEYS)
}
