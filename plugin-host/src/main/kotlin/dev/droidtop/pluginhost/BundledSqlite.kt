package dev.droidtop.pluginhost

import java.io.Closeable
import java.io.File

/**
 * A plugin's database: droidtop's own SQLite (docs/plugin-api.md 5.3, "Preferences and SQLite are droidtop's",
 * Droidtop/tracker#741), the same in every tier and on every Android. A Kotlin plugin gets one from
 * [PluginContext.database]; a Flutter plugin's `sqflite` is answered over it ([SqfliteCalls]).
 *
 * Values are SQLite's own: `Long`, `Double`, `String`, `ByteArray` and null. Arguments may also be any other number,
 * a `Boolean` (1 or 0) or a list of byte values (a blob), as `sqflite` sends them.
 */
interface PluginDatabase : Closeable {
    val path: String

    /** Runs the first statement of [sql], reading past any row it answers (a PRAGMA that names its new value). */
    fun execute(sql: String, args: List<Any?> = emptyList())

    /** The new row's id, or -1 when nothing was inserted (Android's `executeInsert`). */
    fun insert(sql: String, args: List<Any?> = emptyList()): Long

    /** How many rows the statement changed. */
    fun update(sql: String, args: List<Any?> = emptyList()): Int

    /** A query, read [PluginCursor.next] at a time. */
    fun cursor(sql: String, args: List<Any?> = emptyList()): PluginCursor

    /** Every row of a query. */
    fun query(sql: String, args: List<Any?> = emptyList()): PluginRows = cursor(sql, args).use { PluginRows(it.columns, it.next(null)) }

    /** Whether SQLite has a transaction open (false again after SQLite rolled one back by itself). */
    val inTransaction: Boolean
}

class PluginRows(val columns: List<String>, val rows: List<List<Any?>>)

interface PluginCursor : Closeable {
    val columns: List<String>

    /** Up to [limit] more rows (all of them when null). */
    fun next(limit: Int?): List<List<Any?>>

    /** Whether another row follows the ones read so far. */
    val hasMore: Boolean
}

/** A failure SQLite reported, worded the way Android words it ("... (code 2067 SQLITE_CONSTRAINT_UNIQUE)"), which
 * `sqflite`'s Dart side reads the result code from. */
class SqliteException(val code: Int, val sqliteMessage: String?) : RuntimeException(wording(code, sqliteMessage)) {
    /** SQLite's primary result code: the low byte of the extended one. */
    val primaryCode: Int get() = code and 0xff

    companion object {
        fun wording(code: Int, message: String?): String {
            val name = names[code] ?: names[code and 0xff]
            return "${message ?: "unknown error"} (code $code${name?.let { " $it" }.orEmpty()})"
        }

        private val names: Map<Int, String> = mapOf(
            1 to "SQLITE_ERROR", 2 to "SQLITE_INTERNAL", 3 to "SQLITE_PERM", 4 to "SQLITE_ABORT", 5 to "SQLITE_BUSY",
            6 to "SQLITE_LOCKED", 7 to "SQLITE_NOMEM", 8 to "SQLITE_READONLY", 9 to "SQLITE_INTERRUPT", 10 to "SQLITE_IOERR",
            11 to "SQLITE_CORRUPT", 12 to "SQLITE_NOTFOUND", 13 to "SQLITE_FULL", 14 to "SQLITE_CANTOPEN",
            15 to "SQLITE_PROTOCOL", 16 to "SQLITE_EMPTY", 17 to "SQLITE_SCHEMA", 18 to "SQLITE_TOOBIG",
            19 to "SQLITE_CONSTRAINT", 20 to "SQLITE_MISMATCH", 21 to "SQLITE_MISUSE", 22 to "SQLITE_NOLFS",
            23 to "SQLITE_AUTH", 24 to "SQLITE_FORMAT", 25 to "SQLITE_RANGE", 26 to "SQLITE_NOTADB",
            261 to "SQLITE_BUSY_RECOVERY", 517 to "SQLITE_BUSY_SNAPSHOT", 773 to "SQLITE_BUSY_TIMEOUT",
            262 to "SQLITE_LOCKED_SHAREDCACHE", 264 to "SQLITE_READONLY_RECOVERY", 520 to "SQLITE_READONLY_CANTLOCK",
            776 to "SQLITE_READONLY_ROLLBACK", 1032 to "SQLITE_READONLY_DBMOVED", 270 to "SQLITE_CANTOPEN_NOTEMPDIR",
            526 to "SQLITE_CANTOPEN_ISDIR", 782 to "SQLITE_CANTOPEN_FULLPATH", 267 to "SQLITE_CORRUPT_VTAB",
            275 to "SQLITE_CONSTRAINT_CHECK", 531 to "SQLITE_CONSTRAINT_COMMITHOOK", 787 to "SQLITE_CONSTRAINT_FOREIGNKEY",
            1043 to "SQLITE_CONSTRAINT_FUNCTION", 1299 to "SQLITE_CONSTRAINT_NOTNULL", 1555 to "SQLITE_CONSTRAINT_PRIMARYKEY",
            1811 to "SQLITE_CONSTRAINT_TRIGGER", 2067 to "SQLITE_CONSTRAINT_UNIQUE", 2323 to "SQLITE_CONSTRAINT_VTAB",
            2579 to "SQLITE_CONSTRAINT_ROWID", 3091 to "SQLITE_CONSTRAINT_DATATYPE", 516 to "SQLITE_ABORT_ROLLBACK",
            266 to "SQLITE_IOERR_READ", 522 to "SQLITE_IOERR_SHORT_READ", 778 to "SQLITE_IOERR_WRITE",
            1034 to "SQLITE_IOERR_FSYNC", 3338 to "SQLITE_IOERR_ACCESS", 4874 to "SQLITE_IOERR_SHMOPEN",
            5130 to "SQLITE_IOERR_SHMSIZE", 5386 to "SQLITE_IOERR_SHMLOCK", 5642 to "SQLITE_IOERR_SHMMAP",
        )
    }
}

