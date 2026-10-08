package dev.droidtop.stores.steam

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
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.droidtop.stores.db.GameNativeImport
import `in`.dragonbra.javasteam.enums.ELicenseFlags
import `in`.dragonbra.javasteam.enums.ELicenseType
import java.io.File
import java.util.EnumSet
import kotlinx.serialization.json.Json

/*
 * droidtop's own copy of what Steam says the person owns and what is
 * installed (docs/SPEC.md 7g, "Stores"): the Steam tables GameNative kept in
 * its pluvia.db, under the same table and column names and with the same
 * value encodings (GameNative's AppConverter and LicenseConverter, GPL-3.0),
 * so its rows come across column by column when this database is first made.
 * A column only GameNative had is left behind; `install_path` is droidtop's.
 */

/** One app (game, DLC, tool) a licence names, filled in from Steam's product info. */
@Entity("steam_app")
data class SteamApp(
    @PrimaryKey val id: Int,
    @ColumnInfo("package_id")
    val packageId: Int = SteamIds.INVALID_PKG_ID,
    @ColumnInfo("received_pics")
    val receivedPICS: Boolean = false,
    @ColumnInfo("last_change_number")
    val lastChangeNumber: Int = 0,
    @ColumnInfo("depots")
    val depots: Map<Int, DepotInfo> = emptyMap(),
    @ColumnInfo("branches")
    val branches: Map<String, BranchInfo> = emptyMap(),
    @ColumnInfo("name")
    val name: String = "",
    @ColumnInfo("type")
    val type: AppType = AppType.invalid,
    @ColumnInfo("os_list")
    val osList: EnumSet<OS> = EnumSet.of(OS.none),
    @ColumnInfo("icon_hash")
    val iconHash: String = "",
    @ColumnInfo("header_image")
    val headerImage: Map<Language, String> = emptyMap(),
    @ColumnInfo("library_assets")
    val libraryAssets: LibraryAssetsInfo = LibraryAssetsInfo(),
    @ColumnInfo("dlc_for_app_id")
    val dlcForAppId: Int = SteamIds.INVALID_APP_ID,
    @ColumnInfo("install_dir")
    val installDir: String = "",
    @ColumnInfo("config")
    val config: ConfigInfo = ConfigInfo(),
) {
    /** The folder name Steam gives the game: its config's install folder, else its name (GameNative's getAppDirName). */
    val folderName: String get() = config.installDir.ifEmpty { installDir }.ifEmpty { name }

    /**
     * The library's portrait cover (600x900), in English or any language
     * Steam has one in; else the store header; null when Steam named neither.
     */
    val coverUrl: String?
        get() {
            val capsule = libraryAssets.libraryCapsule.image
            val file = capsule[Language.english] ?: capsule.values.firstOrNull()
            if (!file.isNullOrBlank()) return "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/$file"
            return if (headerImage.isNotEmpty() || receivedPICS) "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/header.jpg" else null
        }
}

/** One licence (a "package") the account holds, with the apps and depots it grants. */
@Entity("steam_license")
data class SteamLicense(
    @PrimaryKey val packageId: Int,
    @ColumnInfo("last_change_number")
    val lastChangeNumber: Int,
    @ColumnInfo("license_flags")
    val licenseFlags: EnumSet<ELicenseFlags>,
    @ColumnInfo("license_type")
    val licenseType: ELicenseType,
    @ColumnInfo("access_token")
    val accessToken: Long,
    @ColumnInfo("owner_account_id")
    val ownerAccountId: List<Int>,
    @ColumnInfo("app_ids")
    val appIds: List<Int> = emptyList(),
    @ColumnInfo("depot_ids")
    val depotIds: List<Int> = emptyList(),
)

/** A licence exactly as Steam sent it (LicenseSerializer's JSON), which the depot downloader is handed. */
@Entity("cached_license")
data class CachedLicense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("license_json")
    val licenseJson: String,
)

/**
 * What is installed of one app: the depots downloaded, the DLC apps among
 * them, the branch, and where. [installPath] is droidtop's: GameNative kept
 * no path for its own installs (it looked under its install folders by name)
 * and [customInstallPath] for a folder it was pointed at; the carry-over
 * fills [installPath] for both (SteamCarryOver).
 */
@Entity("app_info")
data class AppInfo(
    @PrimaryKey val id: Int,
    @ColumnInfo("is_downloaded")
    val isDownloaded: Boolean = false,
    @ColumnInfo("downloaded_depots")
    val downloadedDepots: List<Int> = emptyList(),
    @ColumnInfo("dlc_depots")
    val dlcDepots: List<Int> = emptyList(),
    @ColumnInfo("branch", defaultValue = "public")
    val branch: String = "public",
    @ColumnInfo("custom_install_path", defaultValue = "")
    val customInstallPath: String = "",
    @ColumnInfo("install_path", defaultValue = "")
    val installPath: String = "",
)

