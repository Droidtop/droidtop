package dev.droidtop.stores.epic

import android.content.Context
import dev.droidtop.library.stores.StoreAchievement
import dev.droidtop.library.stores.StoreAchievements
import dev.droidtop.runtime.util.runCatchingCancellable
import dev.droidtop.stores.data.EpicGame
import dev.droidtop.stores.util.StoreHttp
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * An Epic game's achievements and the signed-in account's progress, read-only, for its page (Droidtop/tracker#230;
 * owner, 2026-10-23: "read-only and for display"). Epic's launcher reads them from its store GraphQL service
 * (`launcher.store.epicgames.com/graphql`, the host Legendary's launcher calls use, with the launcher's own token): the
 * game's achievements by its namespace (`Achievement.productAchievementsRecordBySandbox`), then the account's records
 * for that product (`PlayerProfile.playerProfile.productAchievements`). droidtop unlocks nothing. Kept ten minutes per
 * game, and the last answer stands when Epic cannot be reached.
 */
internal object EpicAchievements {
    private const val TTL_MS = 10L * 60 * 1000
    private const val GRAPHQL = "https://launcher.store.epicgames.com/graphql"

    private const val DEFINITIONS = """query Achievement(${'$'}sandboxId: String!, ${'$'}locale: String!) {
  Achievement { productAchievementsRecordBySandbox(sandboxId: ${'$'}sandboxId, locale: ${'$'}locale) {
    productId
    achievements { achievement { name hidden unlockedDisplayName lockedDisplayName unlockedDescription lockedDescription unlockedIconLink lockedIconLink rarity { percent } } }
  } }
}"""

    private const val PLAYER = """query PlayerProfile(${'$'}epicAccountId: String!, ${'$'}productId: String!) {
  PlayerProfile { playerProfile(epicAccountId: ${'$'}epicAccountId) {
    productAchievements(productId: ${'$'}productId) {
      __typename
      ... on PlayerProductAchievementsResponseSuccess { data { playerAchievements { playerAchievement { achievementName unlocked unlockDate progress } } } }
    }
  } }
}"""

    private data class Kept(val atMs: Long, val value: StoreAchievements)

    private val kept = ConcurrentHashMap<String, Kept>()

    suspend fun read(context: Context, game: EpicGame, nowMs: Long = System.currentTimeMillis()): Result<StoreAchievements>? {
        if (!EpicAuthManager.hasStoredCredentials(context) || game.namespace.isBlank()) return null
        val key = game.namespace
        kept[key]?.takeIf { nowMs - it.atMs < TTL_MS }?.let { return Result.success(it.value) }
        return withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val credentials = EpicAuthManager.getStoredCredentials(context).getOrThrow()
                val definitions = query(credentials.accessToken, DEFINITIONS, JSONObject().put("sandboxId", game.namespace).put("locale", java.util.Locale.getDefault().toLanguageTag()))
                val (productId, list) = parseDefinitions(definitions)
                if (productId == null || list.isEmpty()) return@runCatchingCancellable StoreAchievements(emptyList())
                val player = query(credentials.accessToken, PLAYER, JSONObject().put("epicAccountId", credentials.accountId).put("productId", productId))
                StoreAchievements(merge(list, parsePlayer(player)))
            }.onSuccess { kept[key] = Kept(nowMs, it) }
                .recoverCatching { failure -> kept[key]?.value ?: throw failure }
        }
    }

    private fun query(token: String, query: String, variables: JSONObject): String {
        val body = JSONObject().put("query", query).put("variables", variables).toString()
        val request = Request.Builder().url(GRAPHQL)
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return StoreHttp.http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Epic did not give the achievements (HTTP ${response.code})" }
            response.body?.string().orEmpty()
        }
    }

    /** One achievement as Epic defines it, before the account's record is put in; [earnedIcon] is shown once earned. */
    data class Defined(val name: String, val achievement: StoreAchievement, val earnedIcon: String? = null)

    /**
     * The product id and the achievements of Epic's definitions answer, in Epic's order: a hidden one's locked name
     * and text are what it shows until earned, so the earned ones are kept. Pure, for the tests.
     */
    fun parseDefinitions(json: String): Pair<String?, List<Defined>> {
        val record = JSONObject(json.ifBlank { "{}" }).optJSONObject("data")?.optJSONObject("Achievement")
            ?.optJSONObject("productAchievementsRecordBySandbox") ?: return null to emptyList()
        val items = record.optJSONArray("achievements") ?: JSONArray()
        val list = (0 until items.length()).mapNotNull { index ->
            val a = items.getJSONObject(index).optJSONObject("achievement") ?: return@mapNotNull null
            val name = a.optString("name", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = a.optString("unlockedDisplayName", "").ifBlank { a.optString("lockedDisplayName", "") }.trim()
                .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Defined(
                name,
                StoreAchievement(
                    id = name,
                    title = title,
                    description = a.optString("unlockedDescription", "").ifBlank { a.optString("lockedDescription", "") }.trim(),
                    earnedAtMs = null,
                    hidden = a.optBoolean("hidden", false),
                    iconUrl = a.optString("lockedIconLink", "").ifBlank { a.optString("unlockedIconLink", "") }.takeIf { it.isNotBlank() },
                    rarityPercent = a.optJSONObject("rarity")?.optDouble("percent", Double.NaN)?.takeUnless { it.isNaN() }?.toFloat(),
                ),
                earnedIcon = a.optString("unlockedIconLink", "").takeIf { it.isNotBlank() },
            )
        }
        return record.optString("productId", "").takeIf { it.isNotBlank() } to list
    }

    /**
     * One of the account's records: when it was earned (null while not; 0 when Epic says earned without a date), and
     * its progress (0 to 100) when Epic counts it.
     */
    data class Record(val earnedAtMs: Long?, val progress: Float?)

    /** The account's records by achievement name. Pure, for the tests. */
    fun parsePlayer(json: String): Map<String, Record> {
        val data = JSONObject(json.ifBlank { "{}" }).optJSONObject("data")?.optJSONObject("PlayerProfile")
            ?.optJSONObject("playerProfile")?.optJSONObject("productAchievements")?.optJSONObject("data") ?: return emptyMap()
        val items = data.optJSONArray("playerAchievements") ?: return emptyMap()
        return (0 until items.length()).mapNotNull { index ->
            val p = items.getJSONObject(index).optJSONObject("playerAchievement") ?: return@mapNotNull null
            val name = p.optString("achievementName", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val earned = if (p.optBoolean("unlocked", false)) {
                runCatching { Instant.parse(p.optString("unlockDate", "")).toEpochMilli() }.getOrDefault(0L)
            } else {
                null
            }
            name to Record(earned, p.optDouble("progress", Double.NaN).takeUnless { it.isNaN() }?.toFloat())
        }.toMap()
    }

    /** The definitions with the account's records put in: an earned one's date and earned icon, a counted one's progress. Pure. */
    fun merge(defined: List<Defined>, records: Map<String, Record>): List<StoreAchievement> = defined.map { (name, achievement, earnedIcon) ->
        val record = records[name] ?: return@map achievement
        val progress = record.progress?.takeIf { record.earnedAtMs == null && it > 0f && it < 100f }
        achievement.copy(
            earnedAtMs = record.earnedAtMs,
            iconUrl = if (record.earnedAtMs != null) earnedIcon ?: achievement.iconUrl else achievement.iconUrl,
            progress = progress,
            progressMax = progress?.let { 100f },
        )
    }
}