/** The JNI half (`native/src/bundled_sqlite.c`); a failure throws [SqliteException]. */
internal object SqliteNative {
    init {
        System.loadLibrary("droidtoppy")
    }

    const val ROW = 100
    const val INTEGER = 1
    const val FLOAT = 2
    const val TEXT = 3
    const val BLOB = 4

    @JvmStatic external fun open(path: String, readOnly: Boolean): Long
    @JvmStatic external fun close(db: Long)
    @JvmStatic external fun prepare(db: Long, sql: String): Long
    @JvmStatic external fun finalize(stmt: Long)
    @JvmStatic external fun bindNull(db: Long, stmt: Long, index: Int)
    @JvmStatic external fun bindLong(db: Long, stmt: Long, index: Int, value: Long)
    @JvmStatic external fun bindDouble(db: Long, stmt: Long, index: Int, value: Double)
    @JvmStatic external fun bindText(db: Long, stmt: Long, index: Int, value: String)
    @JvmStatic external fun bindBlob(db: Long, stmt: Long, index: Int, value: ByteArray)
    @JvmStatic external fun step(db: Long, stmt: Long): Int
    @JvmStatic external fun columnCount(stmt: Long): Int
    @JvmStatic external fun columnName(stmt: Long, index: Int): String?
    @JvmStatic external fun columnType(stmt: Long, index: Int): Int
    @JvmStatic external fun columnLong(stmt: Long, index: Int): Long
    @JvmStatic external fun columnDouble(stmt: Long, index: Int): Double
    @JvmStatic external fun columnText(stmt: Long, index: Int): String
    @JvmStatic external fun columnBlob(stmt: Long, index: Int): ByteArray
    @JvmStatic external fun changes(db: Long): Int
    @JvmStatic external fun lastInsertRowid(db: Long): Long
    @JvmStatic external fun autocommit(db: Long): Boolean
    @JvmStatic external fun version(): String
}

/** [PluginDatabase] over the bundled SQLite. One connection, used by one thread at a time (sqflite's own model). */
class BundledSqlite private constructor(override val path: String, private var db: Long) : PluginDatabase {
    private fun handle(): Long = db.takeIf { it != 0L } ?: throw IllegalStateException("This database has already been closed")

    /** Prepares the first statement of [sql] with [args] bound; null for text with no statement. */
    private fun statement(sql: String, args: List<Any?>): Long? {
        val db = handle()
        val stmt = SqliteNative.prepare(db, sql).takeIf { it != 0L } ?: return null
        try {
            args.forEachIndexed { i, arg -> bind(db, stmt, i + 1, arg) }
        } catch (t: Throwable) {
            SqliteNative.finalize(stmt)
            throw t
        }
        return stmt
    }

