package dev.droidtop.stores.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.droidtop.stores.data.GOGGame

/**
 * DAO for GOG games in the Room database
 */
@Dao
interface GOGGameDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(game: GOGGame)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(games: List<GOGGame>)

    @Update
    suspend fun update(game: GOGGame)

    @Delete
    suspend fun delete(game: GOGGame)

    @Query("SELECT * FROM gog_games WHERE id = :gameId")
    suspend fun getById(gameId: String): GOGGame?

    @Query("SELECT * FROM gog_games WHERE exclude = 0 ORDER BY title ASC")
    suspend fun getAllAsList(): List<GOGGame>

    @Query("DELETE FROM gog_games WHERE is_installed = 0")
    suspend fun deleteAllNonInstalledGames()

    @Query("SELECT id FROM gog_games")
    suspend fun getAllGameIdsIncludingExcluded(): List<String>

    @Query("SELECT id FROM gog_games WHERE exclude = 0 AND vertical_cover_url = ''")
    suspend fun getGameIdsMissingVerticalCover(): List<String>

    @Query("UPDATE gog_games SET vertical_cover_url = :url WHERE id = :gameId")
    suspend fun updateVerticalCoverUrl(gameId: String, url: String)

    /**
     * Upsert GOG games while preserving install status and paths
     * This is useful when refreshing the library from GOG API
     */
    @Transaction
    suspend fun upsertPreservingInstallStatus(games: List<GOGGame>) {
        games.forEach { newGame ->
            val existingGame = getById(newGame.id)
            if (existingGame != null) {
                // Preserve installation status, path, and size from existing game
                val gameToInsert = newGame.copy(
                    isInstalled = existingGame.isInstalled,
                    installPath = existingGame.installPath,
                    installSize = existingGame.installSize,
                    installedBuildId = existingGame.installedBuildId,
                    installedVersionName = existingGame.installedVersionName,
                )
                insert(gameToInsert)
            } else {
                // New game, insert as-is
                insert(newGame)
            }
        }
    }
}
