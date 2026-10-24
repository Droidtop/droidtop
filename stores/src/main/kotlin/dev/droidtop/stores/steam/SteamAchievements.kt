package dev.droidtop.stores.steam

import android.content.Context
import dev.droidtop.library.stores.StoreAchievement
import dev.droidtop.library.stores.StoreAchievements
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.AchievementBlocks
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.SteamUserStats
import `in`.dragonbra.javasteam.types.SteamID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A Steam game's achievements and the signed-in account's progress, read-only, for its page (Droidtop/tracker#230;
 * owner, 2026-10-23: "read-only and for display"). Read over the session droidtop already holds
 * (`SteamUserStats.getUserStats`, GameNative's AchievementWatcher read), never written: droidtop sets no stat and
 * unlocks nothing. Kept ten minutes per game, so opening a page again does not ask Steam again.
 */
internal object SteamAchievements {
    private const val TTL_MS = 10L * 60 * 1000
    private const val ICONS = "https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps"

    /** Steam's answers for a game that keeps no stats (by name: the enum is generated at build time). */
    private val NO_STATS = setOf("Fail", "FileNotFound", "NoMatch")

    private data class Kept(val atMs: Long, val value: StoreAchievements)

    private val kept = ConcurrentHashMap<Int, Kept>()

    suspend fun read(context: Context, appId: Int, nowMs: Long = System.currentTimeMillis()): Result<StoreAchievements>? {
        val credentials = SteamCredentials.load(context)?.takeIf { it.steamId64 != 0L } ?: return null
        kept[appId]?.takeIf { nowMs - it.atMs < TTL_MS }?.let { return Result.success(it.value) }
        return withContext(Dispatchers.IO) {
            runCatching {
                SteamSession.use(context) { steam ->
                    SteamSession.logOn(context).getOrThrow()
                    val stats = steam.getHandler(SteamUserStats::class.java) ?: error("Steam's achievements could not be read")
                    val answer = stats.getUserStats(appId, SteamID(credentials.steamId64)).await()
                    when {
                        answer.result == EResult.OK -> StoreAchievements(
                            achievementsOf(appId, answer.getExpandedAchievements()),
                            pageUrl = "https://steamcommunity.com/profiles/${credentials.steamId64}/stats/$appId/achievements",
                        )
                        // A game with no stats on Steam has no achievements: not a failure.
                        answer.result.name in NO_STATS -> StoreAchievements(emptyList())
                        else -> error("Steam did not give the achievements (${answer.result})")
                    }
                }
            }.onSuccess { kept[appId] = Kept(nowMs, it) }
                .recoverCatching { failure -> kept[appId]?.value ?: throw failure }
        }
    }

    /**
     * Steam's expanded achievement blocks as droidtop shows them, in the game's order. An entry with no name
     * (a block the schema could not expand) is left out. Pure, for the tests.
     */
    fun achievementsOf(appId: Int, blocks: List<AchievementBlocks>): List<StoreAchievement> = blocks.mapNotNull { block ->
        val title = block.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val earned = block.unlockTimestamp.takeIf { it > 0 }?.let { it.toLong() * 1000 }
        val icon = (if (earned != null) block.icon else block.iconGray ?: block.icon)?.takeIf { it.isNotBlank() }
        StoreAchievement(
            id = block.name ?: "${block.achievementId}",
            title = title,
            description = block.description?.trim().orEmpty(),
            earnedAtMs = earned,
            hidden = block.hidden,
            iconUrl = icon?.let { if (it.startsWith("http")) it else "$ICONS/$appId/$it" },
            progress = block.progressCurrent.takeIf { block.hasProgress },
            progressMax = block.progressMax.takeIf { block.hasProgress },
        )
    }
}
