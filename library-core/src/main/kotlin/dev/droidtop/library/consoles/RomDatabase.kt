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
 * Real per-game metadata, scraped by [dev.droidtop.library.scraper.
 * LutrisScraperClient]/[dev.droidtop.library.scraper.IgdbScraperClient]
 * (wired into `:app`'s `ConsoleSystemsActivity`'s existing manual "scrape
 * artwork" action) -- deliberately a SEPARATE table from [RomEntity], not
 * more columns bolted onto it: [RomEntity] rows are a pure filesystem-scan
 * cache, destructively cleared and rebuilt every time
 * [ConsoleRomProvider.scanSystemFolder]'s own folder gets rescanned (see
 * [RomEntity]'s own doc comment) -- scraped metadata is real, separately
 * fetched network data that a filesystem rescan can neither regenerate
 * nor should ever silently discard. Keyed by the same real id
 * ([RomEntity.id], the ROM file's own absolute path) so it survives
 * indefinitely across rescans of the folder it lives in, merged back onto
 * the scanned [dev.droidtop.library.LibraryEntry] at read time (see
 * [ConsoleRomProvider]'s own metadata-merge helper) rather than being
 * part of the scan-and-replace cycle at all.
 *
 * Field set confirmed directly against real ES-DE source
 * (`es-app/src/MetaData.cpp`'s own `gameDecls` table -- a real local
 * clone kept at /root/es-de-reference for ongoing reference), not
 * guessed: matches ES-DE's own real `desc`/`developer`/`publisher`/
 * `genre`/`releasedate`/`rating`/`players`/`favorite` scraped fields
 * exactly. No ESRB/age-rating field -- confirmed real ES-DE has none.
 */
@Entity(tableName = "game_metadata")
data class GameMetadataEntity(
    @PrimaryKey val id: String,
    val description: String?,
    val developer: String?,
    val publisher: String?,
    val genre: String?,
    @ColumnInfo(name = "release_date") val releaseDate: String?,
    val rating: Float?,
    val players: String?,
    val favorite: Boolean,
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
            upsertGameMetadata(
                GameMetadataEntity(
                    id = id,
                    description = null,
                    developer = null,
                    publisher = null,
                    genre = null,
                    releaseDate = null,
                    rating = null,
                    players = null,
                    favorite = favorite,
                )
            )
        }
    }
}

@Database(entities = [RomEntity::class, ScanMetadataEntity::class, GameMetadataEntity::class], version = 3, exportSchema = false)
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
                    // Pure cache, safely rebuildable by rescanning -- no
                    // real user data to preserve across the v1 -> v2
                    // schema change (root-level -> per-folder metadata
                    // granularity), or the v2 -> v3 change (adding
                    // GameMetadataEntity, empty at v3's introduction), so
                    // a destructive wipe-and-rebuild is the correct
                    // migration, not a real handwritten one. Real,
                    // forward-looking caveat: once GameMetadataEntity
                    // actually holds real user-scraped data (descriptions/
                    // ratings a user waited on a real network fetch for),
                    // it stops being a "safely rebuildable, nothing lost"
                    // table like RomEntity/ScanMetadataEntity are -- a
                    // FUTURE schema bump touching this database for real
                    // needs a real handwritten Migration preserving
                    // game_metadata specifically, not another blanket
                    // destructive wipe.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
    }
}
