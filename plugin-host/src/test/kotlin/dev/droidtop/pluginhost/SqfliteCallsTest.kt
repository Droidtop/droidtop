package dev.droidtop.pluginhost

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `sqflite`'s channel as droidtop answers it: the Android half's replies, over a scripted database. */
class SqfliteCallsTest {
    /** A database that records each statement, keeps a transaction flag the way SQLite would, and fails on cue. */
    private class FakeDatabase(override val path: String) : PluginDatabase {
        val ran = ArrayList<String>()
        var failNext: Exception? = null
        var rollsBackOnFailure = false
        var transaction = false
        var rows: List<List<Any?>> = emptyList()
        var nextId = 0L

        private fun run(sql: String) {
            failNext?.let {
                failNext = null
                if (rollsBackOnFailure) transaction = false
                throw it
            }
            ran += sql
            when {
                sql.startsWith("BEGIN") -> transaction = true
                sql.startsWith("COMMIT") || sql == "ROLLBACK" -> transaction = false
            }
        }

        override fun execute(sql: String, args: List<Any?>) = run(sql)
        override fun insert(sql: String, args: List<Any?>): Long = run(sql).let { ++nextId }
        override fun update(sql: String, args: List<Any?>): Int = run(sql).let { 1 }
        override fun cursor(sql: String, args: List<Any?>): PluginCursor {
            run(sql)
            return object : PluginCursor {
                private var at = 0
                override val columns = listOf("a")
                override fun next(limit: Int?): List<List<Any?>> {
                    val end = if (limit == null) rows.size else minOf(rows.size, at + limit)
                    return rows.subList(at, end).also { at = end }
                }
                override val hasMore: Boolean get() = at < rows.size
                override fun close() {}
            }
        }
        override val inTransaction: Boolean get() = transaction
        override fun close() {}
    }

    private class Answer : SqfliteCalls.Reply {
        var answered = false
        var value: Any? = null
        var code: String? = null
        var message: String? = null
        var data: Any? = null
        var notImplemented = false

        override fun success(value: Any?) {
            answered = true
            this.value = value
        }

        override fun error(code: String, message: String?, data: Any?) {
            answered = true
            this.code = code
            this.message = message
            this.data = data
        }

        override fun notImplemented() {
            answered = true
            notImplemented = true
        }
    }

    // The handler makes a database's folder as sqflite does, so paths are under a folder this test may write.
    private val a = java.nio.file.Files.createTempDirectory("sqflite").resolve("a.db").toString()
    private val opened = HashMap<String, FakeDatabase>()
    private val calls = SqfliteCalls("/data/user/0/app/databases", { path, _ -> FakeDatabase(path).also { opened[path] = it } }, Executor { it.run() })

    private fun call(method: String, args: Any? = null): Answer = Answer().also { calls.call(method, args, it) }

    private fun open(path: String = a): Int = ((call("openDatabase", mapOf("path" to path)).value as Map<*, *>)["id"] as Int)

    @Test
    fun theDeviceIsNeverNamedAndTheLocaleIsAcceptedAsIs() {
        assertEquals("Android ${HandledAndroid.RELEASE}", call("getPlatformVersion").value)
        assertEquals("/data/user/0/app/databases", call("getDatabasesPath").value)
        val id = open()
        val locale = call("androidSetLocale", mapOf("id" to id, "locale" to "fr-FR"))
        assertTrue(locale.answered && locale.code == null)
        assertTrue(call("noSuchMethod").notImplemented)
    }

    @Test
    fun aSingleInstanceIsRecoveredAndAMemoryDatabaseNever() {
        val id = open()
        assertEquals(mapOf("id" to id, "recovered" to true), call("openDatabase", mapOf("path" to a)).value)
        val memory1 = call("openDatabase", mapOf("path" to ":memory:")).value as Map<*, *>
        val memory2 = call("openDatabase", mapOf("path" to ":memory:")).value as Map<*, *>
        assertFalse(memory2.containsKey("recovered"))
        assertTrue(memory1["id"] != memory2["id"])
    }

    @Test
    fun aCallOutsideTheOpenTransactionWaitsUntilItEnds() {
        val id = open()
        val begin = call("execute", mapOf("id" to id, "sql" to "BEGIN IMMEDIATE", "inTransaction" to true, "transactionId" to null))
        assertEquals(mapOf("transactionId" to 1), begin.value)
        val outside = call("insert", mapOf("id" to id, "sql" to "INSERT outside"))
        assertFalse("waits for the transaction", outside.answered)
        val inside = call("insert", mapOf("id" to id, "sql" to "INSERT inside", "transactionId" to 1))
        assertEquals(1L, inside.value)
        call("execute", mapOf("id" to id, "sql" to "COMMIT", "inTransaction" to false, "transactionId" to 1))
        assertTrue(outside.answered)
        assertEquals(listOf("BEGIN IMMEDIATE", "INSERT inside", "COMMIT", "INSERT outside"), opened.getValue(a).ran)
    }

