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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

/** One row per favourite non-ROM entry; absence is "not a favourite". See [FavoritesStore]. */
@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val id: String)

@Dao
interface FavoritesDao {
    @Query("SELECT id FROM favorites WHERE id IN (:ids)")
    suspend fun getAll(ids: Collection<String>): List<String>

    @Query("INSERT OR IGNORE INTO favorites (id) VALUES (:id)")
    suspend fun add(id: String)

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun remove(id: String)
}

@Database(entities = [PlayHistoryEntity::class, FavoriteEntity::class], version = 2, exportSchema = false)
abstract class PlayHistoryDatabase : RoomDatabase() {
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun favoritesDao(): FavoritesDao

    companion object {
        @Volatile private var instance: PlayHistoryDatabase? = null

        // A second table beside play history, not a rebuild: destroying
        // and recreating this database would throw away every real play
        // count on the device for the sake of an empty new table.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `favorites` (`id` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        fun get(context: Context): PlayHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PlayHistoryDatabase::class.java,
                    "droidtop-play-history.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}

class RoomFavoritesStore(context: Context) : FavoritesStore {
    private val dao = PlayHistoryDatabase.get(context).favoritesDao()

    override suspend fun setFavorite(id: String, favorite: Boolean) {
        if (favorite) dao.add(id) else dao.remove(id)
    }

    override suspend fun getAll(ids: Collection<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        return dao.getAll(ids).toSet()
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
