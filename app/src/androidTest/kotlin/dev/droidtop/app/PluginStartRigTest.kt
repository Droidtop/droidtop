package dev.droidtop.app

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.droidtop.pluginhost.DroidtopPlugin
import dev.droidtop.pluginhost.NativePluginRunner
import dev.droidtop.pluginhost.PluginArgs
import dev.droidtop.pluginhost.PluginCall
import dev.droidtop.pluginhost.PluginCapability
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * How long a contained plugin takes to start (docs/plugin-api.md 5.3, "The rigs"): on the BlueStacks rig every plugin
 * process waited a constant 10 s at its first app library load, so the first search after a start ran out of time.
 * A second plugin, in a process of its own, must start within [BUDGET_MS]; on BlueStacks it does not yet, and this
 * says by how much. Run on the CI emulator (scripts/emulator-tests.sh, reported only) and by hand with
 * `am instrument`:
 *
 *     adb shell am instrument -w -e class dev.droidtop.app.PluginStartRigTest \
 *         dev.droidtop.app.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The plugins are [Probe], a class of this test APK, loaded contained with droidtop's own runner as in
 * [ContainedFilesRigTest]; their folders are removed after the test.
 */
@RunWith(AndroidJUnit4::class)
class PluginStartRigTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val runner = NativePluginRunner(context, onCrash = { _, _, _ -> })

    class Probe : DroidtopPlugin {
        override fun invoke(capability: PluginCapability, args: PluginArgs): PluginResult = PluginResult.failure("contract 2 only")
        override fun handle(call: PluginCall): PluginReply = PluginReply.ok(JSONObject().put("pong", true))
    }

    private fun record(id: String): PluginRecord {
        val dir = PluginStore.payloadDirFor(context, id).apply { mkdirs() }
        val jar = File(dir, "classes.jar")
        File(instrumentation.context.packageCodePath).copyTo(jar, overwrite = true)
        val sha = MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
        val manifest = JSONObject()
            .put("id", id).put("origin", "droidtop.rigtest").put("label", "Rig start").put("kind", "native_bundle")
            .put("contractVersion", 2).put("capabilities", JSONArray())
            .put("abis", JSONArray(listOf("arm64-v8a", "x86_64"))).put("entryClass", Probe::class.java.name)
            .put("payload", JSONArray().put(JSONObject().put("path", "classes.jar").put("sha256", sha)))
            .put("provides", JSONArray().put(JSONObject().put("point", "ui.status_tile")))
        File(dir, "manifest.json").writeText(manifest.toString())
        return PluginRecord(PluginManifest.fromJson(manifest)!!, "0".repeat(64), "", PluginTrustState.APPROVED, enabled = true, rootApproved = false, disabledReason = null)
    }

    /** Loads [id] contained and has it answer once; returns the milliseconds that took. */
    private fun start(id: String): Long = runBlocking {
        val record = record(id)
        val started = SystemClock.uptimeMillis()
        assertTrue("loaded contained: ${runner.loadFailure(id)}", runner.load(record, PluginStore.payloadDirFor(context, id).path, PluginTier.CONTAINED))
        val reply = runner.handle(id, PluginCall("rig", 30_000, "ui.status_tile", 1, "ping"), 30_000, crashOnTimeout = false)
        assertTrue("answered: ${reply.code} ${reply.message}", reply.ok)
        SystemClock.uptimeMillis() - started
    }

    @Test
    fun aContainedPluginStartsWithinItsBudget() {
        val first = start(FIRST)
        val second = start(SECOND)
        instrumentation.rigLine("first contained plugin $first ms, second $second ms")
        assertTrue("a second contained plugin took $second ms to start (budget $BUDGET_MS ms; the first took $first ms)", second <= BUDGET_MS)
    }

    @After
    fun cleanUp() {
        listOf(FIRST, SECOND).forEach { id ->
            runner.unload(id)
            // Only this test's own folders, file by file: nothing outside them.
            listOf(PluginStore.dataDirFor(context, id), PluginStore.payloadDirFor(context, id)).forEach { root ->
                root.walkBottomUp().forEach { it.delete() }
            }
        }
    }

    private companion object {
        const val FIRST = "droidtop.rigtest.start1"
        const val SECOND = "droidtop.rigtest.start2"
        const val BUDGET_MS = 3_000L
    }
}