    private fun <T> run(sql: String, args: List<Any?>, answer: (db: Long) -> T): T {
        val stmt = statement(sql, args)
        try {
            if (stmt != null) while (SqliteNative.step(db, stmt) == SqliteNative.ROW) Unit
        } finally {
            if (stmt != null) SqliteNative.finalize(stmt)
        }
        return answer(db)
    }

    override fun execute(sql: String, args: List<Any?>) = run(sql, args) {}

    override fun insert(sql: String, args: List<Any?>): Long =
        run(sql, args) { db -> if (SqliteNative.changes(db) > 0) SqliteNative.lastInsertRowid(db) else -1L }

    override fun update(sql: String, args: List<Any?>): Int = run(sql, args) { db -> SqliteNative.changes(db) }

    override fun cursor(sql: String, args: List<Any?>): PluginCursor = Cursor(handle(), statement(sql, args))

    override val inTransaction: Boolean get() = !SqliteNative.autocommit(handle())

    override fun close() {
        val open = db
        db = 0
        if (open != 0L) SqliteNative.close(open)
    }

    /** Reads one row ahead, so [hasMore] is known without losing a row. */
    private class Cursor(private val db: Long, private var stmt: Long?) : PluginCursor {
        override val columns: List<String> =
            stmt?.let { s -> (0 until SqliteNative.columnCount(s)).map { SqliteNative.columnName(s, it).orEmpty() } }.orEmpty()
        private var ahead: List<Any?>? = read()

        private fun read(): List<Any?>? {
            val s = stmt ?: return null
            if (SqliteNative.step(db, s) != SqliteNative.ROW) return null
            return List(columns.size) { i ->
                when (SqliteNative.columnType(s, i)) {
                    SqliteNative.INTEGER -> SqliteNative.columnLong(s, i)
                    SqliteNative.FLOAT -> SqliteNative.columnDouble(s, i)
                    SqliteNative.TEXT -> SqliteNative.columnText(s, i)
                    SqliteNative.BLOB -> SqliteNative.columnBlob(s, i)
                    else -> null
                }
            }
        }

        override fun next(limit: Int?): List<List<Any?>> {
            val out = ArrayList<List<Any?>>()
            while (limit == null || out.size < limit) {
                val row = ahead ?: break
                out += row
                ahead = read()
            }
            return out
        }

        override val hasMore: Boolean get() = ahead != null

        override fun close() {
            stmt?.let { SqliteNative.finalize(it) }
            stmt = null
            ahead = null
        }
    }

    companion object {
        /** Opens (or creates, unless [readOnly]) the database at [path]; ":memory:" is a database in memory. */
        fun open(path: String, readOnly: Boolean = false): BundledSqlite = BundledSqlite(path, SqliteNative.open(path, readOnly))

        /** Removes the database at [path] with its journal, WAL and shared-memory files, as Android's `deleteDatabase` does. */
        fun delete(path: String): Boolean {
            val file = File(path)
            var deleted = file.delete()
            for (suffix in listOf("-journal", "-shm", "-wal")) deleted = File(path + suffix).delete() or deleted
            file.parentFile?.listFiles { _, name -> name.startsWith(file.name + "-mj") }?.forEach { deleted = it.delete() or deleted }
            return deleted
        }

        /** The SQLite this build carries, as SQLite names it ("3.54.1"). */
        fun version(): String = SqliteNative.version()

        /**
         * Binds one argument the way `sqflite` sends them: a list of byte values is a blob, a Boolean is 1 or 0, any
         * other number is an integer or a double.
         */
        internal fun bind(db: Long, stmt: Long, index: Int, arg: Any?) {
            when (val value = blobOf(arg)) {
                null -> SqliteNative.bindNull(db, stmt, index)
                is ByteArray -> SqliteNative.bindBlob(db, stmt, index, value)
                is Double, is Float -> SqliteNative.bindDouble(db, stmt, index, (value as Number).toDouble())
                is Number -> SqliteNative.bindLong(db, stmt, index, value.toLong())
                is Boolean -> SqliteNative.bindLong(db, stmt, index, if (value) 1 else 0)
                is String -> SqliteNative.bindText(db, stmt, index, value)
                else -> throw IllegalArgumentException(
                    "Could not bind $value from index ${index - 1}: Supported types are null, byte[], double, long, boolean and String",
                )
            }
        }

        internal fun blobOf(arg: Any?): Any? =
            if (arg is List<*>) ByteArray(arg.size) { (arg[it] as Number).toByte() } else arg
    }
}
