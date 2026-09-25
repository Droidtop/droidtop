package dev.droidtop.library

import android.content.Context
import androidx.room.ColumnInfo
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

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun put(row: PlayHistoryEntity)

    @Query("DELETE FROM play_history WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * The missing game's history becomes the replacing game's: the counts
     * add and the later last-played wins, because they are one game at
     * two paths (see [PlayHistoryStore.moveTo]). A row is written for
     * [toId] even when it had none, which is the normal case -- a folder
     * that has just been detected has never been launched from.
     */
    @Transaction
    suspend fun moveTo(fromId: String, toId: String) {
        val rows = getAll(listOf(fromId, toId)).associateBy { it.id }
        val from = rows[fromId] ?: return
        val to = rows[toId]
        put(
            PlayHistoryEntity(
                id = toId,
                lastPlayedEpochMs = maxOf(from.lastPlayedEpochMs, to?.lastPlayedEpochMs ?: 0L),
                playCount = from.playCount + (to?.playCount ?: 0),
            ),
        )
        delete(fromId)
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

    /** See [FavoritesStore.moveTo]. A game that was not a favourite does not become one. */
    @Transaction
    suspend fun moveTo(fromId: String, toId: String) {
        if (getAll(listOf(fromId)).isEmpty()) return
        add(toId)
        remove(fromId)
    }
}

/** One row per entry the user has said something about; see [GameLinks]. */
@Entity(tableName = "game_links")
data class GameLinkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "game_name") val gameName: String? = null,
    @ColumnInfo(name = "f95_thread") val f95Thread: Long? = null,
)

/** What F95Checker's index last said about one thread; see [F95ThreadCheck]. */
@Entity(tableName = "f95_threads")
data class F95ThreadEntity(
    @PrimaryKey @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "last_changed") val lastChanged: Long,
    val version: String?,
    @ColumnInfo(name = "checked_at") val checkedAt: Long,
    val gone: Boolean,
) {
    fun toCheck() = F95ThreadCheck(threadId, lastChanged, version, checkedAt, gone)
}

@Dao
interface GameLinksDao {
    @Query("SELECT * FROM game_links WHERE id IN (:ids)")
    suspend fun getLinks(ids: Collection<String>): List<GameLinkEntity>

    @Query("SELECT * FROM f95_threads WHERE thread_id IN (:threads)")
    suspend fun getThreads(threads: Collection<Long>): List<F95ThreadEntity>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun putLink(row: GameLinkEntity)

    @Query("DELETE FROM game_links WHERE id = :id")
    suspend fun deleteLink(id: String)

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun putThread(row: F95ThreadEntity)

    @Query("SELECT DISTINCT f95_thread FROM game_links WHERE f95_thread IS NOT NULL")
    suspend fun linkedThreadIds(): List<Long>

    @Query("SELECT id FROM game_links WHERE f95_thread = :thread")
    suspend fun idsLinkedTo(thread: Long): List<String>

    /** A thread nothing links any more is not worth an answer kept. */
    @Query("DELETE FROM f95_threads WHERE thread_id NOT IN (SELECT f95_thread FROM game_links WHERE f95_thread IS NOT NULL)")
    suspend fun pruneThreads()

    @Transaction
    suspend fun setGameName(ids: Collection<String>, name: String) {
        val existing = getLinks(ids).associateBy { it.id }
        for (id in ids) putOrDrop((existing[id] ?: GameLinkEntity(id)).copy(gameName = name))
    }

    @Transaction
    suspend fun setF95Thread(ids: Collection<String>, thread: Long?) {
        val existing = getLinks(ids).associateBy { it.id }
        for (id in ids) putOrDrop((existing[id] ?: GameLinkEntity(id)).copy(f95Thread = thread))
        pruneThreads()
    }

    /** See [GameLinksStore.moveTo]: only into an empty place. */
    @Transaction
    suspend fun moveTo(fromId: String, toId: String) {
        val rows = getLinks(listOf(fromId, toId)).associateBy { it.id }
        val from = rows[fromId] ?: return
        val to = rows[toId] ?: GameLinkEntity(toId)
        putOrDrop(to.copy(gameName = to.gameName ?: from.gameName, f95Thread = to.f95Thread ?: from.f95Thread))
        deleteLink(fromId)
    }
}

/** Writes [row], or drops it when it no longer says anything. */
private suspend fun GameLinksDao.putOrDrop(row: GameLinkEntity) {
    if (row.gameName == null && row.f95Thread == null) deleteLink(row.id) else putLink(row)
}