    @Test
    fun anErrorIsWordedAsAndroidWordsItWithTheStatement() {
        val id = open()
        opened.getValue(a).failNext = SqliteException(2067, "UNIQUE constraint failed: t.a")
        val answer = call("insert", mapOf("id" to id, "sql" to "INSERT INTO t VALUES (?)", "arguments" to listOf(1)))
        assertEquals("sqlite_error", answer.code)
        assertEquals("UNIQUE constraint failed: t.a (code 2067 SQLITE_CONSTRAINT_UNIQUE)", answer.message)
        assertEquals(mapOf("sql" to "INSERT INTO t VALUES (?)", "arguments" to listOf(1)), answer.data)
        assertEquals("database_closed 99", call("query", mapOf("id" to 99, "sql" to "SELECT 1")).message)
    }

    @Test
    fun aTransactionSqliteRolledBackIsClosedAndTheQueueRuns() {
        val id = open()
        val db = opened.getValue(a)
        call("execute", mapOf("id" to id, "sql" to "BEGIN", "inTransaction" to true, "transactionId" to null))
        val waiting = call("execute", mapOf("id" to id, "sql" to "UPDATE later"))
        db.failNext = SqliteException(13, "database or disk is full")
        db.rollsBackOnFailure = true
        val failed = call("insert", mapOf("id" to id, "sql" to "INSERT big", "transactionId" to 1))
        assertEquals(true, (failed.data as Map<*, *>)["transactionClosed"])
        assertTrue("the queue runs once SQLite ended the transaction", waiting.answered)
    }

    @Test
    fun aBatchAnswersEachOperationAndStopsAtAnErrorUnlessToldToGoOn() {
        val id = open()
        val db = opened.getValue(a)
        val ops = listOf(
            mapOf("method" to "execute", "sql" to "CREATE TABLE t(a)"),
            mapOf("method" to "insert", "sql" to "INSERT INTO t VALUES (1)"),
        )
        assertEquals(listOf(mapOf("result" to null), mapOf("result" to 1L)), call("batch", mapOf("id" to id, "operations" to ops)).value)
        db.failNext = SqliteException(1, "no such table: u")
        val onward = call("batch", mapOf("id" to id, "continueOnError" to true, "operations" to listOf(mapOf("method" to "query", "sql" to "SELECT * FROM u"))))
        val error = ((onward.value as List<*>).single() as Map<*, *>)["error"] as Map<*, *>
        assertEquals("sqlite_error", error["code"])
        assertEquals("no such table: u (code 1 SQLITE_ERROR)", error["message"])
        db.failNext = SqliteException(1, "no such table: u")
        val stopped = call("batch", mapOf("id" to id, "operations" to listOf(mapOf("method" to "query", "sql" to "SELECT * FROM u"))))
        assertEquals("sqlite_error", stopped.code)
        assertEquals("bad_param", call("batch", mapOf("id" to id, "operations" to listOf(mapOf("method" to "vacuum"))) ).code)
        assertNull(call("batch", mapOf("id" to id, "noResult" to true, "operations" to ops)).value)
    }

    @Test
    fun aQueryWithNoRowsIsAnEmptyMapAndAPagedOneKeepsItsCursor() {
        val id = open()
        val db = opened.getValue(a)
        assertEquals(emptyMap<String, Any>(), call("query", mapOf("id" to id, "sql" to "SELECT a FROM t")).value)
        db.rows = listOf(listOf(1L), listOf(2L), listOf(3L))
        val first = call("query", mapOf("id" to id, "sql" to "SELECT a FROM t", "cursorPageSize" to 2)).value as Map<*, *>
        assertEquals(listOf("a"), first["columns"])
        assertEquals(listOf(listOf(1L), listOf(2L)), first["rows"])
        val cursorId = first["cursorId"] as Int
        val rest = call("queryCursorNext", mapOf("id" to id, "cursorId" to cursorId, "cursorPageSize" to 2)).value as Map<*, *>
        assertEquals(listOf(listOf(3L)), rest["rows"])
        assertFalse(rest.containsKey("cursorId"))
        assertTrue(call("queryCursorNext", mapOf("id" to id, "cursorId" to cursorId)).message!!.contains("Cursor $cursorId not found"))
    }

    @Test
    fun aDeletedSingleInstanceIsClosedAndForgotten() {
        open()
        call("deleteDatabase", mapOf("path" to a))
        assertFalse(call("openDatabase", mapOf("path" to a)).value.let { (it as Map<*, *>).containsKey("recovered") })
    }

    @Test
    fun commitEndAndAPlainRollbackEndATransactionButRollbackToDoesNot() {
        assertTrue(SqfliteCalls.endsTransaction("COMMIT"))
        assertTrue(SqfliteCalls.endsTransaction(" end transaction"))
        assertTrue(SqfliteCalls.endsTransaction("ROLLBACK"))
        assertFalse(SqfliteCalls.endsTransaction("ROLLBACK TO sp1"))
        assertFalse(SqfliteCalls.endsTransaction("rollback transaction to sp1"))
        assertFalse(SqfliteCalls.endsTransaction("SELECT 1"))
    }

    @Test
    fun aByteListIsABlobAsSqfliteSendsIt() {
        assertTrue((BundledSqlite.blobOf(listOf(1, 255, -1)) as ByteArray).contentEquals(byteArrayOf(1, -1, -1)))
        assertEquals("x", BundledSqlite.blobOf("x"))
    }
}
