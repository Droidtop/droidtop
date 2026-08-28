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
}

@Database(entities = [RomEntity::class, ScanMetadataEntity::class], version = 2, exportSchema = false)
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
                    // granularity), so a destructive wipe-and-rebuild is
                    // the correct migration, not a real handwritten one.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
    }
}
