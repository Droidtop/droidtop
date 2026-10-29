package dev.droidtop.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.droidtop.pluginhost.DroidtopPlugin
import dev.droidtop.pluginhost.NativePluginRunner
import dev.droidtop.pluginhost.PluginArgs
import dev.droidtop.pluginhost.PluginCall
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginContext
import dev.droidtop.pluginhost.PluginDataStore
import dev.droidtop.pluginhost.PluginManifest
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginReply
import dev.droidtop.pluginhost.PluginResult
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.PluginTier
import dev.droidtop.pluginhost.PluginTrustState
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * A contained plugin's own folder on a real device (docs/plugin-api.md 5.3, "What a contained plugin's own folder
 * holds", Droidtop/tracker#375): files, listing, chmod, utimes, SharedPreferences, SQLite (rollback journal and WAL), the
 * quota's ENOSPC and the per-file EFBIG, each done the way a Flutter package's Android half does it, by Android's own
 * paths, from inside droidtop's isolated sandbox process. Run by hand with `am instrument`, never in CI:
 *
 *     adb shell am instrument -w -e class dev.droidtop.app.ContainedFilesRigTest \
 *         dev.droidtop.app.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The plugin is [Probe], a class of this test APK, which the test copies in as the plugin's `classes.jar` (an APK is a
 * zip with `classes.dex` at its root) and loads with droidtop's own runner, contained, as an installed plugin is. It is
 * never installed, approved or listed; its folder is removed after the test. Nothing outside it is touched.
 */
