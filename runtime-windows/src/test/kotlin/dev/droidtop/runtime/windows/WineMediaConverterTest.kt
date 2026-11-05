package dev.droidtop.runtime.windows

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WineMediaConverterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `every variable Proton's launcher sets is set, the databases in the prefix and the blanks beside them`() {
        val env = WineMediaConverter.env(File("/p"), File("/b"))
        assertEquals(
            setOf(
                "MEDIACONV_AUDIO_DUMP_FILE", "MEDIACONV_VIDEO_DUMP_FILE",
                "MEDIACONV_AUDIO_TRANSCODED_FILE", "MEDIACONV_VIDEO_TRANSCODED_FILE",
                "MEDIACONV_BLANK_VIDEO_FILE", "MEDIACONV_BLANK_AUDIO_FILE",
                "STEAM_COMPAT_TRANSCODED_MEDIA_PATH",
            ),
            env.keys,
        )
        assertEquals("/p/mediaconv/audiov2.foz", env["MEDIACONV_AUDIO_DUMP_FILE"])
        assertEquals("/b/blank.ptna", env["MEDIACONV_BLANK_AUDIO_FILE"])
    }

    @Test
    fun `the tag files go to the prefix's own folder`() {
        assertEquals("/p/mediaconv", WineMediaConverter.env(File("/p"), File("/b"))["STEAM_COMPAT_TRANSCODED_MEDIA_PATH"])
    }

    @Test
    fun `the dump databases start afresh and Valve's transcoded media is kept`() {
        val dir = tmp.newFolder("mediaconv")
        listOf("audiov2.foz", "video.foz", "transcoded_video.foz").forEach { File(dir, it).writeText("x") }
        assertEquals(2, WineMediaConverter.resetDumps(dir))
        assertFalse(File(dir, "audiov2.foz").exists())
        assertFalse(File(dir, "video.foz").exists())
        assertTrue(File(dir, "transcoded_video.foz").exists())
    }

    @Test
    fun `asfdemux is out of autoplugging and ximagesink keeps its rank`() {
        val ranks = WineMediaConverter.GST_FEATURE_RANK.split(',').associate { it.substringBefore(':') to it.substringAfter(':') }
        assertEquals("0", ranks["asfdemux"])
        assertEquals("3000", ranks["ximagesink"])
    }
}
