package dev.droidtop.stores.steam

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * The Steam sign-in this device holds: the account name and the long-lived
 * refresh token Steam hands back after a QR or password sign-in, kept in the
 * app's own files like every other store's sign-in (the Epic and GOG
 * credentials files). Never a password. GameNative kept the same values in
 * its preferences ("user_name", "refresh_token_enc"); an existing sign-in
 * comes across through [SteamCarryOver].
 */
@Serializable
internal data class SteamCredentials(
    val accountName: String,
    val refreshToken: String,
    /** The account's 64-bit Steam id, once a log-on has said it; 0 before. */
    val steamId64: Long = 0L,
    /** The client id of the sign-in session, which Steam Cloud calls name; null when unknown. */
    val clientId: Long? = null,
    /** The Steam cell (content region) the last log-on reported; 0 lets Steam pick. */
    val cellId: Int = 0,
) {
    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun file(context: Context): File = File(File(context.filesDir, "steam"), "credentials.json")

        fun exists(context: Context): Boolean = file(context).isFile

        /** The stored sign-in, or null when there is none or it cannot be read. Reads a file: off the main thread. */
        fun load(context: Context): SteamCredentials? = runCatching {
            val file = file(context)
            if (!file.isFile) null else JSON.decodeFromString(serializer(), file.readText())
        }.onFailure { Timber.tag("SteamCredentials").w(it, "Could not read the Steam sign-in") }
            .getOrNull()
            ?.takeIf { it.accountName.isNotBlank() && it.refreshToken.isNotBlank() }

        /** Writes [credentials] in place of what was there (a temporary file renamed over it). */
        fun save(context: Context, credentials: SteamCredentials) {
            val file = file(context)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(JSON.encodeToString(serializer(), credentials))
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }

        fun clear(context: Context) {
            file(context).delete()
        }
    }
}
