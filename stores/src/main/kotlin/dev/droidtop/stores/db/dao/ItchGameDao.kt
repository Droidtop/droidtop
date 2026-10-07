package dev.droidtop.stores.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.droidtop.stores.data.ItchGame

/** DAO for itch.io games in the Room database, mirroring [GOGGameDao]'s shape. */
@Dao
interface ItchGameDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(game: ItchGame)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(games: List<ItchGame>)

    @Update
    suspend fun update(game: ItchGame)

    @Delete
    suspend fun delete(game: ItchGame)

    @Query("SELECT * FROM itch_games WHERE id = :gameId")
    suspend fun getById(gameId: String): ItchGame?

    @Query("SELECT * FROM itch_games WHERE exclude = 0 ORDER BY title ASC")
    suspend fun getAllAsList(): List<ItchGame>

    @Query("DELETE FROM itch_games WHERE is_installed = 0")
    suspend fun deleteAllNonInstalledGames()

    /**
     * Upsert itch games while preserving install status and paths -- the
     * same reason [GOGGameDao.upsertPreservingInstallStatus] exists: a
     * library refresh must not forget what is already on disk.
     */
    @Transaction
    suspend fun upsertPreservingInstallStatus(games: List<ItchGame>) {
        games.forEach { newGame ->
            val existing = getById(newGame.id)
            if (existing != null) {
                insert(
                    newGame.copy(
                        isInstalled = existing.isInstalled,
                        installPath = existing.installPath,
                        installedUploadId = existing.installedUploadId,
                        sizeBytes = if (existing.isInstalled) existing.sizeBytes else newGame.sizeBytes,
                    ),
                )
            } else {
                insert(newGame)
            }
        }
    }
}
