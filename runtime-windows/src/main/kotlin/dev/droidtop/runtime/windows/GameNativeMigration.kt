package dev.droidtop.runtime.windows

import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import dev.droidtop.runtime.RootProcess
import java.io.File

/**
 * Migrates an existing upstream GameNative install into droidtop
 * (directed 2026-09-01, "GameNative migration flow").
 *
 * droidtop compiles the whole gamenative tree, so its Room database and
 * DataStore file are the SAME shapes upstream writes -- which is what
 * makes this a data migration rather than a conversion. What comes over
 * is the expensive part of a real install: the Steam app catalog and
 * licenses, the GOG/Epic/Amazon libraries, install records, play
 * history, and cached file hashes, plus the preferences (which is where
 * the signed-in session lives, so sign-in often survives too -- and if
 * it doesn't, signing in again is cheap, unlike re-downloading games).
 *
 * ROOT IS REQUIRED and this is deliberately OPTIONAL: another app's data
 * directory is unreadable otherwise. Handheld features never *depend* on
 * root (standing rule) -- signing in normally remains the universal
 * path; this is a shortcut for rooted devices.
 *
 * Safety, in order:
 * - The upstream database is staged into droidtop's own cache first and
 *   inspected there, so nothing is decided from an unreadable file.
 * - A schema NEWER than this build's is refused outright: Room can
 *   migrate an older database forward (its auto-migration chain), never
 *   a newer one backward, and forcing it would corrupt real data.
 * - droidtop's existing database is backed up before it is replaced.
 * - Absolute install paths that point into GameNative's own data
 *   directory are rewritten to droidtop's, the same rewrite the fork
 *   already performs for its own GOG/Amazon path migration.
 * - Files written by root are handed back to droidtop's uid, or the app
 *   cannot read what it just imported.
 */
object GameNativeMigration {

    const val UPSTREAM_PACKAGE = "app.gamenative"
    private const val DATABASE_NAME = "pluvia.db"
    private const val DATASTORE_NAME = "PluviaPreferences.preferences_pb"

    /** The Room schema version this build of droidtop can open (PluviaDatabase's own). */
    const val SUPPORTED_SCHEMA_VERSION = 25

    data class Availability(
        val installed: Boolean,
        val rootAvailable: Boolean,
        val stagingError: String? = null,
        val schemaVersion: Int?,
        val steamApps: Int,
        val installedGames: Int,
        val gogGames: Int,
        val epicGames: Int,
        val amazonGames: Int,
    ) {
        val schemaTooNew: Boolean get() = schemaVersion != null && schemaVersion > SUPPORTED_SCHEMA_VERSION
        val migratable: Boolean get() = installed && rootAvailable && schemaVersion != null && !schemaTooNew

        fun describe(): String = when {
            !installed -> "GameNative isn't installed, so there is nothing to migrate."
            // Confirmed on a real rooted device: the first `su` a new app
            // asks for is a PROMPT in the root manager, and an unattended
            // request simply gets denied. "Needs root" was true and
            // useless; this says what to actually do.
            !rootAvailable ->
                "GameNative is installed, but its data could not be read" +
                    (stagingError?.let { ": $it" } ?: "") +
                    ". If droidtop has not been granted root, approve it in your root manager " +
                    "(Magisk/KernelSU/APatch) and run this again."
            schemaVersion == null -> "GameNative is installed but its database couldn't be read."
            schemaTooNew ->
                "That GameNative install uses database version $schemaVersion; this droidtop build " +
                    "understands $SUPPORTED_SCHEMA_VERSION. Update droidtop first -- importing a newer " +
                    "database would corrupt it."
            else ->
                "Ready to import: $steamApps Steam titles, $installedGames installed, " +
                    "$gogGames GOG, $epicGames Epic, $amazonGames Amazon (database v$schemaVersion)."
        }
    }

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(UPSTREAM_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * Stages a copy of the upstream database and reports what it holds.
     * Nothing in droidtop is touched.
     */
    suspend fun probe(context: Context): Availability {
        val installed = isInstalled(context)
        if (!installed) return Availability(installed = false, rootAvailable = false, schemaVersion = null, steamApps = 0, installedGames = 0, gogGames = 0, epicGames = 0, amazonGames = 0)
        val staged = stageUpstreamFiles(context)
        // rootAvailable = false covers both "no su on this device" and
        // "su refused us", which read identically from here; the message
        // above covers both cases honestly rather than guessing.
        if (staged == null) return Availability(installed = true, rootAvailable = false, stagingError = lastStagingError, schemaVersion = null, steamApps = 0, installedGames = 0, gogGames = 0, epicGames = 0, amazonGames = 0)
        val database = staged.database
        if (!database.isFile) return Availability(installed = true, rootAvailable = true, schemaVersion = null, steamApps = 0, installedGames = 0, gogGames = 0, epicGames = 0, amazonGames = 0)
        return runCatching {
            SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                Availability(
                    installed = true,
                    rootAvailable = true,
                    schemaVersion = db.version,
                    steamApps = db.count("steam_app"),
                    installedGames = db.count("app_info"),
                    gogGames = db.count("gog_games"),
                    epicGames = db.count("epic_games"),
                    amazonGames = db.count("amazon_games"),
                )
            }
        }.getOrElse { Availability(installed = true, rootAvailable = true, schemaVersion = null, steamApps = 0, installedGames = 0, gogGames = 0, epicGames = 0, amazonGames = 0) }
    }

