package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.AppInterfaceTrust
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** droidtop Browser installed by droidtop (docs/SPEC.md 12a): which release asset it takes and when it is current. */
class BrowserInstallTest {
    private fun release(vararg abis: String) = JSONObject()
        .put("tag_name", "v160.0.1-dt.5")
        .put(
            "assets",
            JSONArray().apply {
                abis.forEach { abi ->
                    put(
                        JSONObject().put("name", "droidtop-browser-$abi.apk").put("size", 134_000_000L)
                            .put("browser_download_url", "https://github.com/Droidtop/droidtop-browser/releases/download/v160.0.1-dt.5/droidtop-browser-$abi.apk"),
                    )
                }
            },
        )

    @Test
    fun `the device's processor picks the APK, and a release without it gives nothing`() {
        val picked = BrowserInstall.pick(release("arm64-v8a", "x86_64"), listOf("x86_64", "arm64-v8a"))!!
        assertEquals("v160.0.1-dt.5", picked.tag)
        assertTrue(picked.url.endsWith("droidtop-browser-x86_64.apk"))
        assertEquals(134_000_000L, picked.size)
        assertNull(BrowserInstall.pick(release("arm64-v8a"), listOf("x86_64")))
    }

    @Test
    fun `the installed version is current only when it is the release's`() {
        assertTrue(BrowserInstall.isCurrent("160.0.1-dt.5", "v160.0.1-dt.5"))
        assertFalse(BrowserInstall.isCurrent("160.0.1-dt.3", "v160.0.1-dt.5"))
        assertFalse(BrowserInstall.isCurrent(null, "v160.0.1-dt.5"))
    }

    @Test
    fun `droidtop pins the browser's derived certificate as official`() {
        assertEquals(
            setOf("01c31027da4c4623639563fdb7977d5d62be4182ffcb5c73eacf3bf0e88b3705"),
            AppInterfaceTrust.OFFICIAL[BrowserInstall.PACKAGE],
        )
    }
}
