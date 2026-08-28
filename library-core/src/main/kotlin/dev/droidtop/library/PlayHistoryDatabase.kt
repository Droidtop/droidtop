package dev.droidtop.library

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * Real gap this closes, confirmed by reading the actual code: [LibraryEntry]
 * has carried a [LibraryEntry.lastPlayedEpochMs] field since it was first
 * designed (its own doc comment: "the library model needs to already be
 * launcher-ready -- metadata, artwork, playtime"), but nothing anywhere
 * ever wrote a real value into it -- every provider always returns it as
 * the default `null`, and `GamepadShell`'s own "Continue Playing" row
 * (`entries.filter { it.lastPlayedEpochMs != null }`) could therefore
 * never show anything, on any real device, regardless of how much a user
 * actually played. This is the real, persistent, Room-backed store behind
 * fixing that -- same established companion-singleton pattern as
 * `consoles/RomDatabase.kt`'s own real ROM-scan cache, applied to a
 * different, cross-provider concern (this isn't console-ROM-specific, so
 * it isn't a new table on that database -- every [LibraryEntry] kind
 * shares this one).
 */
@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey val id: String,
    val lastPlayedEpochMs: Long,
    val playCount: Int,
)

@Dao
interface PlayHistoryDao {
    @Query("SELECT * FROM play_history WHERE id IN (:ids)")
    suspend fun getAll(ids: Collection<String>): List<PlayHistoryEntity>

    // Split into ensure-row-exists + always-increment rather than a single
    // @Insert(OnConflictStrategy.REPLACE): a plain upsert-by-replace would
    // need the caller to already know the current playCount to increment
    // it correctly, which defeats the point of a persisted counter. The
    // INSERT OR IGNORE either creates a fresh 0-count row or does nothing
    // (existing row untouched); the UPDATE that follows always applies,
    // taking either that fresh 0 or whatever count was already there to
    // its real, correct next value.
    @Query("INSERT OR IGNORE INTO play_history (id, lastPlayedEpochMs, playCount) VALUES (:id, :epochMs, 0)")
    suspend fun ensureRow(id: String, epochMs: Long)

    @Query("UPDATE play_history SET lastPlayedEpochMs = :epochMs, playCount = playCount + 1 WHERE id = :id")
    suspend fun bumpPlay(id: String, epochMs: Long)

    @Transaction
    suspend fun recordPlay(id: String, epochMs: Long) {
        ensureRow(id, epochMs)
        bumpPlay(id, epochMs)
    }
}

@Database(entities = [PlayHistoryEntity::class], version = 1, exportSchema = false)
abstract class PlayHistoryDatabase : RoomDatabase() {
    abstract fun playHistoryDao(): PlayHistoryDao

    companion object {
        @Volatile private var instance: PlayHistoryDatabase? = null

        fun get(context: Context): PlayHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PlayHistoryDatabase::class.java,
                    "droidtop-play-history.db",
                ).build().also { instance = it }
            }
    }
}

class RoomPlayHistoryStore(context: Context) : PlayHistoryStore {
    private val dao = PlayHistoryDatabase.get(context).playHistoryDao()

    override suspend fun recordPlay(id: String, epochMs: Long) = dao.recordPlay(id, epochMs)

    override suspend fun getAll(ids: Collection<String>): Map<String, PlayHistoryRecord> {
        if (ids.isEmpty()) return emptyMap()
        return dao.getAll(ids).associate { it.id to PlayHistoryRecord(it.lastPlayedEpochMs, it.playCount) }
    }
}