    /**
     * Performs the migration. droidtop must be restarted afterwards: the
     * Room and DataStore instances already open in this process still
     * hold the files being replaced.
     */
    suspend fun migrate(context: Context, includePreferences: Boolean = true): String {
        val availability = probe(context)
        if (!availability.migratable) return availability.describe()

        val staged = stageUpstreamFiles(context) ?: return "Couldn't read GameNative's data."
        rewriteInstallPaths(context, staged.database)

        val databasesDir = File(context.dataDir, "databases").apply { mkdirs() }
        val target = File(databasesDir, DATABASE_NAME)
        if (target.isFile) {
            // Keep exactly one backup, named so it is obvious what it is.
            target.copyTo(File(databasesDir, "$DATABASE_NAME.pre-gamenative-import"), overwrite = true)
        }
        // The journal files belong to the OLD database; leaving them
        // beside a replaced one is how a database gets corrupted.
        listOf("$DATABASE_NAME-wal", "$DATABASE_NAME-shm").forEach { File(databasesDir, it).delete() }
        staged.database.copyTo(target, overwrite = true)

        var preferencesImported = false
        if (includePreferences && staged.preferences?.isFile == true) {
            val datastoreDir = File(context.filesDir, "datastore").apply { mkdirs() }
            staged.preferences.copyTo(File(datastoreDir, DATASTORE_NAME), overwrite = true)
            preferencesImported = true
        }
        staged.root.deleteRecursively()

        return buildString {
            append("Imported ${availability.steamApps} Steam titles, ${availability.gogGames} GOG, ")
            append("${availability.epicGames} Epic, ${availability.amazonGames} Amazon, ")
            append("${availability.installedGames} install records")
            append(if (preferencesImported) " and GameNative's settings (including its sign-in). " else ". ")
            append("Restart droidtop to use them. Game FILES were not moved: titles installed to shared ")
            append("storage are found where they already are, and anything inside GameNative's private ")
            append("storage still needs re-downloading (or moving by hand).")
        }
    }

    private class Staged(val root: File, val database: File, val preferences: File?)

    /**
     * Why the last staging attempt failed, so the UI can say something
     * true instead of assuming a root refusal.
     */
    @Volatile
    private var lastStagingError: String? = null

