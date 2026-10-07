package dev.droidtop.stores.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Brings the store rows GameNative's database already holds into droidtop's
 * own ([StoresDatabase]), once, when that database is first created, so an
 * update keeps every game a store had listed and, above all, what is
 * installed and where (docs/SPEC.md 7g, "Stores").
 *
 * The tables and columns have the same names on both sides, so a row is
 * copied column by column for the columns both tables have; a column only
 * GameNative had (its play-time counters, its app type) is left behind, and a
 * column only droidtop has takes its default. Read-only on GameNative's side.
 * The sign-ins need nothing: each store keeps its sign-in in the same file it
 * always did.
 */
internal object GameNativeImport {
    private const val TAG = "droidtop.StoresImport"

    /** GameNative's database file name (app.gamenative.db.DATABASE_NAME). */
    private const val GAMENATIVE_DATABASE = "pluvia.db"

    private val TABLES = listOf("gog_games", "epic_games", "amazon_games", "itch_games")

    fun run(context: Context, into: SupportSQLiteDatabase) {
        val source = context.getDatabasePath(GAMENATIVE_DATABASE)
        if (!source.isFile) return
        val old = try {
            SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open GameNative's store rows to bring them across", e)
            return
        }
        old.use { gamenative ->
            for (table in TABLES) {
                try {
                    val copied = copyTable(gamenative, into, table)
                    if (copied > 0) Log.i(TAG, "Brought $copied rows of $table across from GameNative")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not bring $table across from GameNative", e)
                }
            }
        }
    }

    private fun copyTable(from: SQLiteDatabase, into: SupportSQLiteDatabase, table: String): Int {
        val theirs = from.rawQuery("PRAGMA table_info(`$table`)", null).use { it.names() }
        if (theirs.isEmpty()) return 0
        val ours = into.query("PRAGMA table_info(`$table`)").use { it.names() }
        val shared = ours.filter { it in theirs }
        if (shared.isEmpty()) return 0
        var copied = 0
        val columns = shared.joinToString(",") { "`$it`" }
        from.rawQuery("SELECT $columns FROM `$table`", null).use { rows ->
            while (rows.moveToNext()) {
                val values = ContentValues()
                shared.forEachIndexed { index, name ->
                    when (rows.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> values.putNull(name)
                        Cursor.FIELD_TYPE_INTEGER -> values.put(name, rows.getLong(index))
                        Cursor.FIELD_TYPE_FLOAT -> values.put(name, rows.getDouble(index))
                        Cursor.FIELD_TYPE_BLOB -> values.put(name, rows.getBlob(index))
                        else -> values.put(name, rows.getString(index))
                    }
                }
                if (into.insert(table, SQLiteDatabase.CONFLICT_IGNORE, values) != -1L) copied++
            }
        }
        return copied
    }

    /** The column names of a `PRAGMA table_info` result. */
    private fun Cursor.names(): List<String> {
        val nameColumn = getColumnIndex("name")
        if (nameColumn < 0) return emptyList()
        return buildList { while (moveToNext()) add(getString(nameColumn)) }
    }
}
