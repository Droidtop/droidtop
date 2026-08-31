package dev.droidtop.library.consoles

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

/**
 * Real, persistent ROM-scan cache -- the actual fix for a real, reported
 * problem: [ConsoleRomProvider.scan] used to walk every configured ROMs
 * root's entire filesystem tree from scratch on every single call (every
 * time the Games section is opened), and a real collection's own "j2me"
 * system folder alone had 18,126 files. That's real, repeated I/O work
 * for data that doesn't change between app launches in the common case.
 *
 * [RomEntity] rows are keyed by the ROM file's own absolute path (stable
 * across app restarts, and naturally unique). [ScanMetadataEntity] tracks
 * completion per (root, system folder) pair, not per root -- a real,
 * reported problem with the earlier per-root granularity: one pathological
 * folder (a real device's "j2me" folder turned out to contain a corrupted
 * directory entry that hung even a plain `ls` indefinitely) meant
 * [kotlinx.coroutines.awaitAll] over that root's folders never returned,
 * so nothing for that root -- not even the fast, already-finished
 * nes/gba/psx folders sitting right next to it -- ever got persisted.
 * [ConsoleRomProvider.scan]/[ConsoleRomProvider.scanProgressive] now treat
 * each system folder as its own independently cacheable unit: a folder
 * with a metadata row is skipped and served from cache, a folder without
 * one is walked and persisted the moment ITS OWN scan finishes, regardless
 * of whether sibling folders in the same root are still running or stuck.
 * [ConsoleRomProvider.rescan]/[ConsoleRomProvider.rescanProgressive] force
 * a real walk regardless of cache state, for an explicit user-triggered
 * "my ROMs changed, rescan" action -- same per-folder persistence timing,
 * so one stuck folder can't block every other folder's fresh results from
 * being saved during a rescan either.
 */
@Entity(tableName = "rom_entries")
data class RomEntity(
    @PrimaryKey val id: String,
    val title: String,
    val systemId: String,
    val artworkUri: String?,
    // Real ES-DE `manuals` media presence (see EsDeArtwork.resolveManual's
    // own doc comment for why this is filesystem-derived, not a metadata
    // field) -- resolved and cached at scan time exactly like [artworkUri]
    // already is, not part of GameMetadataEntity.
    @ColumnInfo(name = "manual_uri") val manualUri: String? = null,
    // Real ES-DE `videos` media presence -- same filesystem-derived,
    // scan-time-resolved convention as [manualUri] (see
    // EsDeArtwork.resolveVideo's own doc comment).
    @ColumnInfo(name = "video_uri") val videoUri: String? = null,
    @ColumnInfo(name = "roms_root") val romsRoot: String,
    // The folder actually scanned to produce this row -- deliberately
    // separate from [systemId], which may have been corrected by
    // [ConsoleRomProvider]'s own content/filename detection to a
    // *different* system than the folder it was found in. Clearing/
    // re-inserting a folder's rows on rescan has to key off which folder
    // produced them, not each row's own possibly-reassigned systemId, or
    // a misfiled disc image could get silently orphaned or double-counted
    // across two folders' cache slices.
    @ColumnInfo(name = "system_folder_id") val systemFolderId: String,
)