@RunWith(AndroidJUnit4::class)
class ContainedFilesRigTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val runner = NativePluginRunner(context, onCrash = { _, _, _ -> })

    /**
     * What runs inside the sandbox: each check answers "ok" or the error it met. It uses only what droidtop gives a
     * plugin (its [PluginContext]: its folder, its preferences), never Android's own context or services.
     */
    class Probe : DroidtopPlugin {
        private lateinit var context: PluginContext

        override fun onLoad(context: PluginContext) {
            this.context = context
        }

        override fun invoke(capability: PluginCapability, args: PluginArgs): PluginResult = PluginResult.failure("contract 2 only")

        override fun handle(call: PluginCall): PluginReply {
            val out = JSONObject()
            fun check(name: String, block: () -> Unit) {
                out.put(name, runCatching { block(); "ok" }.getOrElse { "error: ${it::class.java.simpleName}: ${it.message}" })
            }
            // The plugin's own folder by path; "files" is where Android's getFilesDir would put a plugin's files.
            val files = File(context.privateDataDir(), "files")
            val dir = File(files, "rig")
            when (call.op) {
                "files" -> {
                    check("write") {
                        dir.mkdirs()
                        File(dir, "a.txt").writeText("hello")
                        require(File(dir, "a.txt").readText() == "hello") { "read back differs" }
                    }
                    check("list") {
                        // Which half fails, when one does: opening the folder (File.list answers null) or reading it.
                        val opened = runCatching {
                            android.system.Os.open(files.path, android.system.OsConstants.O_RDONLY, 0)
                        }
                        opened.getOrNull()?.let { android.system.Os.close(it) }
                        require(files.list()?.contains("rig") == true) {
                            "files lists ${files.list()?.toList()}; opening it: ${opened.exceptionOrNull()?.message ?: "ok"}"
                        }
                        require(dir.listFiles()?.any { it.name == "a.txt" } == true) { "rig lists ${dir.list()?.toList()}" }
                    }
                    check("chmod") {
                        val path = File(dir, "a.txt").path
                        android.system.Os.chmod(path, 384 /* 0600 */)
                        require(android.system.Os.stat(path).st_mode and 511 == 384) { "mode is ${android.system.Os.stat(path).st_mode and 511}" }
                    }
                    check("utimes") {
                        val file = File(dir, "a.txt")
                        require(file.setLastModified(1_000_000_000_000L)) { "setLastModified answered false" }
                        require(file.lastModified() == 1_000_000_000_000L) { "lastModified is ${file.lastModified()}" }
                    }
                    check("prefs") {
                        val prefs = requireNotNull(context.preferences("rig")) { "no preferences for the plugin" }
                        require(prefs.edit().putString("k", "v").commit()) { "commit answered false" }
                        val saved = File(context.privateDataDir(), "shared_prefs/rig.xml").readText()
                        require(saved.contains("<string name=\"k\">v</string>")) { "saved as $saved" }
                    }
                    // droidtop's own SQLite, never Android's (docs/plugin-api.md 5.3), with a rollback journal and with WAL.
                    check("sqlite") { sqlite("rig.db", wal = false) }
                    check("sqlite-wal") { sqlite("rig-wal.db", wal = true) }
                }
                "quota" -> {
                    dir.mkdirs()
                    // The file "existing" opens below, made while there is room: each test starts from an empty folder.
                    check("before") { File(dir, "a.txt").writeText("hello") }
                    val limit = call.args.getLong("limit")
                    check("efbig") {
                        val failed = runCatching { RandomAccessFile(File(dir, "big0"), "rw").use { it.setLength(limit + 1) } }.isFailure
                        require(failed) { "a file past the whole quota was allowed" }
                    }
                    // Sparse files: the folder's size passes the quota without using the disk.
                    check("fill") {
                        RandomAccessFile(File(dir, "big1"), "rw").use { it.setLength(limit / 2 + 1) }
                        RandomAccessFile(File(dir, "big2"), "rw").use { it.setLength(limit / 2 + 1) }
                    }
                    Thread.sleep(1_200) // the broker measures the folder at most once a second
                    check("enospc") {
                        val error = runCatching { File(dir, "after.txt").writeText("x") }.exceptionOrNull()
                        require(error != null && (error.message ?: "").contains("No space left")) { "a new file at the quota: ${error?.message ?: "allowed"}" }
                    }
                    check("existing") { File(dir, "a.txt").appendText("!") }
                    check("free") {
                        File(dir, "big0").delete()
                        File(dir, "big1").delete()
                        File(dir, "big2").delete()
                        Thread.sleep(1_200)
                        File(dir, "after.txt").writeText("x")
                    }
                }
            }
            return PluginReply.ok(out)
        }

        private fun sqlite(name: String, wal: Boolean) {
            requireNotNull(context.database(name)) { "no database for the plugin" }.use { db ->
                if (wal) {
                    val mode = db.query("PRAGMA journal_mode=WAL").rows.single().single()
                    require(mode == "wal") { "journal mode is $mode" }
                }
                db.execute("create table if not exists t(x text)")
                require(db.insert("insert into t values (?)", listOf("row")) > 0) { "no row id" }
                val count = db.query("select count(*) from t").rows.single().single() as Long
                require(count >= 1) { "no row" }
            }
        }
    }

    private fun record(): PluginRecord {
        val dir = PluginStore.payloadDirFor(context, ID).apply { mkdirs() }
        val jar = File(dir, "classes.jar")
        File(instrumentation.context.packageCodePath).copyTo(jar, overwrite = true)
        val sha = MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
        val manifest = JSONObject()
            .put("id", ID).put("origin", "droidtop.rigtest").put("label", "Rig files").put("kind", "native_bundle")
            .put("contractVersion", 2).put("capabilities", JSONArray())
            .put("abis", JSONArray(listOf("arm64-v8a", "x86_64"))).put("entryClass", Probe::class.java.name)
            .put("payload", JSONArray().put(JSONObject().put("path", "classes.jar").put("sha256", sha)))
            .put("provides", JSONArray().put(JSONObject().put("point", "ui.status_tile")))
        File(dir, "manifest.json").writeText(manifest.toString())
        return PluginRecord(PluginManifest.fromJson(manifest)!!, "0".repeat(64), "", PluginTrustState.APPROVED, enabled = true, rootApproved = false, disabledReason = null)
    }

    private fun ask(op: String, args: JSONObject = JSONObject()): JSONObject = runBlocking {
        val record = record()
        assertTrue("loaded contained: ${runner.loadFailure(ID)}", runner.load(record, PluginStore.payloadDirFor(context, ID).path, PluginTier.CONTAINED))
        val reply = runner.handle(ID, PluginCall("rig", 60_000, "ui.status_tile", 1, op, args = args), 60_000, crashOnTimeout = false)
        assertTrue("answered: ${reply.code} ${reply.message}", reply.ok)
        reply.data
    }

    private fun assertAllOk(results: JSONObject) {
        val failed = results.keys().asSequence().filter { results.getString(it) != "ok" }.map { "$it: ${results.getString(it)}" }.toList()
        assertTrue("checks that failed: $failed", failed.isEmpty())
    }

    @Test
    fun theOwnFolderBehavesForPathsListingChmodUtimesPrefsAndSqlite() {
        val results = ask("files")
        assertAllOk(results)
        // The broker put it in the plugin's own folder, under droidtop's private folder's name.
        val placed = File(PluginStore.dataDirFor(context, ID), "app-data/files/rig/a.txt")
        assertEquals("hello", placed.readText())
    }

    @Test
    fun theQuotaRefusesANewFileWithEnospcAndOneFileWithEfbig() {
        assertAllOk(ask("quota", JSONObject().put("limit", PluginDataStore.LIMIT_BYTES)))
    }

    @After
    fun cleanUp() {
        runner.unload(ID)
        // Only this test's own two folders, file by file: nothing outside them.
        listOf(PluginStore.dataDirFor(context, ID), PluginStore.payloadDirFor(context, ID)).forEach { root ->
            root.walkBottomUp().forEach { it.delete() }
        }
    }

    private companion object {
        const val ID = "droidtop.rigtest.files"
    }
}
