package dev.droidtop.pluginhost

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * `sqflite`'s method channel (`com.tekartik.sqflite`), answered by droidtop over its own SQLite ([BundledSqlite]) in
 * every plugin process (docs/plugin-api.md 5.3, "Preferences and SQLite are droidtop's", Droidtop/tracker#741). The
 * package's Android half would open Android's own SQLite, which an isolated process cannot, and what a plugin gets must
 * not depend on the Android it runs on. Its Dart half is unchanged: every method, argument, reply and error below is
 * the Android half's (tekartik/sqflite `sqflite_android`, `SqflitePlugin` and `Database`, read at 52dbf98), so a plugin
 * behaves as it does on Android. Where SQLite itself now answers what Android's session layer hid (whether a
 * transaction is still open), the bookkeeping that worked around that layer is gone.
 *
 * Every database call runs on one worker thread, in order, as `sqflite`'s default single thread does; calls of a
 * transaction run as they come and other calls wait until it ends, by the transaction id the Dart side sends.
 */
class SqfliteCalls(
    private val databasesPath: String,
    private val open: (path: String, readOnly: Boolean) -> PluginDatabase = { path, readOnly -> BundledSqlite.open(path, readOnly) },
    private val worker: Executor = Executors.newSingleThreadExecutor { Thread(it, "droidtop-sqflite").apply { isDaemon = true } },
) {
    /** How a call is answered: `MethodChannel.Result` in the engine, a recorder in tests. */
    interface Reply {
        fun success(value: Any?)
        fun error(code: String, message: String?, data: Any?)
        fun notImplemented()
    }

    private class Database(val id: Int, val path: String, val singleInstance: Boolean, val connection: PluginDatabase) {
        var depth = 0
        var lastTransactionId = 0
        var currentTransactionId: Int? = null
        var lastCursorId = 0
        /** Open paged queries, each with its page size. */
        val cursors = HashMap<Int, Pair<PluginCursor, Int>>()
        val queued = ArrayList<() -> Unit>()
    }

    private val lock = Any()
    private val databases = HashMap<Int, Database>()
    private val singleInstances = HashMap<String, Int>()
    private var lastDatabaseId = 0
    private var logLevel = 0

    /** One call from the Dart side: [method] with its [arguments] map, answered on [reply]. */
    fun call(method: String, arguments: Any?, reply: Reply) {
        val args = (arguments as? Map<*, *>).orEmpty()
        when (method) {
            // What a plugin is told is droidtop's own Android, never the device's (docs/plugin-api.md 5.3).
            "getPlatformVersion" -> reply.success("Android ${HandledAndroid.RELEASE}")
            "getDatabasesPath" -> reply.success(databasesPath)
            "openDatabase" -> openDatabase(args, reply)
            "closeDatabase" -> closeDatabase(args, reply)
            "deleteDatabase" -> deleteDatabase(args, reply)
            "databaseExists" -> reply.success(runCatching { File(args["path"] as String).exists() }.getOrDefault(false))
            "execute" -> onDatabase(args, reply) { db -> wrap(db, args) { execute(db, Op(args, reply)) } }
            "insert" -> onDatabase(args, reply) { db -> wrap(db, args) { insert(db, Op(args, reply)) } }
            "update" -> onDatabase(args, reply) { db -> wrap(db, args) { update(db, Op(args, reply)) } }
            "query" -> onDatabase(args, reply) { db -> wrap(db, args) { query(db, Op(args, reply)) } }
            "queryCursorNext" -> onDatabase(args, reply) { db -> wrap(db, args) { cursorNext(db, Op(args, reply)) } }
            "batch" -> onDatabase(args, reply) { db -> batch(db, args, reply) }
            // Android's SQLite collates by a locale; droidtop's has SQLite's own collations only. Accepted, not honoured
            // (docs/plugin-api.md 5.3).
            "androidSetLocale" -> onDatabase(args, reply) { reply.success(null) }
            "options" -> {
                (args["logLevel"] as? Number)?.let { logLevel = it.toInt() }
                reply.success(null)
            }
            "debug" -> reply.success(debugInfo(args["cmd"] as? String))
            "debugMode" -> {
                logLevel = if (arguments == true) 1 else 0
                reply.success(null)
            }
            else -> reply.notImplemented()
        }
    }

    // ---------------------------------------------------------------- opening and closing

    private fun openDatabase(args: Map<*, *>, reply: Reply) {
        val path = args["path"] as String?
        val inMemory = path == null || path == MEMORY
        val readOnly = args["readOnly"] == true
        val singleInstance = args["singleInstance"] != false && !inMemory
        synchronized(lock) {
            if (singleInstance) {
                val known = singleInstances[path]?.let { databases[it] }
                if (known != null) {
                    // Hot restart: the Dart side finds the database it had, in the transaction it had.
                    reply.success(openResult(known.id, recovered = true, inTransaction = known.depth > 0))
                    return
                }
            }
        }
        val id = synchronized(lock) { ++lastDatabaseId }
        worker.execute {
            val file = if (inMemory) null else File(path!!)
            val folder = file?.parentFile
            if (folder != null && !folder.exists() && !folder.mkdirs() && !folder.exists()) {
                reply.error(SQLITE_ERROR, "$OPEN_FAILED $path", null)
                return@execute
            }
            val connection = try {
                open(path ?: MEMORY, readOnly)
            } catch (e: Exception) {
                val op = Op(args, reply)
                if (e is SqliteException && e.primaryCode == CANTOPEN) reply.error(SQLITE_ERROR, "$OPEN_FAILED $path", null)
                else op.error(SQLITE_ERROR, e.message, op.errorInfo())
                return@execute
            }
            synchronized(lock) {
                if (singleInstance) singleInstances[path!!] = id
                databases[id] = Database(id, path ?: MEMORY, singleInstance, connection)
            }
            reply.success(openResult(id, recovered = false, inTransaction = false))
        }
    }

    private fun openResult(id: Int, recovered: Boolean, inTransaction: Boolean): Map<String, Any> = buildMap {
        put("id", id)
        if (recovered) put("recovered", true)
        if (inTransaction) put("recoveredInTransaction", true)
    }

    private fun closeDatabase(args: Map<*, *>, reply: Reply) {
        val db = databaseOrError(args, reply) ?: return
        synchronized(lock) {
            databases.remove(db.id)
            if (db.singleInstance) singleInstances.remove(db.path)
        }
        worker.execute {
            closeQuietly(db)
            reply.success(null)
        }
    }

    private fun deleteDatabase(args: Map<*, *>, reply: Reply) {
        val path = args["path"] as String
        val open = synchronized(lock) {
            singleInstances.remove(path)?.let { databases.remove(it) }
        }
        worker.execute {
            open?.let { closeQuietly(it) }
            runCatching { BundledSqlite.delete(path) }
            reply.success(null)
        }
    }

    private fun closeQuietly(db: Database) {
        db.cursors.values.forEach { runCatching { it.first.close() } }
        db.cursors.clear()
        runCatching { db.connection.close() }
    }

    private fun debugInfo(cmd: String?): Map<String, Any> = buildMap {
        if (cmd != "get") return@buildMap
        if (logLevel > 0) put("logLevel", logLevel)
        val open = synchronized(lock) { databases.values.toList() }
        if (open.isNotEmpty()) {
            put("databases", open.associate { it.id.toString() to mapOf("path" to it.path, "singleInstance" to it.singleInstance) })
        }
    }

    private fun databaseOrError(args: Map<*, *>, reply: Reply): Database? {
        val id = (args["id"] as? Number)?.toInt()
        val db = synchronized(lock) { id?.let { databases[it] } }
        if (db == null) reply.error(SQLITE_ERROR, "$DATABASE_CLOSED $id", null)
        return db
    }

    private fun onDatabase(args: Map<*, *>, reply: Reply, work: (Database) -> Unit) {
        val db = databaseOrError(args, reply) ?: return
        worker.execute { work(db) }
    }

    // ---------------------------------------------------------------- transactions

    /** One operation: a call, or one entry of a batch (whose answer is kept for the batch's reply). */
    private class Op(val args: Map<*, *>, private val reply: Reply?) {
        var result: Any? = null
        var errorCode: String? = null
        var errorMessage: String? = null
        var errorData: Any? = null

        val sql: String? get() = args["sql"] as String?
        @Suppress("UNCHECKED_CAST")
        val arguments: List<Any?> get() = (args["arguments"] as List<Any?>?).orEmpty()
        val noResult: Boolean get() = args["noResult"] == true
        val transactionId: Int? get() = (args["transactionId"] as? Number)?.toInt()
        val hasNullTransactionId: Boolean get() = args.containsKey("transactionId") && args["transactionId"] == null
        /** True when entering a transaction, false when leaving one, null otherwise. */
        val inTransactionChange: Boolean? get() = args["inTransaction"] as? Boolean

        fun success(value: Any?) {
            result = value
            reply?.success(value)
        }

        fun error(code: String, message: String?, data: Any?) {
            errorCode = code
            errorMessage = message
            errorData = data
            reply?.error(code, message, data)
        }

        fun errorInfo(): MutableMap<String, Any?> = hashMapOf("sql" to sql, "arguments" to (args["arguments"] ?: emptyList<Any?>()))

        val transactionClosed: Boolean get() = (errorData as? Map<*, *>)?.get("transactionClosed") == true
    }

    /** Runs [work] now, or keeps it until the open transaction ends when it is not that transaction's. */
    private fun wrap(db: Database, args: Map<*, *>, work: () -> Unit) {
        val current = db.currentTransactionId
        val transactionId = (args["transactionId"] as? Number)?.toInt()
        when {
            current == null -> work()
            transactionId != null && (transactionId == current || transactionId == TRANSACTION_ID_FORCE) -> {
                work()
                postQueuedIfNotInTransaction(db)
            }
            else -> db.queued += work
        }
    }

    private fun postQueuedIfNotInTransaction(db: Database) {
        if (db.currentTransactionId == null && db.queued.isNotEmpty()) worker.execute { runQueued(db) }
    }

    private fun runQueued(db: Database) {
        while (db.queued.isNotEmpty() && db.currentTransactionId == null) db.queued.removeAt(0)()
    }

    /** True when SQLite rolled the open transaction back by itself (ON CONFLICT ROLLBACK, SQLITE_FULL...): it is ended here too. */
    private fun leaveTransactionIfRolledBack(db: Database): Boolean {
        if (db.depth == 0 || runCatching { db.connection.inTransaction }.getOrDefault(true)) return false
        db.depth = 0
        db.currentTransactionId = null
        return true
    }

    /** A failed COMMIT or ROLLBACK ends the transaction for the Dart side: one SQLite still holds is rolled back. */
    private fun leaveTransactionOnError(db: Database, sql: String?) {
        db.depth = 0
        if (!endsTransaction(sql)) return
        if (runCatching { db.connection.inTransaction }.getOrDefault(false)) runCatching { db.connection.execute("ROLLBACK") }
    }

    private fun handleException(db: Database?, e: Exception, op: Op) {
        if (e is SqliteException && e.primaryCode == CANTOPEN) {
            op.error(SQLITE_ERROR, "$OPEN_FAILED ${db?.path}", null)
            return
        }
        op.error(SQLITE_ERROR, e.message, op.errorInfo())
    }

    private fun handleStatementException(db: Database, e: Exception, op: Op) {
        if (e is SqliteException && e.primaryCode != CANTOPEN && leaveTransactionIfRolledBack(db)) {
            op.error(SQLITE_ERROR, e.message, op.errorInfo().apply { put("transactionClosed", true) })
            return
        }
        handleException(db, e, op)
    }

    // ---------------------------------------------------------------- statements

    private fun executeOrError(db: Database, op: Op): Boolean {
        val change = op.inTransactionChange
        return try {
            db.connection.execute(op.sql.orEmpty(), op.arguments)
            if (change == true) db.depth++ else if (change == false) db.depth--
            true
        } catch (e: Exception) {
            if (change == false) leaveTransactionOnError(db, op.sql)
            if (change == null) handleStatementException(db, e, op) else handleException(db, e, op)
            false
        }
    }

    private fun execute(db: Database, op: Op) {
        val change = op.inTransactionChange
        val entering = change == true && op.hasNullTransactionId
        if (entering) db.currentTransactionId = ++db.lastTransactionId
        if (!executeOrError(db, op)) {
            if (entering || change == false) db.currentTransactionId = null
        } else if (entering) {
            op.success(mapOf("transactionId" to db.currentTransactionId))
        } else {
            if (change == false) db.currentTransactionId = null
            op.success(null)
        }
    }

    private fun doExecute(db: Database, op: Op): Boolean {
        if (!executeOrError(db, op)) return false
        op.success(null)
        return true
    }

    private fun insert(db: Database, op: Op): Boolean = try {
        val id = db.connection.insert(op.sql.orEmpty(), op.arguments)
        op.success(if (op.noResult) null else id)
        true
    } catch (e: Exception) {
        handleStatementException(db, e, op)
        false
    }

    private fun update(db: Database, op: Op): Boolean = try {
        val count = db.connection.update(op.sql.orEmpty(), op.arguments)
        op.success(if (op.noResult) null else count)
        true
    } catch (e: Exception) {
        handleStatementException(db, e, op)
        false
    }

    /** Rows as `{columns, rows}`, or an empty map when there are none (Android's own reply). */
    private fun results(cursor: PluginCursor, pageSize: Int?): MutableMap<String, Any?> {
        val rows = cursor.next(pageSize)
        return if (rows.isEmpty()) HashMap() else hashMapOf("columns" to cursor.columns, "rows" to rows)
    }

    private fun query(db: Database, op: Op): Boolean {
        val pageSize = (op.args["cursorPageSize"] as? Number)?.toInt()
        var cursor: PluginCursor? = null
        var kept = false
        return try {
            cursor = db.connection.cursor(op.sql.orEmpty(), op.arguments)
            val results = results(cursor, pageSize)
            if (pageSize != null && cursor.hasMore) {
                val id = ++db.lastCursorId
                results["cursorId"] = id
                db.cursors[id] = cursor to pageSize
                kept = true
            }
            op.success(results)
            true
        } catch (e: Exception) {
            handleStatementException(db, e, op)
            false
        } finally {
            if (!kept) runCatching { cursor?.close() }
        }
    }

    private fun cursorNext(db: Database, op: Op) {
        val id = (op.args["cursorId"] as Number).toInt()
        if (op.args["cancel"] == true) {
            db.cursors.remove(id)?.let { runCatching { it.first.close() } }
            op.success(null)
            return
        }
        try {
            val (cursor, pageSize) = checkNotNull(db.cursors[id]) { "Cursor $id not found" }
            val results = results(cursor, pageSize)
            if (cursor.hasMore) results["cursorId"] = id else db.cursors.remove(id)?.first?.close()
            op.success(results)
        } catch (e: Exception) {
            db.cursors.remove(id)?.let { runCatching { it.first.close() } }
            handleException(db, e, op)
        }
    }

    private fun batch(db: Database, args: Map<*, *>, reply: Reply) {
        val noResult = args["noResult"] == true
        val continueOnError = args["continueOnError"] == true
        val results = ArrayList<Map<String, Any?>>()
        for (entry in (args["operations"] as List<*>?).orEmpty()) {
            val map = entry as Map<*, *>
            // Each entry answers on its own result; noResult is the batch's.
            val op = Op(HashMap(map).apply { put("noResult", noResult) }, null)
            val method = map["method"] as String?
            val ok = when (method) {
                "execute" -> doExecute(db, op)
                "insert" -> insert(db, op)
                "query" -> query(db, op)
                "update" -> update(db, op)
                else -> {
                    reply.error(BAD_PARAM, "Batch method '$method' not supported", null)
                    return
                }
            }
            when {
                ok -> if (!noResult) results += mapOf("result" to op.result)
                continueOnError && !op.transactionClosed -> if (!noResult) {
                    results += mapOf("error" to mapOf("code" to op.errorCode, "message" to op.errorMessage, "data" to op.errorData))
                }
                else -> {
                    reply.error(op.errorCode.orEmpty(), op.errorMessage, op.errorData)
                    postQueuedIfNotInTransaction(db)
                    return
                }
            }
        }
        reply.success(if (noResult) null else results)
    }

    companion object {
        const val CHANNEL = "com.tekartik.sqflite"
        private const val MEMORY = ":memory:"
        private const val SQLITE_ERROR = "sqlite_error"
        private const val BAD_PARAM = "bad_param"
        private const val OPEN_FAILED = "open_failed"
        private const val DATABASE_CLOSED = "database_closed"
        private const val TRANSACTION_ID_FORCE = -1
        private const val CANTOPEN = 14
        private val rollbackTo = Regex("^ROLLBACK\\s+(TRANSACTION\\s+)?TO\\b", RegexOption.IGNORE_CASE)

        /** COMMIT, END or a plain ROLLBACK (not ROLLBACK TO a savepoint), as Android's statement types tell them. */
        internal fun endsTransaction(sql: String?): Boolean {
            val text = sql?.trim().orEmpty()
            val head = text.take(8).uppercase()
            return head.startsWith("COMMIT") || head.startsWith("END") || (head.startsWith("ROLLBACK") && !rollbackTo.containsMatchIn(text))
        }
    }
}

/**
 * The Android a plugin is told it runs on: the one droidtop is built to handle (its target SDK), never the device's
 * (docs/plugin-api.md 5.3, "Preferences and SQLite are droidtop's": "Plugins don't have to be given access to ANY
 * android internals"). A value droidtop's plugin runtime defines; nothing is intercepted to make it so.
 */
object HandledAndroid {
    const val API_LEVEL = 34
    const val RELEASE = "14"
}