    /**
     * Copies the upstream files into droidtop's cache with root.
     *
     * `cat` into a file droidtop CREATED FIRST, rather than `cp`: the
     * redirect truncates the existing file instead of making a new one,
     * so it keeps droidtop's ownership and SELinux label and nothing
     * has to be relabelled afterwards.
     *
     * Success is judged by the RESULT, never by su's exit code.
     * Confirmed on a real device: Magisk logs policy ALLOW and the copy
     * genuinely works, while its own logging helper (`content call ...
     * --method log`) crashes and su reports that crash as its exit
     * status. Believing the messenger threw away a perfectly good
     * database and then blamed a root refusal that never happened.
     */
    private suspend fun stageUpstreamFiles(context: Context): Staged? {
        val stagingRoot = File(context.cacheDir, "gamenative-migration")
        stagingRoot.deleteRecursively()
        stagingRoot.mkdirs()

        val database = File(stagingRoot, DATABASE_NAME)
        // Created by droidtop so the root redirect below inherits this
        // file's ownership rather than making a root-owned one.
        runCatching { database.createNewFile() }
        val upstreamData = "/data/data/$UPSTREAM_PACKAGE"
        // Checkpoint first: an upstream database with an unmerged
        // write-ahead log would otherwise arrive missing its newest
        // rows. Failure is not fatal -- a database with no WAL is
        // already complete.
        RootProcess.run(
            "sh", "-c",
            "sqlite3 $upstreamData/databases/$DATABASE_NAME 'PRAGMA wal_checkpoint(TRUNCATE);' 2>/dev/null || true",
        )
        val copied = RootProcess.run(
            "sh", "-c",
            "cat $upstreamData/databases/$DATABASE_NAME > ${database.absolutePath}",
        )
        // SQLite's own file magic: a database that arrived truncated or
        // as an error message is worse than one that never arrived,
        // because everything downstream would treat it as real.
        val looksLikeDatabase = database.isFile && database.length() > 0 && runCatching {
            database.inputStream().use { stream ->
                val header = ByteArray(15)
                stream.read(header) == 15 && String(header, Charsets.US_ASCII) == "SQLite format 3"
            }
        }.getOrDefault(false)
        if (!looksLikeDatabase) {
            android.util.Log.w(
                "droidtop.Migration",
                "Staging failed: exit=${copied.exitCode} size=${database.length()} stderr=${copied.stderr.take(200)}",
            )
            lastStagingError = copied.stderr.lineSequence().firstOrNull { it.isNotBlank() }
                ?: "su exited ${copied.exitCode}"
            stagingRoot.deleteRecursively()
            return null
        }

        val preferences = File(stagingRoot, DATASTORE_NAME)
        RootProcess.run(
            "sh", "-c",
            "cat $upstreamData/files/datastore/$DATASTORE_NAME > ${preferences.absolutePath} 2>/dev/null || true",
        )
        return Staged(stagingRoot, database, preferences.takeIf { it.isFile && it.length() > 0 })
    }

    /**
     * Rewrites install paths that point into GameNative's data directory
     * so they address droidtop's instead. Paths on shared storage are
     * left exactly as they are -- both apps can read those, which is why
     * a library installed to a games folder survives the move for free.
     */
    private fun rewriteInstallPaths(context: Context, database: File) {
        val ourData = context.dataDir.absolutePath
        val theirPaths = listOf("/data/data/$UPSTREAM_PACKAGE", "/data/user/0/$UPSTREAM_PACKAGE")
        runCatching {
            SQLiteDatabase.openDatabase(database.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                val columns = listOf(
                    "gog_games" to "install_path",
                    "epic_games" to "install_path",
                    "amazon_games" to "install_path",
                    "app_info" to "custom_install_path",
                )
                columns.forEach { (table, column) ->
                    theirPaths.forEach { theirs ->
                        runCatching {
                            db.execSQL(
                                "UPDATE $table SET $column = REPLACE($column, ?, ?) WHERE $column LIKE ?",
                                arrayOf(theirs, ourData, "$theirs%"),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun SQLiteDatabase.count(table: String): Int = runCatching {
        rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }.getOrDefault(0)
}