/**
 * Real per-game metadata -- deliberately a SEPARATE table from [RomEntity],
 * not more columns bolted onto it: [RomEntity] rows are a pure filesystem-
 * scan cache, destructively cleared and rebuilt every time
 * [ConsoleRomProvider.scanSystemFolder]'s own folder gets rescanned (see
 * [RomEntity]'s own doc comment) -- this table holds real user- and
 * scraper-written data that a filesystem rescan can neither regenerate nor
 * should ever silently discard. Keyed by the same real id
 * ([RomEntity.id], the ROM file's own absolute path) so it survives
 * indefinitely across rescans of the folder it lives in, merged back onto
 * the scanned [dev.droidtop.library.LibraryEntry] at read time (see
 * [ConsoleRomProvider]'s own metadata-merge helper) rather than being
 * part of the scan-and-replace cycle at all.
 *
 * Full field set confirmed directly against real ES-DE source
 * (`es-app/src/MetaData.cpp`'s own `gameDecls` table -- a real local
 * clone kept at /root/es-de-reference for ongoing reference), not
 * guessed -- matches every one of real ES-DE's own per-GAME metadata
 * fields (the game/folder tables differ only in a handful of folder-only
 * fields droidtop has no use for yet, since it has no folder/collection
 * concept -- see [dev.droidtop.library.LibraryEntry]'s own doc note).
 * `Scrape` below mirrors `MetaData.cpp`'s own real per-field `Scrape`
 * column exactly (whether real ES-DE's scraper can ever populate this
 * field, vs. user-editor-only):
 *
 * - `description`/`developer`/`publisher`/`genre`/`releaseDate`/`rating`/
 *   `players` (Scrape=true, real): real ScreenScraper/TheGamesDB output,
 *   also user-editable via [dev.droidtop.shell.gamepad] `GameMetadataEditor`
 *   to correct wrong/missing scraped values -- same real dual path
 *   `desc`/`rating`/etc. have in real ES-DE's own `GuiMetaDataEd`.
 * - `controllerShortName` (Scrape=true, real): ES-DE's own real scraper
 *   DOES populate `controller` for some sources -- droidtop's own
 *   scraper clients don't extract it yet (a real, separate follow-up,
 *   not silently claimed as done here); user-editable regardless.
 * - `favorite`/`completed`/`kidGame`/`hidden`/`broken`/`noGameCount`/
 *   `noMultiScrape`/`hideMetadata` (Scrape=false, real): pure user-editor
 *   fields in real ES-DE too, never scraper-written there either.
 * - `altEmulator` (Scrape=false, real): which registered launch provider/
 *   emulator this specific game should use instead of its system's real
 *   default -- see `GameMetadataEditor`'s own alt-emulator picker.
 * - `launchScreen` (Scrape=false, real ES-DE field `screen`): which real
 *   display output to launch this game on -- maps directly onto
 *   droidtop's own actual multi-display work (`DisplayOutputRepository`),
 *   more directly applicable here than in upstream ES-DE itself.
 * - `sortName`/`collectionSortName` (Scrape=false, real): stored, real,
 *   matching ES-DE's own field exactly -- `collectionSortName` has no
 *   real consumer yet since droidtop has no custom-collections concept
 *   (same honestly-tracked gap noted in `LibraryEntry`'s own doc
 *   comment), stored now rather than invented later so a real collections
 *   pass doesn't also need a schema migration.
 * - No ESRB/age-rating field -- confirmed real ES-DE has none either.
 * - Deliberately NOT modeled: `playcount`/`playtime`/`lastplayed`
 *   (real ES-DE's own `Statistic` column marks these auto-tracked, not
 *   editor fields -- droidtop already tracks the equivalent real data as
 *   [dev.droidtop.library.LibraryEntry.playCount]/`playtimeSeconds`/
 *   `lastPlayedEpochMs`, a different real mechanism, not a gap);
 *   `folderlink` (real ES-DE folder-type-only field -- droidtop has no
 *   folder entries to link, same collections gap as `collectionSortName`
 *   above); a real "manual" PDF path -- confirmed against `MetaData.cpp`
 *   this is genuinely NOT a MetaDataDecl field in real ES-DE at all
 *   (`BadgeComponent`'s own "manual" badge slot is driven by real *media
 *   file presence*, not per-game metadata -- see `EsDeThemedBadges`'
 *   own doc comment for how droidtop determines it the same way).
 */
