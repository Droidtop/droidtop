package dev.droidtop.runtime.windows

import android.content.Context
import dev.droidtop.runtime.windows.PrefManager
import dev.droidtop.runtime.windows.utils.StoragePaths
import java.io.File

/**
 * What GameNative kept of a Steam sign-in and where it installed Steam
 * games, read once so droidtop's own Steam can carry them over
 * (`dev.droidtop.stores.steam.SteamCarryOver`, docs/SPEC.md 7g "Stores").
 * GameNative kept the sign-in in its preferences, the refresh token
 * encrypted with its Android Keystore key; the runtime's [PrefManager]
 * reads the same preferences file with the same key. Needs the runtime up
 * ([WindowsBackbone.awaitReady]); off the main thread.
 */
object GameNativeSteamSignIn {
    data class Session(val accountName: String, val refreshToken: String, val steamId64: Long, val clientId: Long?, val cellId: Int)

    /** The sign-in GameNative kept, or null when there is none or it cannot be read. */
    fun session(): Session? = runCatching {
        val name = PrefManager.username
        val token = PrefManager.refreshToken
        if (name.isBlank() || token.isBlank()) {
            null
        } else {
            Session(name, token, PrefManager.steamUserSteamId64, PrefManager.clientId, PrefManager.cellId)
        }
    }.getOrNull()

    /**
     * The folders GameNative installed Steam games under: its internal one,
     * the external folder it was set to, and each volume it knew (its
     * SteamService.allInstallPaths), each `<base>/Steam/steamapps/common`.
     */
    fun installRoots(context: Context): List<File> {
        val bases = buildList {
            add(context.dataDir.path)
            runCatching { PrefManager.externalStoragePath }.getOrNull()?.takeIf { it.isNotBlank() }?.let(::add)
            StoragePaths.externalVolumePaths.filter { it.isNotBlank() }.forEach(::add)
        }
        return bases.distinct().map { File(File(File(it, "Steam"), "steamapps"), "common") }
    }
}
