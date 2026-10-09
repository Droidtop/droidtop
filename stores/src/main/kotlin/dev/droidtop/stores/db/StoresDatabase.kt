package dev.droidtop.stores.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.droidtop.stores.data.AmazonGame
import dev.droidtop.stores.data.EpicGame
import dev.droidtop.stores.data.GOGGame
import dev.droidtop.stores.data.ItchGame
import dev.droidtop.stores.db.dao.AmazonGameDao
import dev.droidtop.stores.db.dao.EpicGameDao
import dev.droidtop.stores.db.dao.GOGGameDao
import dev.droidtop.stores.db.dao.ItchGameDao
import java.io.File

/**
 * droidtop's own copy of what each store says the person owns
 * (docs/SPEC.md 7g, "Stores"): the four store tables that used to live in
 * GameNative's `pluvia.db`, with the same table and column names so rows
 * read out of that database come across as they are ([GameNativeImport]).
 */
@Database(
    entities = [GOGGame::class, EpicGame::class, AmazonGame::class, ItchGame::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(StringListConverter::class)
abstract class StoresDatabase : RoomDatabase() {
    abstract fun gogGameDao(): GOGGameDao
    abstract fun epicGameDao(): EpicGameDao
    abstract fun amazonGameDao(): AmazonGameDao
    abstract fun itchGameDao(): ItchGameDao

    companion object {
        const val NAME = "stores.db"

        /** GameNative's tables of these four stores, brought across when this database is first made. */
        private val GAMENATIVE_TABLES = listOf("gog_games", "epic_games", "amazon_games", "itch_games")

        /** 1 to 2: the build an install was made from, so GOG and itch can say a newer one exists. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE gog_games ADD COLUMN installed_build_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE gog_games ADD COLUMN installed_version_name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE itch_games ADD COLUMN installed_stamp TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var instance: StoresDatabase? = null

        fun get(context: Context): StoresDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, StoresDatabase::class.java, NAME)
                    .addMigrations(MIGRATION_1_2)
                    .addCallback(
                        object : RoomDatabase.Callback() {
                            // Once, as the database is first made: what GameNative's database holds.
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                GameNativeImport.run(context.applicationContext, db, GAMENATIVE_TABLES)
                            }
                        },
                    )
                    .build()
                    .also { instance = it }
            }

        /**
         * The files whose modification times say a store's rows changed: the
         * database and its write-ahead log, which every write moves.
         */
        fun files(context: Context): List<File> {
            val db = context.getDatabasePath(NAME)
            return listOf(db, File(db.path + "-wal"))
        }
    }
}
