package dev.droidtop.stores.steam

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A large app's product info arrives as a file on Steam's content servers (Droidtop/tracker#956): the console held
 * 1,150 owned games as rows with no name and no type, read from answers whose inline key-values were empty.
 */
class SteamPicsTest {
    private val appInfo = """
        "appinfo"
        {
            "appid"		"570"
            "common"
            {
                "name"		"Dota 2"
                "type"		"Game"
            }
        }
    """.trimIndent()

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    @Test
    fun `a fetched appinfo file becomes a named game row`() {
        val kv = SteamPics.appInfoFile(gzip(appInfo.toByteArray() + byteArrayOf(0)))
        assertNotNull(kv)
        val app = SteamPics.appFrom(kv!!)
        assertEquals("Dota 2", app.name)
        assertEquals(AppType.game, app.type)
        assertFalse(SteamPics.isPlaceholder(app))
    }

    @Test
    fun `plain text is read too, and an empty file is nothing`() {
        assertEquals("Dota 2", SteamPics.appInfoFile(appInfo.toByteArray())!!["common"]["name"].value)
        assertNull(SteamPics.appInfoFile(gzip(ByteArray(0))))
        assertNull(SteamPics.appInfoFile(ByteArray(0)))
    }

    @Test
    fun `a row with no name and no type is read again`() {
        assertTrue(SteamPics.isPlaceholder(SteamApp(id = 570)))
        assertFalse(SteamPics.isPlaceholder(SteamApp(id = 570, name = "Dota 2")))
        assertFalse(SteamPics.isPlaceholder(SteamApp(id = 570, type = AppType.game)))
    }
}
