package dev.droidtop.stores.gog

import android.content.Context
import dev.droidtop.library.stores.StoreAchievement
import dev.droidtop.library.stores.StoreAchievements
import dev.droidtop.runtime.util.runCatchingCancellable
import dev.droidtop.stores.util.StoreHttp
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * A GOG game's achievements and the signed-in account's progress, read-only, for its page (Droidtop/tracker#230; owner,
 * 2026-10-23: "read-only and for display"). GOG serves them from its Galaxy gameplay service
 * (`gameplay.gog.com/clients/<client>/users/<user>/achievements`) to the account's token for the game's own client
 * ([GOGGameClient]), the call Galaxy and the Comet client make. droidtop unlocks nothing. Kept ten minutes per game,
 * and the last answer stands when GOG cannot be reached.
 */
internal object GOGAchievements {
    private const val TTL_MS = 10L * 60 * 1000
    private const val GAMEPLAY = "https://gameplay.gog.com/clients"

    private data class Kept(val atMs: Long, val value: StoreAchievements)

    private val kept = ConcurrentHashMap<String, Kept>()

    suspend fun read(context: Context, gameId: String, nowMs: Long = System.currentTimeMillis()): Result<StoreAchievements>? {
        if (!GOGAuthManager.hasStoredCredentials(context)) return null
        kept[gameId]?.takeIf { nowMs - it.atMs < TTL_MS }?.let { return Result.success(it.value) }
        return withContext(Dispatchers.IO) {
            runCatchingCancellable {
                // A game whose build names no client of its own has no Galaxy features, achievements among them.
                val client = GOGGameClient.of(context, gameId) ?: return@runCatchingCancellable StoreAchievements(emptyList())
                val credentials = GOGGameClient.credentials(context, client)
                val request = Request.Builder()
                    .url("$GAMEPLAY/${client.id}/users/${credentials.userId}/achievements")
                    .header("Authorization", "Bearer ${credentials.accessToken}")
                    .header("Accept", "application/json")
                    .build()
                val body = StoreHttp.http.newCall(request).execute().use { response ->
                    // A game with no achievements answers 404.
                    if (response.code == 404) return@runCatchingCancellable StoreAchievements(emptyList())
                    check(response.isSuccessful) { "GOG did not give the achievements (HTTP ${response.code})" }
                    response.body?.string().orEmpty()
                }
                StoreAchievements(parse(body))
            }.onSuccess { kept[gameId] = Kept(nowMs, it) }
                .recoverCatching { failure -> kept[gameId]?.value ?: throw failure }
        }
    }

    private val OFFSET_NO_COLON = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSSSSS][.SSS]Z")

    /** GOG's time, "2016-10-21T13:21:56+0000" or with a colon in the offset, in epoch ms; null when unreadable. Pure. */
    fun parseTime(text: String): Long? =
        runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text, OFFSET_NO_COLON).toInstant().toEpochMilli() }.getOrNull()

    /**
     * GOG's answer as droidtop shows it, in GOG's order: a secret one is one GOG marks not visible; the icon is the
     * earned or the locked one; the rarity is the share of players who earned it. An entry with no name is left out.
     * Pure, for the tests.
     */
    fun parse(json: String): List<StoreAchievement> {
        val items = JSONObject(json.ifBlank { "{}" }).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.getJSONObject(index)
            val title = item.optString("name", "").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val earned = item.optString("date_unlocked", "").takeIf { it.isNotBlank() && it != "null" }
                ?.let(::parseTime)
            val icon = (if (earned != null) item.optString("image_url_unlocked", "") else item.optString("image_url_locked", ""))
                .ifBlank { item.optString("image_url_unlocked", "") }
            StoreAchievement(
                id = item.optString("achievement_key", "").ifBlank { item.optString("achievement_id", "$index") },
                title = title,
                description = item.optString("description", "").trim(),
                earnedAtMs = earned,
                hidden = !item.optBoolean("visible", true),
                iconUrl = icon.takeIf { it.isNotBlank() },
                rarityPercent = item.optDouble("rarity", Double.NaN).takeUnless { it.isNaN() }?.toFloat(),
            )
        }
    }
}
