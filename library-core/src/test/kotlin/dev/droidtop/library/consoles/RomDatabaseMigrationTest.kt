package dev.droidtop.library.consoles

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Walks the real `game_metadata` migration chain, v3 to now, over a real
 * SQLite file with a real user row in it.
 *
 * `game_metadata` is the one RomDatabase table that holds real user data
 * (favourites, editor edits, the field_sources provenance), and RomDatabase
 * is built with `fallbackToDestructiveMigration(dropAllTables = true)`
 * (RomDatabase.kt:472): a future version bump that forgets its migration
 * path silently wipes all of it, and nothing would notice until a person's
 * library edits were already gone -- the deep review of fc48b5dc flagged
 * exactly that gap (no test walks the chain; pcgames/REVIEW.md, appended
 * 2026-09-29). This test is the alarm.
 *
 * The fixture database is built the way a real v3 install's file actually
 * was: the v3 CREATE TABLEs below are Room's own generated v3 DDL, taken
 * from the entity set of 4efbe098, the last commit whose @Database carried
 * version 3. exportSchema was always false, so there are no historical
 * schema JSONs a MigrationTestHelper could replay; the hand-built v3 file
 * is the only faithful way to walk from v3.
 *
 * The walk is split at v4 rather than one Room pass, because `completed`
 * only exists from MIGRATION_3_4 on: the fixture row takes its completed
 * flag at v4 the way a real install that had already upgraded to v4 carried
 * it, and Room then walks 4 -> now through the rest of the registered chain
 * itself. Room's own post-migration schema validation -- the same check
 * that threw "Migration didn't properly handle" on a real device when the
 * defaultValue annotations mismatched (RomDatabase.kt:85-92, fixed in
 * 36adf2d5) -- is what asserts the entity and the migrated schema agree.
 * This builder registers the migrations and nothing else: a future bump
 * the chain doesn't cover fails here with "A migration from X to Y was
 * required but not found" instead of falling back to the wipe.
 */
