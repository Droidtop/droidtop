package dev.droidtop.library.consoles

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Real, persistent, user-editable console-platform database -- replaces
 * [ES_DE_CONSOLE_SYSTEMS] (a compile-time-fixed Kotlin list) as the
 * actual runtime source of truth, matching real Daijishō-level platform
 * management: add a new platform, edit any platform's extensions/
 * display name/RetroArch core, delete one, all persisted and surviving
 * app restarts. [ES_DE_CONSOLE_SYSTEMS] itself is untouched and stays in
 * the codebase purely as immutable seed/factory-default data (see
 * [ConsoleSystemsRepository]).
 *
 * Same real singleton/[Room.databaseBuilder] pattern as [RomDatabase] --
 * read that file first if this one is unfamiliar.
 */
@Entity(tableName = "console_systems")
data class ConsoleSystemEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    // Comma-separated, same manual-serialization style CustomPlayerPrefs
    // already uses for a collection value in a single SharedPreferences/
    // column -- no need for a Room TypeConverter over a real Set<String>
    // for something this simple.
    val extensionsCsv: String,
    val retroArchCore: String?,
    // True for every row [ConsoleSystemsRepository] seeded from
    // [ES_DE_CONSOLE_SYSTEMS] on first run -- drives "restore defaults"
    // and a real (non-blocking) warning before deleting a built-in
    // platform, not a hard block: Daijishō itself lets a user delete or
    // edit any platform, built-in included.
    val isBuiltIn: Boolean,
)

@Dao
interface ConsoleSystemDao {
    @Query("SELECT * FROM console_systems ORDER BY displayName")
    suspend fun getAll(): List<ConsoleSystemEntity>

    @Query("SELECT COUNT(*) FROM console_systems")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConsoleSystemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ConsoleSystemEntity>)

    // Edits an existing platform only: a row deleted meanwhile stays
    // deleted (0 rows changed), where upsert would insert it again.
    @Update
    suspend fun update(entity: ConsoleSystemEntity): Int

    @Query("DELETE FROM console_systems WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM console_systems WHERE isBuiltIn = 1")
    suspend fun clearBuiltIns()
}

/**
 * Built-in RetroArch cores that the old Linux-derived platform data named
 * and that RetroArch for Android does not have under that name, with the
 * name ES-DE's Android es_systems.xml uses (droidtop-platforms
 * generator/from_esde_systems.py, Droidtop/tracker#271). Triples are
 * (system id, old shipped core, new core); null means no core.
 *
 * A database refresh never rewrites a row the person may have edited
 * (see [ConsoleSystemsRepository]), so without this every existing
 * install kept launching N64 and the arcade systems with a core file
 * that does not exist. Only a built-in row still holding exactly the old
 * shipped value changes; any other value is the person's own.
 */
internal val RETROARCH_CORE_CORRECTIONS: List<Triple<String, String?, String?>> = listOf(
    Triple("apple2gs", "mame", null),
    Triple("arcade", "mame", "mamearcade"),
    Triple("astrocde", "mame", null),
    Triple("consolearcade", "mame", "mamearcade"),
    Triple("cps", "mame", "mamearcade"),
    Triple("cps1", "mame", "mamearcade"),
    Triple("cps2", "mame", "mamearcade"),
    Triple("cps3", "mame", "mamearcade"),
    Triple("daphne", "mame", "dirksimple"),
    Triple("fmtowns", "mame", null),
    Triple("gamate", "mame", null),
    Triple("gameandwatch", "mame", "mamemess"),
    Triple("gamecom", "mame", null),
    Triple("gmaster", "mame", null),
    Triple("laserdisc", "mame", "dirksimple"),
    Triple("lcdgames", "mame", "mamemess"),
    Triple("mame", "mame", "mamearcade"),
    Triple("mess", "mess2015", "mamemess"),
    Triple("model2", "mame", "mamearcade"),
    Triple("model3", "mame", "supermodel"),
    Triple("n3ds", "azahar", "citra"),
    Triple("n64", "mupen64plus_next", "mupen64plus_next_gles3"),
    Triple("n64dd", "parallel_n64", "mupen64plus_next_gles3"),
    Triple("pico8", "retro8", "fake08"),
    Triple("pv1000", "mame", null),
    Triple("scv", "mame", null),
    Triple("sgb", "sameboy", "mesen2"),
    Triple("stv", "kronos", "mamearcade"),
    Triple("supracan", "mame", null),
    Triple("vsmile", "mame", null),
    Triple("wiiu", null, "cemu"),
)

/** Applies [RETROARCH_CORE_CORRECTIONS]; the schema is unchanged. */
val CONSOLE_SYSTEMS_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for ((id, old, new) in RETROARCH_CORE_CORRECTIONS) {
            if (old == null) {
                db.execSQL(
                    "UPDATE console_systems SET retroArchCore = ? WHERE id = ? AND isBuiltIn = 1 AND retroArchCore IS NULL",
                    arrayOf<Any?>(new, id),
                )
            } else {
                db.execSQL(
                    "UPDATE console_systems SET retroArchCore = ? WHERE id = ? AND isBuiltIn = 1 AND retroArchCore = ?",
                    arrayOf<Any?>(new, id, old),
                )
            }
        }
    }
}

@Database(entities = [ConsoleSystemEntity::class], version = 2, exportSchema = false)
abstract class ConsoleSystemsDatabase : RoomDatabase() {
    abstract fun consoleSystemDao(): ConsoleSystemDao

    companion object {
        @Volatile private var instance: ConsoleSystemsDatabase? = null

        fun get(context: Context): ConsoleSystemsDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ConsoleSystemsDatabase::class.java,
                    "droidtop-console-systems.db",
                ).addMigrations(CONSOLE_SYSTEMS_MIGRATION_1_2).build().also { instance = it }
            }
    }
}
