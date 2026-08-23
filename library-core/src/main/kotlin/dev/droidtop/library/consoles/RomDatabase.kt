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
 * across app restarts, and naturally unique). [ScanMetadataEntity] tracks,
 * per configured root, whether that root has ever been walked for real --
 * [ConsoleRomProvider.scan] only performs the real filesystem walk for a
 * root with no metadata row yet (first-ever scan), returning cached
 * [RomEntity] rows for anything already scanned. [ConsoleRomProvider.rescan]
 * forces a real walk regardless of cache state, for an explicit
 * user-triggered "my ROMs changed, rescan" action.
 */
@Entity(tableName = "rom_entries")
data class RomEntity(
    @PrimaryKey val id: String,
    val title: String,
    val systemId: String,
    val artworkUri: String?,
    @ColumnInfo(name = "roms_root") val romsRoot: String,
)

@Entity(tableName = "scan_metadata")
data class ScanMetadataEntity(
    @PrimaryKey @ColumnInfo(name = "roms_root") val romsRoot: String,
    @ColumnInfo(name = "last_scanned_epoch_ms") val lastScannedEpochMs: Long,
)

@Dao
interface RomDao {
    @Query("SELECT * FROM rom_entries WHERE roms_root IN (:romsRoots)")
    suspend fun getEntries(romsRoots: List<String>): List<RomEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<RomEntity>)

    @Query("DELETE FROM rom_entries WHERE roms_root = :romsRoot")
    suspend fun clearRoot(romsRoot: String)

    @Query("SELECT roms_root FROM scan_metadata WHERE roms_root IN (:romsRoots)")
    suspend fun getScannedRoots(romsRoots: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markScanned(metadata: ScanMetadataEntity)

    @Query("DELETE FROM scan_metadata WHERE roms_root = :romsRoot")
    suspend fun clearScanMetadata(romsRoot: String)
}

@Database(entities = [RomEntity::class, ScanMetadataEntity::class], version = 1, exportSchema = false)
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
                ).build().also { instance = it }
            }
    }
}
