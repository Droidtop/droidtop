package dev.droidtop.stores.steam

import android.content.Context
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient.CPlayer_GetOwnedGames_Request
import `in`.dragonbra.javasteam.rpc.service.Player
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import java.io.File
import kotlinx.coroutines.future.await

/**
 * Which of the account's games have ever been played, as Steam counts
 * playtime (docs/SPEC.md 7g, "Stores"; Droidtop/tracker#377): a free game is
 * the person's own once played ([SteamOwnership]). Read on each sync through
 * Steam's Player service (GetOwnedGames with free games, as GameNative's
 * SteamUnifiedFriends asked it) and kept in one small file, one app id a
 * line, so the library reads it without a connection.
 */
internal object SteamPlaytime {
    fun file(context: Context): File = File(File(context.filesDir, "steam"), "played.txt")

    /** The app ids with playtime, or null when Steam did not answer. */
    suspend fun read(steam: SteamClient, steamId64: Long): Set<Int>? {
        val player = steam.getHandler(SteamUnifiedMessages::class.java)?.createService(Player::class.java) ?: return null
        val answer = player.getOwnedGames(
            CPlayer_GetOwnedGames_Request.newBuilder().apply {
                steamid = steamId64
                includePlayedFreeGames = true
                includeFreeSub = true
            }.build(),
        ).await()
        if (answer.result != EResult.OK) return null
        return answer.body.gamesList.filter { it.playtimeForever > 0 }.mapTo(HashSet()) { it.appid }
    }

    /** Not for the main thread. */
    fun save(context: Context, played: Set<Int>) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".tmp")
        temp.writeText(played.sorted().joinToString("\n"))
        temp.renameTo(target)
    }

    /** The last answer saved; empty when there is none. Not for the main thread. */
    fun load(context: Context): Set<Int> =
        runCatching { file(context).readLines().mapNotNullTo(HashSet()) { it.trim().toIntOrNull() } }.getOrDefault(emptySet())
}