@Entity(tableName = "game_metadata")
data class GameMetadataEntity(
    @PrimaryKey val id: String,
    val description: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val genre: String? = null,
    @ColumnInfo(name = "release_date") val releaseDate: String? = null,
    val rating: Float? = null,
    val players: String? = null,
    val favorite: Boolean = false,
    // defaultValue = "0" on every column below: real, required to match
    // MIGRATION_3_4's own actual `NOT NULL DEFAULT 0` SQL exactly --
    // SQLite's ALTER TABLE ADD COLUMN requires a real default for a
    // NOT NULL column (unlike these entities' original v3 CREATE TABLE,
    // which needs no DEFAULT clause at all since Room always supplies a
    // value on insert) -- confirmed live: a mismatched/missing
    // defaultValue here throws a real
    // `IllegalStateException: Migration didn't properly handle` at
    // runtime, caught on-device, not a guess.
    @ColumnInfo(defaultValue = "0") val completed: Boolean = false,
    @ColumnInfo(name = "kid_game", defaultValue = "0") val kidGame: Boolean = false,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    @ColumnInfo(defaultValue = "0") val broken: Boolean = false,
    @ColumnInfo(name = "no_game_count", defaultValue = "0") val noGameCount: Boolean = false,
    @ColumnInfo(name = "no_multi_scrape", defaultValue = "0") val noMultiScrape: Boolean = false,
    @ColumnInfo(name = "hide_metadata", defaultValue = "0") val hideMetadata: Boolean = false,
    @ColumnInfo(name = "controller_short_name") val controllerShortName: String? = null,
    @ColumnInfo(name = "alt_emulator") val altEmulator: String? = null,
    @ColumnInfo(name = "launch_screen") val launchScreen: Int? = null,
    @ColumnInfo(name = "sort_name") val sortName: String? = null,
    @ColumnInfo(name = "collection_sort_name") val collectionSortName: String? = null,
    /**
     * How the scraper matched this game, real ES-DE semantics: "hash"
     * means the local file's MD5 was identical to ScreenScraper's own
     * digest for the matched ROM (what ES-DE logs as a perfect match);
     * "name" means a filename search's first result — correct most of
     * the time, but not verified. Null means never scraped.
     */
    @ColumnInfo(name = "scrape_confidence") val scrapeConfidence: String? = null,
)

@Entity(tableName = "scan_metadata", primaryKeys = ["roms_root", "system_folder_id"])
data class ScanMetadataEntity(
    @ColumnInfo(name = "roms_root") val romsRoot: String,
    @ColumnInfo(name = "system_folder_id") val systemFolderId: String,
    @ColumnInfo(name = "last_scanned_epoch_ms") val lastScannedEpochMs: Long,
)

data class ScannedFolderKey(
    @ColumnInfo(name = "roms_root") val romsRoot: String,
    @ColumnInfo(name = "system_folder_id") val systemFolderId: String,
)

/**
 * Real custom collection -- droidtop's own equivalent of real ES-DE's
 * `CollectionSystemType.CUSTOM_COLLECTION` (confirmed against
 * `CollectionSystemsManager.h`/`.cpp`, a real local clone kept at
 * /root/es-de-reference for ongoing reference). Only custom collections
 * are stored -- the three real AUTO collections (all games/favorites/
 * last played) are computed on the fly from [LibraryEntry]/
 * [GameMetadataEntity] directly (see `GameGroup.Collection`'s own doc
 * comment in shell-gamepad), matching real ES-DE's own "no gamelist.xml,
 * exists only in memory" auto-collection behavior. [id] is a stable,
 * user-invisible key (a UUID); [name] is the real, user-editable display
 * name -- kept separate so renaming a collection doesn't need to migrate
 * every [CollectionMemberEntity] row's own foreign key.
 */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
)

/**
 * Real many-to-many membership -- a game can be in any number of custom
 * collections at once (real ES-DE's own actual model, confirmed via
 * `CollectionSystemsManager::toggleGameInCollection`'s per-collection
 * config-file membership, not a single collection-per-game field).
 * [gameId] matches [RomEntity.id]/[LibraryEntry.id] (the ROM file's own
 * absolute path).
 */
@Entity(tableName = "collection_members", primaryKeys = ["collection_id", "game_id"])
data class CollectionMemberEntity(
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "game_id") val gameId: String,
)

