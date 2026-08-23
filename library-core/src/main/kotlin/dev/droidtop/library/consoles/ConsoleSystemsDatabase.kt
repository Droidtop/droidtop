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

    @Query("DELETE FROM console_systems WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM console_systems WHERE isBuiltIn = 1")
    suspend fun clearBuiltIns()
}

@Database(entities = [ConsoleSystemEntity::class], version = 1, exportSchema = false)
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
                ).build().also { instance = it }
            }
    }
}
