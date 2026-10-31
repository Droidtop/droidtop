package dev.droidtop.app

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.droidtop.library.integrations.UnifiedSearch
import dev.droidtop.pluginhost.NativePluginRunner
import dev.droidtop.pluginhost.PluginCall
import dev.droidtop.pluginhost.PluginManifest
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.PluginTier
import dev.droidtop.pluginhost.PluginTrustState
import dev.droidtop.pluginhost.PythonRuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * How long a contained python plugin takes to start, step by step (Droidtop/tracker#848): on the console four python
 * sources took about 11 s to load, and Gaming Search said they did not answer in time. Each step is said as a "rig
 * start:" line ([rigLine]): the runtime's one-time download and extract, the first python plugin, a second in a process
 * of its own, a second call to the first, and four started at once against [UnifiedSearch.LOAD_TIMEOUT_MS]. Nothing is
 * held to a budget here: the console's speed is not an emulator's, and scripts/emulator-tests.sh reports it only. Run
 * on the console with
 *
 *     adb shell am instrument -w -e class dev.droidtop.app.PythonPluginStartRigTest \
 *         dev.droidtop.app.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The plugins are one `plugin.py` that answers a ping, written into a record directly as [PluginStartRigTest] does
 * with its Probe: no bundle, signature or trust prompt, the same contained load. Their folders are removed after.
 */
@RunWith(AndroidJUnit4::class)
class PythonPluginStartRigTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val runner = NativePluginRunner(context, onCrash = { _, _, _ -> })

    private fun record(id: String): PluginRecord {
        val dir = PluginStore.payloadDirFor(context, id).apply { mkdirs() }
        val script = File(dir, "plugin.py").apply { writeText(PLUGIN_PY) }
        val sha = MessageDigest.getInstance("SHA-256").digest(script.readBytes()).joinToString("") { "%02x".format(it) }
        val manifest = JSONObject()
            .put("id", id).put("origin", "droidtop.rigtest").put("label", "Rig start (python)").put("kind", "python")
            .put("contractVersion", 2).put("capabilities", JSONArray()).put("abis", JSONArray())
            .put("payload", JSONArray().put(JSONObject().put("path", "plugin.py").put("sha256", sha)))
            .put("provides", JSONArray().put(JSONObject().put("point", "ui.status_tile")))
        File(dir, "manifest.json").writeText(manifest.toString())
        return PluginRecord(PluginManifest.fromJson(manifest)!!, "0".repeat(64), "", PluginTrustState.APPROVED, enabled = true, rootApproved = false, disabledReason = null)
    }

    /** One ping to [id], already loaded; returns the milliseconds the answer took. */
    private suspend fun ping(id: String): Long {
        val started = SystemClock.uptimeMillis()
        val reply = runner.handle(id, PluginCall("rig", 30_000, "ui.status_tile", 1, "ping"), 30_000, crashOnTimeout = false)
        assertTrue("$id answered: ${reply.code} ${reply.message}", reply.ok)
        return SystemClock.uptimeMillis() - started
    }

    /** Loads [id] contained and has it answer once; returns the milliseconds from the load to the answer. */
    private suspend fun start(id: String): Long {
        val record = record(id)
        val started = SystemClock.uptimeMillis()
        assertTrue("$id loaded contained: ${runner.loadFailure(id)}", runner.load(record, PluginStore.payloadDirFor(context, id).path, PluginTier.CONTAINED))
        ping(id)
        return SystemClock.uptimeMillis() - started
    }

    @Test
    fun eachStepOfAPythonStartIsTimed() = runBlocking {
        val had = PythonRuntimeManager.isInstalled(context)
        val runtimeStarted = SystemClock.uptimeMillis()
        assertNull("the Python runtime installed", PythonRuntimeManager.ensureInstalled(context) { })
        val runtime = SystemClock.uptimeMillis() - runtimeStarted
        instrumentation.rigLine(if (had) "python runtime already installed" else "python runtime download and extract $runtime ms")

        instrumentation.rigLine("first python plugin ${start(FIRST)} ms (load to first answer)")
        instrumentation.rigLine("second python plugin ${start(SECOND)} ms (its own process)")
        instrumentation.rigLine("warm call to the first ${ping(FIRST)} ms")

        val together = SystemClock.uptimeMillis()
        val each = TOGETHER.map { id -> async(Dispatchers.Default) { start(id) } }.awaitAll()
        val all = SystemClock.uptimeMillis() - together
        instrumentation.rigLine(
            "four python plugins at once $all ms, slowest ${each.max()} ms (search waits up to ${UnifiedSearch.LOAD_TIMEOUT_MS} ms; the console took about 11000)",
        )
    }

    @After
    fun cleanUp() {
        (listOf(FIRST, SECOND) + TOGETHER).forEach { id ->
            runner.unload(id)
            // Only this test's own folders, file by file: nothing outside them.
            listOf(PluginStore.dataDirFor(context, id), PluginStore.payloadDirFor(context, id)).forEach { root ->
                root.walkBottomUp().forEach { it.delete() }
            }
        }
    }

    private companion object {
        const val FIRST = "droidtop.rigtest.python1"
        const val SECOND = "droidtop.rigtest.python2"
        val TOGETHER = (3..6).map { "droidtop.rigtest.python$it" }

        /** Contract 2: `handle` answers every call itself (docs/plugin-api.md 1.3). */
        val PLUGIN_PY = """
            import json

            def handle(call_json):
                return json.dumps({"ok": True, "data": {"pong": True}})
        """.trimIndent() + "\n"
    }
}
