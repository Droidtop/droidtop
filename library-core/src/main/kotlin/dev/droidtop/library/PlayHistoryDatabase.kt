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
        // The launch log beside the count (Droidtop/tracker#611): when in the day each game is started, kept
        // PlayStarts.KEEP_MS, so Home can put the games of this hour first. On the device only.
        addStart(PlayStartEntity(gameId = id, startedAt = epochMs))
        pruneStarts(epochMs - PlayStarts.KEEP_MS)
    }

    @androidx.room.Insert
    suspend fun addStart(row: PlayStartEntity)

    @Query("DELETE FROM play_starts WHERE started_at < :beforeMs")
    suspend fun pruneStarts(beforeMs: Long)

    @Query("SELECT * FROM play_starts WHERE started_at >= :sinceMs")
    suspend fun startsSince(sinceMs: Long): List<PlayStartEntity>

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
        moveStarts(fromId, toId)
    }

    @Query("UPDATE play_starts SET game_id = :toId WHERE game_id = :fromId")
    suspend fun moveStarts(fromId: String, toId: String)
}

/** One start of a game, for the launch log (Droidtop/tracker#611): which game and when. */
@Entity(tableName = "play_starts", indices = [androidx.room.Index("started_at")])
data class PlayStartEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    @ColumnInfo(name = "game_id") val gameId: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
)

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

/** One row per entry the user has named as part of another game, or linked to a store's row of it; see [GameLinks]. */
@Entity(tableName = "game_links")
data class GameLinkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "game_name") val gameName: String? = null,
    @ColumnInfo(name = "same_as") val sameAs: String? = null,
)

/** One entry's link to one source's record of its game; see [SourceLink]. */
@Entity(tableName = "source_links", primaryKeys = ["id", "source"])
data class SourceLinkEntity(
    val id: String,
    val source: String,
    @ColumnInfo(name = "external_id") val externalId: String,
)

/** What a source last said about one of its records; see [SourceAnswer]. */
@Entity(tableName = "source_answers", primaryKeys = ["source", "external_id"])
data class SourceAnswerEntity(
    val source: String,
    @ColumnInfo(name = "external_id") val externalId: String,
    val version: String?,
    val url: String?,
    @ColumnInfo(name = "checked_at") val checkedAt: Long,
    val gone: Boolean,
) {
    val key: SourceKey get() = SourceKey(source, externalId)
    fun toAnswer() = SourceAnswer(version, checkedAt, gone, url)
}

@Dao
interface GameLinksDao {
    @Query("SELECT * FROM game_links WHERE id IN (:ids)")
    suspend fun getLinks(ids: Collection<String>): List<GameLinkEntity>

    @Query("SELECT * FROM source_links WHERE id IN (:ids)")
    suspend fun getSourceLinks(ids: Collection<String>): List<SourceLinkEntity>

    /** The store ids a scrape kept for [ids] (`game_ids`, docs/SPEC.md 7o "Saves"): the ones a store's row is keyed by. */
    @Query("SELECT * FROM game_ids WHERE game_id IN (:ids) AND kind IN ('steam', 'gog')")
    suspend fun getStoreIds(ids: Collection<String>): List<dev.droidtop.library.saves.GameIdEntity>

    @Query("SELECT * FROM source_answers WHERE source = :source AND external_id IN (:externalIds)")
    suspend fun getAnswers(source: String, externalIds: Collection<String>): List<SourceAnswerEntity>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun putLink(row: GameLinkEntity)

    @Query("DELETE FROM game_links WHERE id = :id")
    suspend fun deleteLink(id: String)

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun putSourceLink(row: SourceLinkEntity)

    @Query("DELETE FROM source_links WHERE id = :id AND source = :source")
    suspend fun deleteSourceLink(id: String, source: String)

    @Query("DELETE FROM source_links WHERE id = :id")
    suspend fun deleteSourceLinks(id: String)

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun putAnswer(row: SourceAnswerEntity)

    @Query("SELECT DISTINCT source, external_id FROM source_links")
    suspend fun linkedKeys(): List<LinkedKey>

    @Query("SELECT * FROM source_answers")
    suspend fun allAnswers(): List<SourceAnswerEntity>

    @Query("SELECT id FROM source_links WHERE source = :source AND external_id = :externalId")
    suspend fun idsLinkedTo(source: String, externalId: String): List<String>

