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
 * Steam's own answer to "which games does this account own" (docs/SPEC.md 7g,
 * "Stores"; Droidtop/tracker#377): the Player service's GetOwnedGames, asked
 * the way a profile counts games, with free games only once played
 * (`include_played_free_games`) and without the free sub
 * (`include_free_sub` off). A game it lists is the person's own whatever its
 * licence says ([SteamOwnership.statusOf]); [Answer.played] is the listed
 * games with playtime. Read on each sync (the request GameNative's
 * SteamUnifiedFriends made) and kept in one small file, a line per game
 * ("appid" or "appid played"), so the library reads it without a connection.
 */
internal object SteamOwnedGames {
    data class Answer(val listed: Set<Int>, val played: Set<Int>) {
        companion object {
            val NONE = Answer(emptySet(), emptySet())
        }
    }

    fun file(context: Context): File = File(File(context.filesDir, "steam"), "owned-games.txt")

    /** Steam's answer, or null when it gave none. */
    suspend fun read(steam: SteamClient, steamId64: Long): Answer? {
        val player = steam.getHandler(SteamUnifiedMessages::class.java)?.createService(Player::class.java) ?: return null
        val answer = player.getOwnedGames(
            CPlayer_GetOwnedGames_Request.newBuilder().apply {
                steamid = steamId64
                includePlayedFreeGames = true
                includeFreeSub = false
            }.build(),
        ).await()
        if (answer.result != EResult.OK) return null
        val games = answer.body.gamesList
        return Answer(games.mapTo(HashSet()) { it.appid }, games.filter { it.playtimeForever > 0 }.mapTo(HashSet()) { it.appid })
    }

    /** Not for the main thread. */
    fun save(context: Context, answer: Answer) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".tmp")
        temp.writeText(answer.listed.sorted().joinToString("\n") { if (it in answer.played) "$it played" else "$it" })
        temp.renameTo(target)
    }

    /** The last answer saved; [Answer.NONE] when there is none. Not for the main thread. */
    fun load(context: Context): Answer = runCatching {
        val listed = HashSet<Int>()
        val played = HashSet<Int>()
        for (line in file(context).readLines()) {
            val parts = line.trim().split(' ')
            val id = parts[0].toIntOrNull() ?: continue
            listed += id
            if (parts.getOrNull(1) == "played") played += id
        }
        Answer(listed, played)
    }.getOrDefault(Answer.NONE)
}
