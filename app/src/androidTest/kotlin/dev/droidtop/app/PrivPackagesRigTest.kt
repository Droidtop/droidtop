package dev.droidtop.app

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.droidtop.pluginhost.AppBrokerEnvironment
import dev.droidtop.pluginhost.BrokerCore
import dev.droidtop.pluginhost.BrokerEnvironment
import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.PluginDataStore
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginManifest
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginReply
import dev.droidtop.pluginhost.PluginTrustState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The rig check of `priv.packages` (docs/plugin-api.md 2.7, Droidtop/tracker#130) on a real console, through droidtop's
 * real broker and the installed Shizuku plugin, on a throwaway app the test installs and removes itself
 * (`:rigtest-throwaway`, carried in this test APK). Run by hand with `am instrument`, never in CI:
 *
 *     adb shell am instrument -w -e class dev.droidtop.app.PrivPackagesRigTest \
 *         dev.droidtop.app.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The caller is a stand-in record that exists only in this test's broker environment: it is never installed, never in
 * the plugin list and never trusted, and its grant, its "the person started this" and its yes on the confirm card hold
 * only here. Everything else (records, grants, the provider, its process, the job) is droidtop's own.
 */
@RunWith(AndroidJUnit4::class)
class PrivPackagesRigTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val real = AppBrokerEnvironment(context)
    private val dataDir = File(context.cacheDir, "rigtest-caller")
    private val confirms = mutableListOf<String>()

    private val caller = PluginRecord(
        PluginManifest.fromJson(
            JSONObject()
                .put("id", CALLER).put("origin", "droidtop.rigtest").put("label", "Rig check").put("kind", "native_bundle")
                .put("contractVersion", 2).put("capabilities", JSONArray())
                .put("abis", JSONArray(listOf("arm64-v8a", "x86_64"))).put("entryClass", "dev.droidtop.rigtest.None")
                .put("payload", JSONArray().put(JSONObject().put("path", "classes.jar").put("sha256", "a".repeat(64))))
                .put("provides", JSONArray().put(JSONObject().put("point", "ui.status_tile")))
                .put("requires", JSONArray().put(JSONObject().put("api", "priv.packages").put("version", "1.0").put("optional", true)))
                .put("permissions", JSONArray().put(JSONObject().put("id", "priv.packages"))),
        )!!,
        "0".repeat(64),
        "",
        PluginTrustState.APPROVED,
        enabled = true,
        rootApproved = false,
        disabledReason = null,
    )

    private val env = object : BrokerEnvironment by real {
        override fun record(pluginId: String) = if (pluginId == CALLER) caller else real.record(pluginId)
        override fun grants(pluginId: String) =
            if (pluginId == CALLER) PluginGrants.Snapshot(mapOf("priv.packages" to GrantState.GRANTED)) else real.grants(pluginId)
        override fun setGrant(pluginId: String, permission: String, state: GrantState) {
            if (pluginId != CALLER) real.setGrant(pluginId, permission, state)
        }
        override fun noteWanted(pluginId: String, permission: String) {
            if (pluginId != CALLER) real.noteWanted(pluginId, permission)
        }
        override fun audit(pluginId: String, entry: dev.droidtop.pluginhost.AuditEntry) {
            if (pluginId != CALLER) real.audit(pluginId, entry)
        }
        override fun userInitiated(pluginId: String) = pluginId == CALLER || real.userInitiated(pluginId)
        override fun confirm(notice: dev.droidtop.library.settings.Notice, proceed: String, cancel: String): Boolean {
            confirms += notice.title
            return true
        }
        override fun dataStore(pluginId: String) = if (pluginId == CALLER) PluginDataStore(dataDir) else real.dataStore(pluginId)
    }

    private fun call(op: String, args: JSONObject): PluginReply = PluginReply.parse(
        BrokerCore(CALLER, env).call(JSONObject().put("api", "priv.packages").put("version", 1).put("op", op).put("args", args).toString()),
    )

    private fun installed(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun cameraGranted() = context.packageManager.checkPermission(CAMERA, THROWAWAY) == PackageManager.PERMISSION_GRANTED

    /** Waits for a brokered job and answers its reply as the caller reads it. */
    private fun finish(started: PluginReply): JSONObject {
        assertTrue("the job started: ${started.code} ${started.message}", started.ok)
        val jobId = started.data.getString("jobId")
        val deadline = System.currentTimeMillis() + 10 * 60 * 1000L
        while (System.currentTimeMillis() < deadline) {
            val status = env.brokeredJobStatus(caller, jobId)
            if (status.ok && status.data.optBoolean("done")) return status.data
            Thread.sleep(500)
        }
        throw AssertionError("the install did not finish in 10 minutes")
    }

    @Test
    fun throwawayAppThroughShizuku() {
        assertTrue(
            "the Shizuku plugin is installed, on and running, and droidtop is allowed in Shizuku",
            env.resolution().providers["priv.packages"].orEmpty().isNotEmpty(),
        )
        if (installed(THROWAWAY)) call("uninstall", JSONObject().put("package", THROWAWAY))
        assertFalse("the throwaway is not installed before the check", installed(THROWAWAY))

        // Install through droidtop's own path: the APK in the caller's data, confirmed by name, staged, streamed.
        dataDir.mkdirs()
        instrumentation.context.assets.open("throwaway.apk").use { input -> File(dataDir, "throwaway.apk").outputStream().use { input.copyTo(it) } }
        val done = finish(call("install", JSONObject().put("name", "throwaway.apk")))
        assertTrue("install: $done", done.optBoolean("ok"))
        assertTrue(installed(THROWAWAY))
        assertEquals(listOf("Install droidtop rig throwaway 1.0?"), confirms)

        // grant, then revoke.
        assertTrue(call("grant_permission", JSONObject().put("package", THROWAWAY).put("permission", CAMERA)).ok)
        assertTrue("granted", cameraGranted())
        assertTrue(call("revoke_permission", JSONObject().put("package", THROWAWAY).put("permission", CAMERA)).ok)
        assertFalse("revoked", cameraGranted())

        val cleared = call("clear_data", JSONObject().put("package", THROWAWAY))
        assertTrue("clear_data: ${cleared.message}", cleared.ok)

        // What is never touched.
        val home = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        for (pkg in listOfNotNull(context.packageName, SHIZUKU, home).distinct()) {
            val reply = call("uninstall", JSONObject().put("package", pkg))
            assertEquals("uninstall $pkg is refused: ${reply.message}", "PERMISSION_DENIED", reply.code?.name)
            assertTrue("$pkg is still installed", installed(pkg))
        }
        assertEquals("PERMISSION_DENIED", call("clear_data", JSONObject().put("package", context.packageName)).code?.name)

        val removed = call("uninstall", JSONObject().put("package", THROWAWAY))
        assertTrue("uninstall: ${removed.message}", removed.ok)
        assertFalse(installed(THROWAWAY))
        assertEquals("NOT_FOUND", call("uninstall", JSONObject().put("package", THROWAWAY)).code?.name)
    }

    @After
    fun cleanUp() {
        if (installed(THROWAWAY)) call("uninstall", JSONObject().put("package", THROWAWAY))
        File(dataDir, "throwaway.apk").delete()
        dataDir.delete()
    }

    private companion object {
        const val CALLER = "droidtop.rigtest.caller"
        const val THROWAWAY = "dev.droidtop.rigtest.throwaway"
        const val SHIZUKU = "moe.shizuku.privileged.api"
        const val CAMERA = "android.permission.CAMERA"
    }
}