    /** A record nothing links any more is not worth an answer kept. */
    @Query(
        "DELETE FROM source_answers WHERE NOT EXISTS (SELECT 1 FROM source_links l " +
            "WHERE l.source = source_answers.source AND l.external_id = source_answers.external_id)",
    )
    suspend fun pruneAnswers()

    @Transaction
    suspend fun setGameName(ids: Collection<String>, name: String?) {
        val rows = getLinks(ids).associateBy { it.id }
        for (id in ids) putOrDelete((rows[id] ?: GameLinkEntity(id)).copy(gameName = name))
    }

    @Transaction
    suspend fun setSameAs(ids: Collection<String>, storeKey: String?) {
        val rows = getLinks(ids).associateBy { it.id }
        for (id in ids) putOrDelete((rows[id] ?: GameLinkEntity(id)).copy(sameAs = storeKey))
    }

    /** A row that says nothing is not kept. */
    suspend fun putOrDelete(row: GameLinkEntity) {
        if (row.gameName == null && row.sameAs == null) deleteLink(row.id) else putLink(row)
    }

    @Transaction
    suspend fun setSourceLink(ids: Collection<String>, source: String, externalId: String?) {
        for (id in ids) if (externalId == null) deleteSourceLink(id, source) else putSourceLink(SourceLinkEntity(id, source, externalId))
        pruneAnswers()
    }

    /** See [GameLinksStore.moveTo]: only into an empty place. */
    @Transaction
    suspend fun moveTo(fromId: String, toId: String) {
        val rows = getLinks(listOf(fromId, toId)).associateBy { it.id }
        val from = rows[fromId]
        if (from != null && rows[toId] == null) putLink(from.copy(id = toId))
        deleteLink(fromId)
        val links = getSourceLinks(listOf(fromId, toId)).groupBy { it.id }
        val taken = links[toId].orEmpty().map { it.source }.toSet()
        for (link in links[fromId].orEmpty()) if (link.source !in taken) putSourceLink(link.copy(id = toId))
        deleteSourceLinks(fromId)
    }
}

/** One linked record, as [GameLinksDao.linkedKeys] reads it. */
data class LinkedKey(
    val source: String,
    @ColumnInfo(name = "external_id") val externalId: String,
)

@Database(
    entities = [
        PlayHistoryEntity::class, PlayStartEntity::class, FavoriteEntity::class, GameLinkEntity::class, SourceLinkEntity::class, SourceAnswerEntity::class,
        dev.droidtop.library.saves.GameIdEntity::class, dev.droidtop.library.saves.SaveRuleEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class PlayHistoryDatabase : RoomDatabase() {
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun gameLinksDao(): GameLinksDao
    abstract fun saveRecordsDao(): dev.droidtop.library.saves.SaveRecordsDao

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

        // The F95zone thread link became a generic source link
        // (docs/SPEC.md 7g, "Where an update comes from"): every existing
        // thread link and answer moves to the source key "f95zone", the key
        // the F95zone plugin's `library.updates` entry declares, so a person's
        // links survive the move of F95 support into a plugin. game_links
        // keeps only the names, rebuilt because SQLite before 3.35 cannot
        // drop a column.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `source_links` (`id` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                        "`external_id` TEXT NOT NULL, PRIMARY KEY(`id`, `source`))",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `source_links` (`id`, `source`, `external_id`) " +
                        "SELECT `id`, 'f95zone', CAST(`f95_thread` AS TEXT) FROM `game_links` WHERE `f95_thread` IS NOT NULL",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `source_answers` (`source` TEXT NOT NULL, `external_id` TEXT NOT NULL, " +
                        "`version` TEXT, `url` TEXT, `checked_at` INTEGER NOT NULL, `gone` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`source`, `external_id`))",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `source_answers` (`source`, `external_id`, `version`, `url`, `checked_at`, `gone`) " +
                        "SELECT 'f95zone', CAST(`thread_id` AS TEXT), `version`, NULL, `checked_at`, `gone` FROM `f95_threads`",
                )
                db.execSQL("DROP TABLE IF EXISTS `f95_threads`")
                db.execSQL("CREATE TABLE IF NOT EXISTS `game_links_new` (`id` TEXT NOT NULL, `game_name` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO `game_links_new` (`id`, `game_name`) SELECT `id`, `game_name` FROM `game_links` WHERE `game_name` IS NOT NULL")
                db.execSQL("DROP TABLE `game_links`")
                db.execSQL("ALTER TABLE `game_links_new` RENAME TO `game_links`")
            }
        }

        // The ids a game is known by outside droidtop and the save rule it was matched to (docs/SPEC.md 7o "Saves"):
        // two new tables, nothing existing touched, for the same reason as MIGRATION_1_2.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `game_ids` (`game_id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`value` TEXT NOT NULL, PRIMARY KEY(`game_id`, `kind`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `save_rules` (`game_id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `how` TEXT NOT NULL, `checked_at` INTEGER NOT NULL, PRIMARY KEY(`game_id`))",
                )
            }
        }

        // The launch log (Droidtop/tracker#611): one new table, nothing existing touched, for the same reason as
        // MIGRATION_1_2. It starts empty; Home's time-of-day row waits until it has something to say.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `play_starts` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`game_id` TEXT NOT NULL, `started_at` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_starts_started_at` ON `play_starts` (`started_at`)")
            }
        }

        // A folder linked to a store's row of its game (Droidtop/tracker#745): one nullable column, nothing existing
        // touched, for the same reason as MIGRATION_1_2.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `game_links` ADD COLUMN `same_as` TEXT")
            }
        }

        fun get(context: Context): PlayHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PlayHistoryDatabase::class.java,
                    "droidtop-play-history.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build().also { instance = it }
            }
    }
}