@Dao
interface RomDao {
    @Query("SELECT * FROM rom_entries WHERE roms_root IN (:romsRoots)")
    suspend fun getEntries(romsRoots: List<String>): List<RomEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<RomEntity>)

    @Query("DELETE FROM rom_entries WHERE roms_root = :romsRoot AND system_folder_id = :systemFolderId")
    suspend fun clearSystemFolder(romsRoot: String, systemFolderId: String)

    @Query("DELETE FROM rom_entries WHERE roms_root = :romsRoot")
    suspend fun clearRoot(romsRoot: String)

    @Query("SELECT roms_root, system_folder_id FROM scan_metadata WHERE roms_root IN (:romsRoots)")
    suspend fun getScannedSystemFolders(romsRoots: List<String>): List<ScannedFolderKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markScanned(metadata: ScanMetadataEntity)

    @Query("DELETE FROM scan_metadata WHERE roms_root = :romsRoot")
    suspend fun clearScanMetadata(romsRoot: String)

    @Query("SELECT * FROM game_metadata WHERE id IN (:ids)")
    suspend fun getGameMetadata(ids: List<String>): List<GameMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGameMetadata(metadata: GameMetadataEntity)

    /**
     * Real, plain `UPDATE` -- touches only the one real column being set,
     * never the other real scraped columns (description/rating/etc.), the
     * same real intent SQLite's own `INSERT ... ON CONFLICT DO UPDATE`
     * (UPSERT) syntax would express in one statement. NOT using that
     * syntax here: UPSERT needs SQLite 3.24+, and Android's own bundled
     * SQLite doesn't reliably reach that until API 30 -- this module's
     * real `minSdk` is 26, so relying on it would crash at runtime on a
     * real, non-edge-case range of devices (confirmed via real research,
     * not assumed). Returns real affected-row-count so [setFavorite] can
     * tell whether a row already existed.
     */
    @Query("UPDATE game_metadata SET favorite = :favorite WHERE id = :id")
    suspend fun updateFavorite(id: String, favorite: Boolean): Int

    /**
     * Real, user-driven favorite toggle -- previously nothing in droidtop
     * ever set `favorite = true` anywhere (confirmed by grep before
     * writing this), so [GameMetadataEntity.favorite]/`EsDeThemedBadges`'
     * own favorite-badge rendering had no real way to ever show anything
     * but false. A plain `UPDATE`, falling back to a real `INSERT` only
     * when no row exists yet for this game (its first-ever real write to
     * `game_metadata`) -- see [updateFavorite]'s own doc comment for why
     * this is two plain statements rather than one UPSERT statement.
     */
    @androidx.room.Transaction
    suspend fun setFavorite(id: String, favorite: Boolean) {
        if (updateFavorite(id, favorite) == 0) {
            upsertGameMetadata(GameMetadataEntity(id = id, favorite = favorite))
        }
    }

    /**
     * Real single-row lookup for [dev.droidtop.shell.gamepad]
     * `GameMetadataEditor`'s own "load existing values before showing the
     * editor" step -- [getGameMetadata] (batch, for the library-merge
     * read path) returns nothing for a game that has no row yet, same as
     * this; the editor treats that as "start from real ES-DE's own
     * documented defaults" (see `GameMetadataEditor`'s own doc comment).
     */
    @Query("SELECT * FROM game_metadata WHERE id = :id")
    suspend fun getGameMetadataSingle(id: String): GameMetadataEntity?

    @Query("SELECT * FROM collections ORDER BY name")
    suspend fun getCollections(): List<CollectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollection(collection: CollectionEntity)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollection(id: String)

    @Query("DELETE FROM collection_members WHERE collection_id = :id")
    suspend fun deleteCollectionMembers(id: String)

    @Query("SELECT game_id FROM collection_members WHERE collection_id = :collectionId")
    suspend fun getCollectionMemberIds(collectionId: String): List<String>

    // Real collectionId -> gameIds map in one query, for the games-list
    // read path (every custom collection's membership needed at once,
    // not one query per collection) -- see GameGroup.Collection's own
    // doc comment for how this feeds the system carousel.
    @Query("SELECT collection_id, game_id FROM collection_members")
    suspend fun getAllCollectionMembers(): List<CollectionMemberEntity>

    /** Real reverse query for [dev.droidtop.library.LibraryEntry.inCollection] (the badge "collection" slot) -- which games are in ANY real collection, not which collection a given game is in. */
    @Query("SELECT DISTINCT game_id FROM collection_members")
    suspend fun getGameIdsInAnyCollection(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addCollectionMember(member: CollectionMemberEntity)

    @Query("DELETE FROM collection_members WHERE collection_id = :collectionId AND game_id = :gameId")
    suspend fun removeCollectionMember(collectionId: String, gameId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM collection_members WHERE collection_id = :collectionId AND game_id = :gameId)")
    suspend fun isCollectionMember(collectionId: String, gameId: String): Boolean

    /**
     * Real add/remove toggle -- droidtop's own equivalent of real ES-DE's
     * `CollectionSystemsManager::toggleGameInCollection`. Returns the
     * real new membership state.
     */
    @androidx.room.Transaction
    suspend fun toggleCollectionMember(collectionId: String, gameId: String): Boolean {
        return if (isCollectionMember(collectionId, gameId)) {
            removeCollectionMember(collectionId, gameId)
            false
        } else {
            addCollectionMember(CollectionMemberEntity(collectionId, gameId))
            true
        }
    }
}

