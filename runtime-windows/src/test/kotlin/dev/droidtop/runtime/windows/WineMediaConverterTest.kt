package dev.droidtop.runtime.windows

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class WineMediaConverterTest {
    @Test
    fun `every variable Proton's launcher sets is set, the databases in the prefix and the blanks beside them`() {
        val env = WineMediaConverter.env(File("/p"), File("/b"))
        assertEquals(
            setOf(
                "MEDIACONV_AUDIO_DUMP_FILE", "MEDIACONV_VIDEO_DUMP_FILE",
                "MEDIACONV_AUDIO_TRANSCODED_FILE", "MEDIACONV_VIDEO_TRANSCODED_FILE",
                "MEDIACONV_BLANK_VIDEO_FILE", "MEDIACONV_BLANK_AUDIO_FILE",
            ),
            env.keys,
        )
        assertEquals("/p/mediaconv/audiov2.foz", env["MEDIACONV_AUDIO_DUMP_FILE"])
        assertEquals("/b/blank.ptna", env["MEDIACONV_BLANK_AUDIO_FILE"])
    }
}
