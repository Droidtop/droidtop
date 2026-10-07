package dev.droidtop.stores.itch

import dev.droidtop.stores.data.ItchUpload
import dev.droidtop.stores.util.StoreHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One owned game, as `GET /profile/owned-keys` returns it (one entry per download key). */
internal data class ItchOwnedKey(
    val downloadKeyId: String,
    val gameId: String,
    val gameTitle: String,
    val coverUrl: String,
    val gameUrl: String,
)

/**
 * Raw HTTP calls against the itch.io API. See [ItchConstants] for which of
 * these are documented and which are reverse-engineered, and why. Lifted from
 * droidtop's fork of GameNative (app.gamenative.service.itch.api, GPL-3.0).
 */
internal object ItchApiClient {
    private const val TAG = "ItchApiClient"
    private val httpClient get() = StoreHttp.http

    private fun authedRequest(path: String, apiKey: String, query: Map<String, String> = emptyMap()): Request {
        val urlBuilder = "${ItchConstants.ITCH_API_BASE}$path".toHttpUrl().newBuilder()
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        return Request.Builder()
            .url(urlBuilder.build())
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
    }

    /** `GET /profile`: verifies the key belongs to a real account. Returns the username, or a failure. */
    suspend fun verifyKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            httpClient.newCall(authedRequest("/profile", apiKey)).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("itch.io rejected this key: HTTP ${response.code}"))
                }
                val body = response.body?.string().orEmpty()
                val user = JSONObject(body).optJSONObject("user")
                val username = user?.optString("username").takeUnless { it.isNullOrEmpty() } ?: "itch.io user"
                Result.success(username)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "verifyKey failed")
            Result.failure(e)
        }
    }

    /**
     * `GET /profile/owned-keys`: every download key (owned game) the
     * account has, paginated (itch.io defaults to 50 rows per page).
     * Stops when a page comes back with fewer than [perPage] rows.
     */
    suspend fun ownedKeys(apiKey: String, perPage: Int = 50): Result<List<ItchOwnedKey>> = withContext(Dispatchers.IO) {
        try {
            val all = mutableListOf<ItchOwnedKey>()
            var page = 1
            while (true) {
                val request = authedRequest(
                    "/profile/owned-keys",
                    apiKey,
                    mapOf("page" to page.toString(), "per_page" to perPage.toString()),
                )
                val batch = httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Fetching owned itch.io games failed: HTTP ${response.code}")
                    }
                    val body = JSONObject(response.body?.string().orEmpty())
                    val keys = body.optJSONArray("owned_keys") ?: return@use emptyList()
                    (0 until keys.length()).mapNotNull { i ->
                        val key = keys.optJSONObject(i) ?: return@mapNotNull null
                        val game = key.optJSONObject("game") ?: return@mapNotNull null
                        ItchOwnedKey(
                            downloadKeyId = key.optString("id"),
                            gameId = game.optLong("id").toString(),
                            gameTitle = game.optString("title"),
                            coverUrl = game.optString("cover_url"),
                            gameUrl = game.optString("url"),
                        )
                    }
                }
                all += batch
                if (batch.size < perPage) break
                page += 1
            }
            Result.success(all)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "ownedKeys failed")
            Result.failure(e)
        }
    }

    /**
     * `GET /games/{gameId}/uploads`: the platform builds for one owned
     * game. Undocumented endpoint -- see [ItchConstants].
     */
    suspend fun uploads(apiKey: String, gameId: String, downloadKeyId: String): Result<List<ItchUpload>> =
        withContext(Dispatchers.IO) {
            try {
                val request = authedRequest(
                    "/games/$gameId/uploads",
                    apiKey,
                    mapOf("download_key_id" to downloadKeyId),
                )
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("Fetching uploads failed: HTTP ${response.code}"))
                    }
                    val body = JSONObject(response.body?.string().orEmpty())
                    val array = body.optJSONArray("uploads") ?: org.json.JSONArray()
                    val uploads = (0 until array.length()).mapNotNull { i ->
                        val upload = array.optJSONObject(i) ?: return@mapNotNull null
                        val traits = upload.optJSONArray("traits")
                        val traitSet = (traits?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()).toSet()
                        ItchUpload(
                            id = upload.optLong("id"),
                            filename = upload.optString("filename"),
                            displayName = upload.optString("display_name").takeIf { it.isNotEmpty() },
                            sizeBytes = upload.optLong("size"),
                            platformWindows = upload.optBoolean("p_windows") || "p_windows" in traitSet,
                            platformLinux = upload.optBoolean("p_linux") || "p_linux" in traitSet,
                            platformAndroid = upload.optBoolean("p_android") || "p_android" in traitSet,
                            isDemo = "demo" in traitSet,
                        )
                    }
                    Result.success(uploads)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "uploads failed for game $gameId")
                Result.failure(e)
            }
        }

    /**
     * `GET /uploads/{uploadId}/download`: resolves the real, time-limited
     * download URL for one upload. Undocumented endpoint -- see [ItchConstants].
     */
    suspend fun resolveDownloadUrl(apiKey: String, uploadId: Long, downloadKeyId: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val request = authedRequest(
                    "/uploads/$uploadId/download",
                    apiKey,
                    mapOf("download_key_id" to downloadKeyId),
                )
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("Resolving the download failed: HTTP ${response.code}"))
                    }
                    val body = JSONObject(response.body?.string().orEmpty())
                    val url = body.optString("url").takeIf { it.isNotEmpty() }
                        ?: return@withContext Result.failure(Exception("itch.io returned no download URL for this upload"))
                    Result.success(url)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "resolveDownloadUrl failed for upload $uploadId")
                Result.failure(e)
            }
        }
}