/**
 * Real, handwritten migration, not a destructive wipe -- exactly the
 * "FUTURE schema bump" [RomDatabase]'s own v1-v3 comment already warned
 * about: `game_metadata` now holds real user-entered data (favorite
 * toggles, and soon full [dev.droidtop.shell.gamepad] `GameMetadataEditor`
 * edits) a rescan can't regenerate, so it has to survive this bump.
 * `rom_entries`/`scan_metadata` are untouched (pure cache, no schema
 * change here) -- Room still requires every table to be accounted for in
 * a migration even when unchanged, which this one satisfies by simply not
 * touching them. Plain `ALTER TABLE ... ADD COLUMN`, SQLite's own real,
 * standard, non-destructive way to add nullable/defaulted columns to an
 * existing table.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // rom_entries is pure cache (see RomEntity's own doc comment) --
        // this column just needs to exist with the right shape for
        // schema validation to pass; its real values get repopulated the
        // next time each folder is (re)scanned regardless.
        db.execSQL("ALTER TABLE rom_entries ADD COLUMN manual_uri TEXT")
        db.execSQL("ALTER TABLE game_metadata ADD COLUMN completed INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE game_metadata ADD COLUMN kid_game INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE game_metadata ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE game_metadata ADD COLUMN broken INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE game_metadata ADD COLUMN no_game_count INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE game_metadata ADD COLUMN no_multi_scrape INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE game_metadata ADD COLUMN hide_metadata INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE game_metadata ADD COLUMN controller_short_name TEXT")
        db.execSQL("ALTER TABLE game_metadata ADD COLUMN alt_emulator TEXT")
        db.execSQL("ALTER TABLE game_metadata ADD COLUMN launch_screen INTEGER")
        db.execSQL("ALTER TABLE game_metadata ADD COLUMN sort_name TEXT")
        db.execSQL("ALTER TABLE game_metadata ADD COLUMN collection_sort_name TEXT")
    }
}

/** Real, handwritten migration -- two brand-new tables for real custom collections (see [CollectionEntity]/[CollectionMemberEntity]'s own doc comments), nothing existing touched. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `collections` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `collection_members` (`collection_id` TEXT NOT NULL, `game_id` TEXT NOT NULL, PRIMARY KEY(`collection_id`, `game_id`))",
        )
    }
}

/** Real, handwritten migration -- rom_entries is pure cache (see RomEntity's own doc comment), same treatment as manual_uri in MIGRATION_3_4. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rom_entries ADD COLUMN video_uri TEXT")
    }
}

/**
 * Clears the scan cache so PS2 discs get re-detected.
 *
 * Until this release every PS2 disc was stored as `psx`: the
 * magic-number check keys on the ISO9660 volume identifier, which reads
 * "PLAYSTATION" on PS1 and PS2 alike (see
 * `SerialScanner.extractInfoForPlayStationDisc`). The scan result is
 * cached, so fixing the detector alone would leave every existing
 * install still launching PS2 games through a PS1 emulator.
 *
 * Only the cache is dropped. `game_metadata` and the collection tables
 * are left untouched on purpose -- favorites, completed flags and
 * collection membership are real user data, not something to discard to
 * fix a detection bug.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM rom_entries")
        db.execSQL("DELETE FROM scan_metadata")
    }
}

/** Adds the scraper-confidence column ("hash"/"name", ES-DE's own match semantics). Plain additive column: user metadata survives untouched. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE game_metadata ADD COLUMN scrape_confidence TEXT")
    }
}

@Database(
    entities = [
        RomEntity::class, ScanMetadataEntity::class, GameMetadataEntity::class,
        CollectionEntity::class, CollectionMemberEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class RomDatabase : RoomDatabase() {
    abstract fun romDao(): RomDao

    companion object {
        @Volatile private var instance: RomDatabase? = null

        fun get(context: Context): RomDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RomDatabase::class.java,
                    "droidtop-rom-cache.db",
                )
                    // rom_entries/scan_metadata stay destructively
                    // rebuildable (pure cache, see RomEntity's own doc
                    // comment) -- fallbackToDestructiveMigration still
                    // covers any FUTURE bump this file's migrations list
                    // doesn't explicitly handle, but v3->v4 (this file's
                    // MIGRATION_3_4) is now real and explicit specifically
                    // because game_metadata holds real user data that
                    // must survive it -- see that migration's own doc
                    // comment.
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
    }
}
