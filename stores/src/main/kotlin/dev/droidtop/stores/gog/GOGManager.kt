package dev.droidtop.stores.gog

import android.content.Context
import dev.droidtop.stores.data.GOGGame
import dev.droidtop.stores.db.dao.GOGGameDao
import dev.droidtop.stores.util.StoreFiles
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * GOG's library: the database rows, the library read from GOG's API, and
 * what an installed game's own GOG info file says (GameNative's GOGManager,
 * GPL-3.0, without its dependency injection). Not carried: GameNative's
 * cloud saves, its Wine launch command and the installer scripts it ran
 * before a game (scriptinterpreter, support commands), which belong to the
 * Wine path, and its scan of GameNative's own install folders.
 */
internal class GOGManager(
    private val gogGameDao: GOGGameDao,
) {

    private val REFRESH_BATCH_SIZE = 10

    suspend fun getGameFromDbById(gameId: String): GOGGame? {
        return withContext(Dispatchers.IO) {
            try {
                gogGameDao.getById(gameId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get GOG game by ID: $gameId")
                null
            }
        }
    }

    suspend fun updateGame(game: GOGGame) {
        withContext(Dispatchers.IO) {
            gogGameDao.update(game)
        }
    }

    suspend fun deleteAllNonInstalledGames() {
        withContext(Dispatchers.IO) {
            gogGameDao.deleteAllNonInstalledGames()
        }
    }

    suspend fun getAllGameIds(): Set<String> {
        return withContext(Dispatchers.IO) {
            try {
                gogGameDao.getAllGameIdsIncludingExcluded().toSet()
            } catch (e: Exception) {
                Timber.e(e, "Failed to get all game IDs")
                emptySet()
            }
        }
    }

    /**
     * Refresh the entire library (called manually by user)
     * Fetches all games from GOG API and updates the database
     * ! Note: If someone wants to improve this logic, I'd recommend seeing
     * ! if coroutine parallel downloading would work without being rate-limited
     */
    suspend fun refreshLibrary(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!GOGAuthManager.hasStoredCredentials(context)) {
                Timber.w("Cannot refresh library: not authenticated with GOG")
                return@withContext Result.failure(Exception("Not authenticated with GOG"))
            }

            Timber.tag("GOG").i("Refreshing GOG library from GOG API...")


            var gameIdList = GOGApiClient.getGameIds(context)

            if (!gameIdList.isSuccess) {
                val error = gameIdList.exceptionOrNull()
                Timber.e(error, "Failed to fetch GOG game IDs: ${error?.message}")
                return@withContext Result.failure(error ?: Exception("Failed to fetch GOG game IDs"))
            }

            val gameIds = gameIdList.getOrNull() ?: emptyList()
            Timber.tag("GOG").i("Successfully fetched ${gameIds.size} game IDs from GOG")

            if (gameIds.isEmpty()) {
                Timber.w("No games found in GOG library")
                return@withContext Result.success(0)
            }

            val ignoredGameId = "1801418160" // Hidden ID for GOG Galaxy that we should ignore.

            // Get existing game IDs from database to avoid re-fetching
            val existingGameIds = gogGameDao.getAllGameIdsIncludingExcluded().toMutableSet()
            existingGameIds.add(ignoredGameId)

            Timber.tag("GOG").d("Found ${existingGameIds.size} games already in database")

            // Filter to only new games that need details fetched
            val newGameIds = gameIds.filter { it !in existingGameIds }
            Timber.tag("GOG").d("${newGameIds.size} new games need details fetched")

            if (newGameIds.isEmpty()) {
                Timber.tag("GOG").d("No new games to fetch, library is up to date")
                backfillVerticalCovers()
                return@withContext Result.success(0)
            }

            var totalProcessed = 0

            Timber.tag("GOG").d("Getting Game Details for ${newGameIds.size} new GOG Games...")

            val games = mutableListOf<GOGGame>()

            // Use direct HTTP calls via GOGApiClient
            for ((index, id) in newGameIds.withIndex()) {
                try {
                    // Fetch game details using direct HTTP call
                    val result = GOGApiClient.getGameById(context, id)

                    if (result.isSuccess) {
                        val gameDetails = result.getOrNull()
                        if (gameDetails != null) {
                            Timber.tag("GOG").d("Got Game Details for ID: $id")
                            val parsedGame = parseGameObject(gameDetails)
                            if (parsedGame != null) {
                                // Only real (non-excluded) games are shown, so only fetch
                                // their portrait cover to avoid wasting GamesDB requests.
                                val game = if (parsedGame.exclude) {
                                    parsedGame
                                } else {
                                    parsedGame.copy(verticalCoverUrl = GOGApiClient.getVerticalCoverUrl(id))
                                }
                                games.add(game)
                                Timber.tag("GOG").d("Refreshed Game: ${game.title}")
                                totalProcessed++
                            }
                        }
                    } else {
                        Timber.w("GOG game ID $id not found in library after refresh")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse game details for ID: $id")
                }

                if ((index + 1) % REFRESH_BATCH_SIZE == 0 || index == newGameIds.size - 1) {
                    if (games.isNotEmpty()) {
                        gogGameDao.upsertPreservingInstallStatus(games)
                        Timber.tag("GOG").d("Batch inserted ${games.size} games (processed ${index + 1}/${newGameIds.size})")
                        games.clear()
                    }
                }
            }
            backfillVerticalCovers()
            Timber.tag("GOG").i("Successfully refreshed GOG library with $totalProcessed games")
            return@withContext Result.success(totalProcessed)
        } catch (e: Exception) {
            Timber.e(e, "Failed to refresh GOG library")
            return@withContext Result.failure(e)
        }
    }

    /**
     * Backfill portrait covers for games already in the database that predate the
     * vertical_cover_url column (or whose fetch previously failed). New games already
     * get their cover during [refreshLibrary], so this only touches existing rows.
     */
    private suspend fun backfillVerticalCovers() = withContext(Dispatchers.IO) {
        try {
            val gameIds = gogGameDao.getGameIdsMissingVerticalCover()
            if (gameIds.isEmpty()) return@withContext

            Timber.tag("GOG").d("Backfilling vertical covers for ${gameIds.size} games")
            var filled = 0
            for (id in gameIds) {
                val coverUrl = GOGApiClient.getVerticalCoverUrl(id)
                if (coverUrl.isNotEmpty()) {
                    gogGameDao.updateVerticalCoverUrl(id, coverUrl)
                    filled++
                }
            }
            Timber.tag("GOG").i("Backfilled $filled vertical covers")
        } catch (e: Exception) {
            Timber.tag("GOG").w(e, "Failed to backfill vertical covers")
        }
    }

    private fun parseGameObject(parsedGame: ParsedGogGame): GOGGame? {
        val title = parsedGame.title
        val id = parsedGame.id
        val downloadSize = parsedGame.downloadSize
        val isSecret = parsedGame.isSecret
        val isDlc = parsedGame.isDlc
        // Added Exclude so that we still store a record in the DB but we don't expose it.
        // This reduces the amount of fetching we do from the APIs and we also reduce chances of Amazon Prime duplicates etc.
        // Had to put in an extra case for some games not using isSecret but still are amazon prime duplicates...
        val exclude =
            title == "Unknown Game" || title.startsWith("product_title_") || title == "Unknown" || downloadSize == 0L || isSecret ||
                title.endsWith("Amazon Prime") || isDlc

        return GOGGame(
            id = id,
            title = title,
            exclude = exclude,
            slug = parsedGame.slug,
            imageUrl = parsedGame.imageUrl,
            iconUrl = parsedGame.iconUrl,
            backgroundUrl = parsedGame.backgroundUrl,
            description = parsedGame.description,
            releaseDate = parsedGame.releaseDate,
            developer = parsedGame.developer,
            publisher = parsedGame.publisher,
            genres = parsedGame.genres,
            languages = parsedGame.languages,
            downloadSize = parsedGame.downloadSize,
            installSize = 0L,
            isInstalled = false,
            installPath = "",
        )
    }

    fun verifyInstallation(gameId: String): Pair<Boolean, String?> {
        val game = runBlocking { getGameFromDbById(gameId) }
        val installPath = game?.installPath

        if (game == null || installPath == null || !game.isInstalled) {
            return Pair(false, "Game not marked as installed in database")
        }

        val installDir = File(installPath)
        if (!installDir.exists()) {
            return Pair(false, "Install directory not found: $installPath")
        }

        if (!installDir.isDirectory) {
            return Pair(false, "Install path is not a directory")
        }

        val contents = installDir.listFiles()
        if (contents == null || contents.isEmpty()) {
            return Pair(false, "Install directory is empty")
        }

        Timber.i("Installation verified for game $gameId at $installPath")
        return Pair(true, null)
    }

    /**
     * The primary play task of an installed game, from its own
     * `goggame-<id>.info` file: the executable (relative to the install
     * folder), the folder it runs in and its arguments. GOG keeps the info
     * file in the install root (older games) or in `game_<id>` and other
     * subfolders (newer ones).
     */
    suspend fun primaryPlayTask(gameId: String): GOGPlayTask? = withContext(Dispatchers.IO) {
        try {
            val game = getGameFromDbById(gameId) ?: return@withContext null
            if (!game.isInstalled || game.installPath.isBlank()) return@withContext null
            val installDir = File(game.installPath)
            val v2GameDir = File(installDir, "game_$gameId")
            val searchFrom = buildList {
                if (v2GameDir.isDirectory) add(v2GameDir)
                add(installDir)
                installDir.listFiles()?.filter { it.isDirectory && it.name != "saves" && it.name != "_CommonRedist" && it != v2GameDir }
                    ?.let(::addAll)
            }
            searchFrom.firstNotNullOfOrNull { dir -> playTaskFrom(dir, installDir) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read the play task of GOG game $gameId")
            null
        }
    }

    private fun findGOGInfoFile(directory: File, maxDepth: Int = 3, currentDepth: Int = 0): File? {
        if (!directory.isDirectory) return null
        directory.listFiles()?.find { it.isFile && it.name.startsWith("goggame-") && it.name.endsWith(".info") }?.let { return it }
        if (currentDepth >= maxDepth) return null
        return directory.listFiles()?.filter { it.isDirectory }?.firstNotNullOfOrNull { findGOGInfoFile(it, maxDepth, currentDepth + 1) }
    }

    private fun playTaskFrom(gameDir: File, installDir: File): GOGPlayTask? {
        val infoFile = findGOGInfoFile(gameDir) ?: return null
        val playTasks = JSONObject(infoFile.readText()).optJSONArray("playTasks") ?: return null
        for (i in 0 until playTasks.length()) {
            val task = playTasks.getJSONObject(i)
            if (!task.optBoolean("isPrimary")) continue
            val exe = StoreFiles.findCaseInsensitive(infoFile.parentFile ?: gameDir, task.getString("path"))
                ?: StoreFiles.findCaseInsensitive(gameDir, task.getString("path"))
                ?: return null
            val workingDir = task.optString("workingDir").takeIf { it.isNotBlank() }
                ?.let { StoreFiles.findCaseInsensitive(infoFile.parentFile ?: gameDir, it) }
                ?.takeIf { it.isDirectory }
                ?: exe.parentFile
                ?: installDir
            val arguments = task.optString("arguments").takeIf { it.isNotBlank() }
            return GOGPlayTask(exe, workingDir, arguments?.let(::splitArguments).orEmpty())
        }
        return null
    }

    internal companion object {
        /** A play task's arguments, split as a command line is: on spaces, keeping quoted runs whole. */
        fun splitArguments(raw: String): List<String> {
            val out = mutableListOf<String>()
            val current = StringBuilder()
            var quoted = false
            for (c in raw) {
                when {
                    c == '"' -> quoted = !quoted
                    c.isWhitespace() && !quoted -> if (current.isNotEmpty()) {
                        out += current.toString()
                        current.clear()
                    }
                    else -> current.append(c)
                }
            }
            if (current.isNotEmpty()) out += current.toString()
            return out
        }
    }
}

/** An installed GOG game's primary play task ([GOGManager.primaryPlayTask]). */
internal data class GOGPlayTask(val executable: File, val workingDir: File, val arguments: List<String>)
