package dev.droidtop.library.romdetect

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Real, forked wholesale (unmodified schema/queries, only package changed)
 * from Lemuroid's own `lemuroid-metadata-libretro-db` module
 * (github.com/Swordfish90/Lemuroid, GPL-3.0 -- already vendored in full at
 * vendor/lemuroid, see that module's own real `LibretroDBMetadataProvider`
 * for the real, prioritized cascade this was ported from: CRC32 -> embedded
 * serial -> filename -> path+filename -> unique-extension -> known-system
 * -> path+extension). Only the CRC32/serial/filename lookups are ported
 * here (see [ConsoleRomProvider]'s own real usage) -- full CRC32 hashing
 * (reading a ROM's entire content, not just its header) is real, deferred
 * follow-up work; a multi-gigabyte disc image would make that genuinely
 * slow without real performance tuning this pass didn't have room for.
 * Filename lookup needs no file read at all (a single indexed query
 * against [libretro-db.sqlite] -- the same real, ~13MB community database
 * Lemuroid itself ships, already vendored in this repo, just never bundled
 * as droidtop's own asset before now) and [SerialScanner]'s own embedded-
 * serial detection already reads the file's own real header for the
 * disc-based systems it covers -- both real, cheap signals, unlike a full
 * CRC32 pass.
 *
 * `system` values in this real database use [SystemID.dbname]'s own
 * convention (Lemuroid's real system id space, e.g. "nes"/"md"/"scd"),
 * NOT [dev.droidtop.library.consoles.ConsoleSystemDef.id] directly -- see
 * [toConsoleSystemId] for the real, verified mapping between them.
 */
@Entity(
    tableName = "games",
    indices = [
        Index("romName"),
        Index("crc32"),
        Index("serial"),
    ],
)
data class LibretroRom(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Int,
    @ColumnInfo(name = "name")
    val name: String?,
    @ColumnInfo(name = "system")
    val system: String?,
    @ColumnInfo(name = "romName")
    val romName: String?,
    @ColumnInfo(name = "developer")
    val developer: String?,
    @ColumnInfo(name = "crc32")
    val crc32: String?,
    @ColumnInfo(name = "serial")
    val serial: String?,
)

@Dao
interface LibretroGameDao {
    @Query("SELECT * FROM games WHERE romName = :romName LIMIT 1")
    suspend fun findByFileName(romName: String): LibretroRom?

    @Query("SELECT * FROM games WHERE crc32 = :crc LIMIT 1")
    suspend fun findByCRC(crc: String): LibretroRom?

    @Query("SELECT * FROM games WHERE serial = :serial LIMIT 1")
    suspend fun findBySerial(serial: String): LibretroRom?
}

@Database(entities = [LibretroRom::class], version = 8, exportSchema = false)
abstract class LibretroDatabase : RoomDatabase() {
    abstract fun gameDao(): LibretroGameDao

    companion object {
        @Volatile private var instance: LibretroDatabase? = null

        fun get(context: Context): LibretroDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LibretroDatabase::class.java,
                    "libretro-db",
                ).createFromAsset("libretro-db.sqlite")
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