class SteamConverters {
    @TypeConverter fun toAppType(code: Int): AppType = AppType.fromCode(code)
    @TypeConverter fun fromAppType(type: AppType): Int = type.code
    @TypeConverter fun toOS(code: Int): EnumSet<OS> = OS.from(code)
    @TypeConverter fun fromOS(os: EnumSet<OS>): Int = OS.code(os)
    @TypeConverter fun toDepots(text: String): Map<Int, DepotInfo> = JSON.decodeFromString(text)
    @TypeConverter fun fromDepots(depots: Map<Int, DepotInfo>): String = JSON.encodeToString(depots)
    @TypeConverter fun toBranches(text: String): Map<String, BranchInfo> = JSON.decodeFromString(text)
    @TypeConverter fun fromBranches(branches: Map<String, BranchInfo>): String = JSON.encodeToString(branches)
    @TypeConverter fun toLangMap(text: String): Map<Language, String> = JSON.decodeFromString(text)
    @TypeConverter fun fromLangMap(map: Map<Language, String>): String = JSON.encodeToString(map)
    @TypeConverter fun toLibraryAssets(text: String): LibraryAssetsInfo = JSON.decodeFromString(text)
    @TypeConverter fun fromLibraryAssets(assets: LibraryAssetsInfo): String = JSON.encodeToString(assets)
    @TypeConverter fun toConfig(text: String): ConfigInfo = JSON.decodeFromString(text)
    @TypeConverter fun fromConfig(config: ConfigInfo): String = JSON.encodeToString(config)
    @TypeConverter fun toIntList(text: String): List<Int> = if (text.isBlank()) emptyList() else JSON.decodeFromString(text)
    @TypeConverter fun fromIntList(list: List<Int>): String = JSON.encodeToString(list)
    @TypeConverter fun toLicenseFlags(code: Int): EnumSet<ELicenseFlags> = ELicenseFlags.from(code)
    @TypeConverter fun fromLicenseFlags(flags: EnumSet<ELicenseFlags>): Int = ELicenseFlags.code(flags)
    @TypeConverter fun toLicenseType(code: Int): ELicenseType = ELicenseType.from(code)
    @TypeConverter fun fromLicenseType(type: ELicenseType): Int = type.code()

    companion object {
        /** GameNative wrote these with kotlinx's defaults; a field it wrote that droidtop dropped is skipped. */
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/*
 * Which apps the account owns (GameNative's SteamAppDao.OWNED_APPS_WHERE): an
 * app with a package, a known type, not Spacewar, and a licence that has not
 * expired, either its own package's or one of its DLC's (a free-to-start game
 * whose purchase is a DLC). Bit 8 of the licence flags is Expired.
 * [OWNED_BY_LICENCE] is the same without the known type, for the sync's
 * summary, which counts the apps whose product info never came.
 */
private const val OWNED_APPS_WHERE = "WHERE app.type != 0 AND "

private const val OWNED_BY_LICENCE =
    "app.id != ${SteamIds.SPACEWAR} " +
        "AND app.package_id != ${SteamIds.INVALID_PKG_ID} " +
        "AND (" +
        "  EXISTS (SELECT 1 FROM steam_license AS license WHERE license.packageId = app.package_id AND (license.license_flags & 8) = 0) " +
        "  OR EXISTS (" +
        "    SELECT 1 FROM steam_app AS dlc INNER JOIN steam_license AS license ON dlc.package_id = license.packageId " +
        "    WHERE dlc.dlc_for_app_id = app.id AND (license.license_flags & 8) = 0" +
        "  )" +
        ") "

/** One line of [SteamAppDao.ownedCounts]: [type] is an [AppType] code. */
data class SteamAppCount(val type: Int, val namesBaseGame: Boolean, val count: Int)

@Dao
interface SteamAppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: SteamApp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<SteamApp>)

    @Update
    suspend fun update(app: SteamApp)

    @Query("SELECT * FROM steam_app WHERE id = :appId")
    suspend fun find(appId: Int): SteamApp?

    /** Every app the account owns whose type is one a person plays ([SteamLibrarySync.PLAYABLE_TYPES] codes) and that is not DLC of another app. */
    @Query("SELECT * FROM steam_app AS app " + OWNED_APPS_WHERE + OWNED_BY_LICENCE + "AND app.type IN (:types) AND app.dlc_for_app_id = ${SteamIds.INVALID_APP_ID} ORDER BY LOWER(app.name)")
    suspend fun owned(types: List<Int>): List<SteamApp>

    /** How many owned apps there are of each type, with and without a base game (`dlcforappid`); the sync's summary. */
    @Query(
        "SELECT app.type AS type, (app.dlc_for_app_id != ${SteamIds.INVALID_APP_ID}) AS namesBaseGame, COUNT(*) AS count " +
            "FROM steam_app AS app WHERE " + OWNED_BY_LICENCE + "GROUP BY app.type, namesBaseGame",
    )
    suspend fun ownedCounts(): List<SteamAppCount>

