package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranslationCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a game's folder name is fixed, path-safe and its own`() {
        val a = TranslationCache.key("steam:221380")
        assertEquals(a, TranslationCache.key("steam:221380"))
        assertNotEquals(a, TranslationCache.key("steam:221381"))
        assertTrue(a.matches(Regex("[0-9a-f]{24}")))
    }

    @Test
    fun `both translators point at the game's folder, FEX through Wine's Z drive`() {
        val root = tmp.newFolder("imagefs")
        val dir = TranslationCache.gameDir(root, "gog:1207658924")
        val env = TranslationCache.launchEnv(root, dir, limitMiB = 512)
        assertEquals("1", env["BOX64_DYNACACHE"])
        assertEquals(File(dir, "box64").absolutePath, env["BOX64_DYNACACHE_FOLDER"])
        assertEquals("512", env["BOX64_DYNACACHE_LIMIT"])
        assertEquals("1", env["FEX_DISKCACHE"])
        assertEquals("Z:/translation-cache/${TranslationCache.key("gog:1207658924")}/fex/", env["FEX_DISKCACHEPATH"])
        // EnvVars unescapes backslashes and splits on spaces: neither may appear in a value.
        assertTrue(env.values.none { it.contains('\\') || it.contains(' ') })
    }

    @Test
    fun `trimming drops the oldest files until the folders fit`() {
        val dir = tmp.newFolder("cache")
        val old = File(dir, "box64/old.box64").apply { parentFile.mkdirs(); writeBytes(ByteArray(600)); setLastModified(1_000_000L) }
        val mid = File(dir, "fex/mid").apply { parentFile.mkdirs(); writeBytes(ByteArray(300)); setLastModified(2_000_000L) }
        val new = File(dir, "fex/new").apply { writeBytes(ByteArray(300)); setLastModified(3_000_000L) }
        TranslationCache.trim(listOf(dir), limitBytes = 700)
        assertFalse(old.exists())
        assertTrue(mid.exists())
        assertTrue(new.exists())
    }

    @Test
    fun `folders already within the limit are left alone`() {
        val dir = tmp.newFolder("small")
        val file = File(dir, "a").apply { writeBytes(ByteArray(100)) }
        TranslationCache.trim(listOf(dir), limitBytes = 100)
        assertTrue(file.exists())
    }
}
