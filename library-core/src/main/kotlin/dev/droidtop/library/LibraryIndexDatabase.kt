package dev.droidtop.library

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
import androidx.room.Transaction

/**
 * One game's place in the index (docs/SPEC.md 7g, step 3): only what a
 * LIST needs -- identity, where it's shown, what it's filtered/sorted by,
 * and where its full [GameRecord] lives. Every column here is DERIVED
 * from a record, which is what makes a schema change here "drop and
 * rebuild from the records" (seconds, no games-root walk) rather than a
 * real migration.
 */
@Entity(tableName = "games")
data class GameIndexEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val root: String?,
    val part: String?,
    val kind: String,
    val systemId: String?,
    val title: String,
    val sortName: String?,
    val collectionSortName: String?,
    val missing: Boolean,
    val hidden: Boolean,
    val favorite: Boolean,
    val completed: Boolean,
    val kidGame: Boolean,
    val broken: Boolean,
    val genre: String?,
    val players: String?,
    val rating: Float?,
    val releaseDate: String?,
    val artworkUri: String?,
    /** Where [GameRecordStore] keeps this game's full record -- see [recordPathFor]. */
    val recordPath: String,
)

/**
 * One part of one provider's walk (docs/SPEC.md 7g, step 3) -- the same
 * unit [ScanStep.Segment] answers in. [folderMtime] backs the slow
 * rebuild pass (step 4): 0 when [key] isn't itself a directory (a
 * console system's part is several folders, not one -- see
 * [dev.droidtop.library.consoles.ConsoleRomProvider]'s own doc comment),
 * which step 4 must read as "unknown, walk it" rather than "unchanged."
 */
@Entity(tableName = "parts", primaryKeys = ["provider", "key"])
data class PartIndexEntity(
    val provider: String,
    val key: String,
    val root: String?,
    val folderMtime: Long,
    val walkedAt: Long,
)

@Dao
interface LibraryIndexDao {
    @Query("SELECT * FROM games")
    suspend fun allGames(): List<GameIndexEntity>

    @Query("SELECT * FROM parts")
    suspend fun allParts(): List<PartIndexEntity>

    @Query("SELECT * FROM games WHERE provider = :provider")
    suspend fun gamesFor(provider: String): List<GameIndexEntity>

    @Query("SELECT * FROM parts WHERE provider = :provider")
    suspend fun partsFor(provider: String): List<PartIndexEntity>

    @Query("DELETE FROM games WHERE provider = :provider AND part IS :part")
    suspend fun deleteGamesInPart(provider: String, part: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGames(games: List<GameIndexEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPart(part: PartIndexEntity)

    /**
     * The one transaction docs/SPEC.md 7g, step 3 asks for: a finished
     * part's rows replace that part's previous ones, and the part's own
     * row is upserted, together -- so a reader never sees a part with
     * its games cleared but its own row not yet updated (or vice
     * versa).
     */
    @Transaction
    suspend fun replacePart(part: PartIndexEntity, games: List<GameIndexEntity>) {
        deleteGamesInPart(part.provider, part.key)
        if (games.isNotEmpty()) insertGames(games)
        upsertPart(part)
    }

    @Query("DELETE FROM games WHERE provider = :provider")
    suspend fun deleteProvider(provider: String)

    @Query("DELETE FROM parts WHERE provider = :provider")
    suspend fun deletePartsForProvider(provider: String)

    @Query("DELETE FROM parts WHERE provider = :provider AND `key` = :key")
    suspend fun deletePart(provider: String, key: String)

    @Query("DELETE FROM games")
    suspend fun clearGames()

    @Query("DELETE FROM parts")
    suspend fun clearParts()

    @Query("SELECT COUNT(*) FROM games")
    suspend fun gameCount(): Int
}

/**
 * `library-index.db` (docs/SPEC.md 7g, step 3) -- its own file, separate
 * from [PlayHistoryDatabase], because it holds a different kind of thing
 * (a DERIVED index, safe to drop and rebuild) and changes shape on its
 * own schedule.
 *
 * `fallbackToDestructiveMigration`, deliberately: every column here is
 * derived from a [GameRecord] (see [GameIndexEntity]'s own doc comment),
 * so a version bump just drops and rebuilds from the records
 * ([RoomLibraryIndexStore.rebuildFromRecords]) rather than needing a real
 * migration path the way [PlayHistoryDatabase] (real play counts, not
 * derivable from anything else) does.
 */
@Database(entities = [GameIndexEntity::class, PartIndexEntity::class], version = 1, exportSchema = false)
abstract class LibraryIndexDatabase : RoomDatabase() {
    abstract fun dao(): LibraryIndexDao

    companion object {
        @Volatile private var instance: LibraryIndexDatabase? = null

        fun get(context: Context): LibraryIndexDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LibraryIndexDatabase::class.java,
                    "library-index.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
