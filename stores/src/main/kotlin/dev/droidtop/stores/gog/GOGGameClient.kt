package dev.droidtop.stores.gog

import android.content.Context
import dev.droidtop.stores.data.GOGCredentials
import dev.droidtop.stores.gog.api.GOGApiClient
import dev.droidtop.stores.gog.api.GOGManifestParser
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A GOG game's own Galaxy client (docs/SPEC.md 7g, "Stores"): the id and secret its newest Windows build manifest
 * names (as gogdl and Heroic read them; GOG serves them for the game, droidtop bundles none). The game's cloud saves
 * ([GOGCloudSaves]) and its achievements ([GOGAchievements]) are both reached with the account's token for that
 * client. Kept for the process once read: a build's client does not change.
 */
internal object GOGGameClient {
    data class Client(val id: String, val secret: String)

    private val known = ConcurrentHashMap<String, Client>()

    /** [gameId]'s client, read from GOG once; null when its build names none (a Gen 1 build). Network: never on the main thread. */
    suspend fun of(context: Context, gameId: String): Client? {
        known[gameId]?.let { return it }
        return withContext(Dispatchers.IO) {
            val api = GOGApiClient(context.applicationContext, GOGManifestParser())
            val build = api.latestWindowsBuild(gameId).getOrThrow() ?: return@withContext null
            val manifest = api.fetchManifest(build.link).getOrThrow()
            if (manifest.clientId.isBlank() || manifest.clientSecret.isBlank()) return@withContext null
            Client(manifest.clientId, manifest.clientSecret).also { known[gameId] = it }
        }
    }

    /** The signed-in account's token for [client] ([GOGAuthManager.getGameCredentials]). */
    suspend fun credentials(context: Context, client: Client): GOGCredentials =
        GOGAuthManager.getGameCredentials(context, client.id, client.secret).getOrThrow()
}