@Database(
    entities = [PlayHistoryEntity::class, FavoriteEntity::class, GameLinkEntity::class, F95ThreadEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class PlayHistoryDatabase : RoomDatabase() {
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun gameLinksDao(): GameLinksDao

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

        // The user's links (docs/SPEC.md 7g, 7m) and the update source's
        // answers: two new tables, nothing existing touched, for the same
        // reason as MIGRATION_1_2.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `game_links` (`id` TEXT NOT NULL, `game_name` TEXT, " +
                        "`f95_thread` INTEGER, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `f95_threads` (`thread_id` INTEGER NOT NULL, " +
                        "`last_changed` INTEGER NOT NULL, `version` TEXT, `checked_at` INTEGER NOT NULL, " +
                        "`gone` INTEGER NOT NULL, PRIMARY KEY(`thread_id`))",
                )
            }
        }

        fun get(context: Context): PlayHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PlayHistoryDatabase::class.java,
                    "droidtop-play-history.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}

class RoomGameLinksStore(context: Context) : GameLinksStore {
    private val dao = PlayHistoryDatabase.get(context).gameLinksDao()

    override suspend fun getAll(ids: Collection<String>): Map<String, GameLinks> {
        if (ids.isEmpty()) return emptyMap()
        val links = ids.chunked(MAX_IDS_PER_QUERY).flatMap { dao.getLinks(it) }
        if (links.isEmpty()) return emptyMap()
        val threads = links.mapNotNull { it.f95Thread }.distinct()
        val checks = threads.chunked(MAX_IDS_PER_QUERY).flatMap { dao.getThreads(it) }.associateBy { it.threadId }
        return links.associate { row ->
            row.id to GameLinks(row.gameName, row.f95Thread, row.f95Thread?.let { checks[it]?.toCheck() })
        }
    }

    override suspend fun setGameName(ids: Collection<String>, name: String) = dao.setGameName(ids, name)

    override suspend fun setF95Thread(ids: Collection<String>, thread: Long?) = dao.setF95Thread(ids, thread)

    override suspend fun moveTo(fromId: String, toId: String) = dao.moveTo(fromId, toId)

    override suspend fun linkedThreads(): Map<Long, F95ThreadCheck?> {
        val threads = dao.linkedThreadIds()
        val checks = threads.chunked(MAX_IDS_PER_QUERY).flatMap { dao.getThreads(it) }.associateBy { it.threadId }
        return threads.associateWith { checks[it]?.toCheck() }
    }

    override suspend fun idsLinkedTo(thread: Long): List<String> = dao.idsLinkedTo(thread)

    override suspend fun saveCheck(check: F95ThreadCheck) = dao.putThread(
        F95ThreadEntity(check.thread, check.lastChanged, check.version, check.checkedAtEpochMs, check.gone),
    )
}

class RoomFavoritesStore(context: Context) : FavoritesStore {
    private val dao = PlayHistoryDatabase.get(context).favoritesDao()

    override suspend fun setFavorite(id: String, favorite: Boolean) {
        if (favorite) dao.add(id) else dao.remove(id)
    }

    override suspend fun getAll(ids: Collection<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        return ids.chunked(MAX_IDS_PER_QUERY).flatMapTo(HashSet()) { dao.getAll(it) }
    }

    override suspend fun moveTo(fromId: String, toId: String) = dao.moveTo(fromId, toId)
}

class RoomPlayHistoryStore(context: Context) : PlayHistoryStore {
    private val dao = PlayHistoryDatabase.get(context).playHistoryDao()

    override suspend fun recordPlay(id: String, epochMs: Long) = dao.recordPlay(id, epochMs)

    override suspend fun getAll(ids: Collection<String>): Map<String, PlayHistoryRecord> {
        if (ids.isEmpty()) return emptyMap()
        return ids.chunked(MAX_IDS_PER_QUERY)
            .flatMap { dao.getAll(it) }
            .associate { it.id to PlayHistoryRecord(it.lastPlayedEpochMs, it.playCount) }
    }

    override suspend fun moveTo(fromId: String, toId: String) = dao.moveTo(fromId, toId)
}

/**
 * The most ids one `IN (:ids)` query binds. Room gives each id its own
 * variable, and SQLite before 3.32 (Android 11 and older, the Android 9
 * rig included) refuses a statement with more than 999; the library asks
 * about every game it holds at once when it first loads.
 */
private const val MAX_IDS_PER_QUERY = 500
