package dev.droidtop.library.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** See [esDeGameOverrideImage] for the ES-DE source of every rule here. */
class EsDeGameOverrideTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun override(name: String): String {
        val dir = File(temp.root, "overrides/nes")
        dir.mkdirs()
        val file = File(dir, name)
        file.writeText("x")
        return file.absolutePath
    }

    @Test
    fun `the extensions are tried in ES-DE's own order`() {
        override("sonic.png")
        val jpg = override("sonic.jpg")
        assertEquals(
            jpg,
            esDeGameOverrideImage("${temp.root}/overrides", "nes", "sonic", "/theme/back.png"),
        )
    }

    @Test
    fun `a declared path without a trailing separator still resolves`() {
        val png = override("sonic.png")
        assertEquals(png, esDeGameOverrideImage("${temp.root}/overrides", "nes", "sonic", null))
        assertEquals(png, esDeGameOverrideImage("${temp.root}/overrides/", "nes", "sonic", null))
    }

    @Test
    fun `no override file falls back to the path the element declared`() {
        assertEquals(
            "/theme/back.png",
            esDeGameOverrideImage("${temp.root}/overrides", "nes", "missing", "/theme/back.png"),
        )
        assertNull(esDeGameOverrideImage("${temp.root}/overrides", "nes", "missing", null))
    }

    @Test
    fun `no system or basename to address the override by keeps the declared path`() {
        assertEquals("/theme/back.png", esDeGameOverrideImage("/x", null, "sonic", "/theme/back.png"))
        assertEquals("/theme/back.png", esDeGameOverrideImage("/x", "nes", null, "/theme/back.png"))
    }
}