    /** DLC apps of [appId] with depots of their own that a licence grants (GameNative's findDownloadableDLCApps). */
    @Query(
        "SELECT * FROM steam_app AS app WHERE dlc_for_app_id = :appId AND depots <> '{}' AND EXISTS (" +
            " SELECT * FROM steam_license AS license WHERE license.license_type <> 0 AND " +
            " REPLACE(REPLACE(license.app_ids, '[', ','), ']', ',') LIKE ('%,' || app.id || ',%'))",
    )
    suspend fun ownedDlcWithDepots(appId: Int): List<SteamApp>

    @Query("SELECT id FROM steam_app WHERE id IN (:appIds)")
    suspend fun knownIds(appIds: List<Int>): List<Int>

    @Query("DELETE FROM steam_app")
    suspend fun deleteAll()
}

@Dao
interface SteamLicenseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(licenses: List<SteamLicense>)

    @Query("SELECT * FROM steam_license")
    suspend fun all(): List<SteamLicense>

    @Query("SELECT * FROM steam_license WHERE packageId = :packageId")
    suspend fun find(packageId: Int): SteamLicense?

    @Query("SELECT * FROM steam_license WHERE packageId IN (:packageIds)")
    suspend fun findAll(packageIds: List<Int>): List<SteamLicense>

    @Query("UPDATE steam_license SET app_ids = :appIds, depot_ids = :depotIds WHERE packageId = :packageId")
    suspend fun setContents(packageId: Int, appIds: List<Int>, depotIds: List<Int>)

    @Query("DELETE FROM steam_license WHERE packageId NOT IN (:keep)")
    suspend fun deleteAllBut(keep: List<Int>)

    @Query("DELETE FROM steam_license")
    suspend fun deleteAll()
}

@Dao
interface CachedLicenseDao {
    @Insert
    suspend fun insertAll(licenses: List<CachedLicense>)

    @Query("SELECT * FROM cached_license")
    suspend fun all(): List<CachedLicense>

    @Query("DELETE FROM cached_license")
    suspend fun deleteAll()
}

@Dao
interface AppInfoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(info: AppInfo)

    @Query("SELECT * FROM app_info WHERE id = :appId")
    suspend fun find(appId: Int): AppInfo?

    @Query("SELECT * FROM app_info")
    suspend fun all(): List<AppInfo>

    @Query("DELETE FROM app_info WHERE id IN (:appIds)")
    suspend fun delete(appIds: List<Int>)
}

@Database(
    entities = [SteamApp::class, SteamLicense::class, CachedLicense::class, AppInfo::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(SteamConverters::class)
abstract class SteamDatabase : RoomDatabase() {
    abstract fun apps(): SteamAppDao
    abstract fun licenses(): SteamLicenseDao
    abstract fun cachedLicenses(): CachedLicenseDao
    abstract fun installs(): AppInfoDao

    companion object {
        /**
         * Its own file beside stores.db: Steam's product info is rewritten
         * in bulk on every sync, and a separate file keeps that churn out of
         * the other stores' change stamp. Made fresh, so no migration of
         * stores.db was needed to add it.
         */
        const val NAME = "steam.db"

        /** GameNative's Steam tables, brought across when this database is first made. */
        private val GAMENATIVE_TABLES = listOf("steam_license", "cached_license", "app_info", "steam_app")

        /**
         * GameNative read a missing `dlcforappid` as 0 (KeyValueUtils'
         * `asInteger()` default), droidtop as [SteamIds.INVALID_APP_ID]. A row
         * brought across keeps GameNative's reading until its product info
         * changes, so every game GameNative had read looked like DLC of app 0
         * and the library left it out: the owner's library listed 725 games
         * where Steam counts 1,245 (Droidtop/tracker#360). App 0 is no game,
         * so 0 always means "none".
         */
        internal const val NO_BASE_GAME_FROM_GAMENATIVE =
            "UPDATE steam_app SET dlc_for_app_id = ${SteamIds.INVALID_APP_ID} WHERE dlc_for_app_id = 0"

        /** Version 2: [NO_BASE_GAME_FROM_GAMENATIVE] over the rows a version 1 database brought across. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(NO_BASE_GAME_FROM_GAMENATIVE)
            }
        }

        @Volatile
        private var instance: SteamDatabase? = null

        fun get(context: Context): SteamDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, SteamDatabase::class.java, NAME)
                    .addMigrations(MIGRATION_1_2)
                    .addCallback(
                        object : RoomDatabase.Callback() {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                GameNativeImport.run(context.applicationContext, db, GAMENATIVE_TABLES)
                                db.execSQL(NO_BASE_GAME_FROM_GAMENATIVE)
                            }
                        },
                    )
                    .build()
                    .also { instance = it }
            }

        /** The files whose modification times say Steam's rows changed. */
        fun files(context: Context): List<File> {
            val db = context.getDatabasePath(NAME)
            return listOf(db, File(db.path + "-wal"))
        }
    }
}