class RoomGameLinksStore(context: Context) : GameLinksStore {
    private val dao = PlayHistoryDatabase.get(context).gameLinksDao()

    override suspend fun getAll(ids: Collection<String>): Map<String, GameLinks> {
        if (ids.isEmpty()) return emptyMap()
        val rows = ids.chunked(MAX_IDS_PER_QUERY).flatMap { dao.getLinks(it) }.associateBy { it.id }
        val links = ids.chunked(MAX_IDS_PER_QUERY).flatMap { dao.getSourceLinks(it) }
        val storeIds = ids.chunked(MAX_IDS_PER_QUERY).flatMap { dao.getStoreIds(it) }
            .groupBy({ it.gameId }, { "${it.kind}:${it.value}" })
        if (rows.isEmpty() && links.isEmpty() && storeIds.isEmpty()) return emptyMap()
        val answers = links.groupBy { it.source }.flatMap { (source, rows) ->
            rows.map { it.externalId }.distinct().chunked(MAX_IDS_PER_QUERY).flatMap { dao.getAnswers(source, it) }
        }.associateBy { it.key }
        val byId = links.groupBy { it.id }
        return (rows.keys + byId.keys + storeIds.keys).associateWith { id ->
            GameLinks(
                gameName = rows[id]?.gameName,
                sameAs = rows[id]?.sameAs,
                storeIds = storeIds[id].orEmpty(),
                sources = byId[id].orEmpty().map { row ->
                    SourceLink(row.source, row.externalId, answers[SourceKey(row.source, row.externalId)]?.toAnswer())
                },
            )
        }
    }

    override suspend fun setGameName(ids: Collection<String>, name: String?) = dao.setGameName(ids, name)

    override suspend fun setSourceLink(ids: Collection<String>, source: String, externalId: String?) =
        dao.setSourceLink(ids, source, externalId)

    override suspend fun setSameAs(ids: Collection<String>, storeKey: String?) = dao.setSameAs(ids, storeKey)

    override suspend fun moveTo(fromId: String, toId: String) = dao.moveTo(fromId, toId)

    override suspend fun linkedSources(): Map<SourceKey, SourceAnswer?> {
        val answers = dao.allAnswers().associateBy { it.key }
        return dao.linkedKeys().map { SourceKey(it.source, it.externalId) }.associateWith { answers[it]?.toAnswer() }
    }

    override suspend fun idsLinkedTo(key: SourceKey): List<String> = dao.idsLinkedTo(key.source, key.externalId)

    override suspend fun saveAnswer(key: SourceKey, answer: SourceAnswer) = dao.putAnswer(
        SourceAnswerEntity(key.source, key.externalId, answer.version, answer.url, answer.checkedAtEpochMs, answer.gone),
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

    override suspend fun startsSince(sinceMs: Long): List<PlayStart> = dao.startsSince(sinceMs).map { PlayStart(it.gameId, it.startedAt) }

    override suspend fun forgetStarts() = dao.pruneStarts(Long.MAX_VALUE)
}

/**
 * The most ids one `IN (:ids)` query binds. Room gives each id its own
 * variable, and SQLite before 3.32 (Android 11 and older, the Android 9
 * rig included) refuses a statement with more than 999; the library asks
 * about every game it holds at once when it first loads.
 */
private const val MAX_IDS_PER_QUERY = 500