@RunWith(RobolectricTestRunner::class)
// A library module has no targetSdk of its own for Robolectric to pick its
// android-all jar from; pin one so the run is the same everywhere.
@Config(sdk = [34])
class RomDatabaseMigrationTest {
    @Test
    fun `a favourite v3 row rides the whole migration chain with its values intact`() {
        val context = RuntimeEnvironment.getApplication()
        val dbFile = context.getDatabasePath(TEST_DB)
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        // v3: the tables a real v3 install had, with the user's row already
        // in game_metadata (favourite set; `completed` does not exist yet).
        // The framework opener records user_version = 3 exactly the way it
        // did on device -- no hand-set PRAGMA.
        val v3Helper = helperAtVersion(
            context,
            3,
            create = { db ->
                db.execSQL(V3_GAME_METADATA)
                db.execSQL(V3_ROM_ENTRIES)
                db.execSQL(V3_SCAN_METADATA)
                db.execSQL(INSERT_V3_ROW)
            },
            upgrade = { _, _, _ -> error("the v3 fixture is created, never upgraded by this helper") },
        )
        v3Helper.writableDatabase
        v3Helper.close()

        // v3 -> v4: the real MIGRATION_3_4 object runs over the real v3
        // file (the same one RomDatabase.get registers), and only now that
        // `completed` exists does the row take the flag a v4 install
        // carried. The framework opener then records user_version = 4.
        val v4Helper = helperAtVersion(
            context,
            4,
            create = { _ -> error("the v3 fixture file already exists") },
            upgrade = { db, _, _ ->
                MIGRATION_3_4.migrate(db)
                db.execSQL("UPDATE game_metadata SET completed = 1")
            },
        )
        v4Helper.writableDatabase
        v4Helper.close()

        // 4 -> now: Room itself walks the rest of the registered chain over
        // the file and validates the result against the current entity set.
        // The journal is pinned to the classic rollback mode so the run does
        // not depend on WAL support in the test JVM; no destructive
        // fallback is registered, on purpose (see the class comment).
        val room = Room.databaseBuilder(context, RomDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()

        // The first query is what triggers the upgrade path: open, run
        // 4_5..10_11, validate, then hand the row to the DAO. Everything
        // the user had at v3/v4 must read back exactly as it went in, and
        // the six columns MIGRATION_9_10 added (series, links,
        // field_sources, hero_path, logo_path, icon_path) must read back
        // null for a row that predates them.
        val migrated = runBlocking { room.romDao().getGameMetadataSingle(GAME_ID) }
        assertEquals(
            EXPECTED_ROW,
            checkNotNull(migrated) { "the user's game_metadata row must survive the whole chain" },
        )

        // And the schema shape itself: those six columns must still exist
        // and stay nullable, so a future migration that renames one or
        // hardens it to NOT NULL fails with the column's name in the
        // message rather than only through Room's generic validation.
        val notNullByColumn = room.openHelper.readableDatabase
            .query("PRAGMA table_info(game_metadata)")
            .use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(1), cursor.getInt(3))
                }
            }
        for (column in listOf("series", "links", "field_sources", "hero_path", "logo_path", "icon_path")) {
            assertTrue("game_metadata must still carry its $column column", column in notNullByColumn)
            assertEquals("$column must stay a nullable column", 0, notNullByColumn[column])
        }

        room.close()
    }

    /**
     * A framework opener -- the same `FrameworkSQLiteOpenHelperFactory`
     * Room itself uses on Android -- so the version bookkeeping around
     * the fixture (create at 3, upgrade to 4) is the framework's own, not
     * a hand-maintained PRAGMA.
     */
    private fun helperAtVersion(
        context: Context,
        version: Int,
        create: (SupportSQLiteDatabase) -> Unit,
        upgrade: (SupportSQLiteDatabase, Int, Int) -> Unit,
    ): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = create(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = upgrade(db, oldVersion, newVersion)
                })
                .build(),
        )

    private companion object {
        private const val TEST_DB = "romdatabase-migration-test.db"
        private const val GAME_ID = "/storage/emulated/0/Roms/nes/Super Mario Bros. (World).nes"

        // The v3 CREATE TABLEs, exactly what Room's generated v3 code
        // created on real devices: the entity set of 4efbe098, the last
        // @Database version = 3 commit. game_metadata's nine original
        // columns, plus the rom_entries/scan_metadata cache tables the
        // chain still ALTERs (MIGRATION_3_4, MIGRATION_5_6), clears
        // (MIGRATION_6_7) and drops (MIGRATION_10_11); their shape stops
        // mattering to validation once dropped, but a faithful v3 file
        // keeps them as they were.
        private const val V3_GAME_METADATA =
            "CREATE TABLE IF NOT EXISTS `game_metadata` (" +
                "`id` TEXT NOT NULL, `description` TEXT, `developer` TEXT, " +
                "`publisher` TEXT, `genre` TEXT, `release_date` TEXT, " +
                "`rating` REAL, `players` TEXT, `favorite` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        private const val V3_ROM_ENTRIES =
            "CREATE TABLE IF NOT EXISTS `rom_entries` (" +
                "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `systemId` TEXT NOT NULL, " +
                "`artworkUri` TEXT, `roms_root` TEXT NOT NULL, `system_folder_id` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))"
        private const val V3_SCAN_METADATA =
            "CREATE TABLE IF NOT EXISTS `scan_metadata` (" +
                "`roms_root` TEXT NOT NULL, `system_folder_id` TEXT NOT NULL, " +
                "`last_scanned_epoch_ms` INTEGER NOT NULL, " +
                "PRIMARY KEY(`roms_root`, `system_folder_id`))"

        // The row as it sat in a v3 database: every user-data column of the
        // v3 table, favourite already on. completed follows at v4.
        private const val INSERT_V3_ROW =
            "INSERT INTO game_metadata (`id`, `description`, `developer`, `publisher`, `genre`, " +
                "`release_date`, `rating`, `players`, `favorite`) VALUES (" +
                "'$GAME_ID', 'The classic 1985 platformer.', 'Nintendo R&D1', 'Nintendo', " +
                "'Platform', '1985', 3.5, '1-2', 1)"

        // What the row must read back as after the whole chain: every value
        // it had at v3/v4, everything MIGRATION_3_4 defaulted (the editor
        // booleans at 0, the nullable text columns null), and the six
        // MIGRATION_9_10 columns still null.
        private val EXPECTED_ROW = GameMetadataEntity(
            id = GAME_ID,
            description = "The classic 1985 platformer.",
            developer = "Nintendo R&D1",
            publisher = "Nintendo",
            genre = "Platform",
            releaseDate = "1985",
            rating = 3.5f,
            players = "1-2",
            favorite = true,
            completed = true,
        )
    }
}
