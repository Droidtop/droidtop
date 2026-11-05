package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Test

class WineMediaDecodeTest {
    private val dir = "/data/user/0/app/files/gst-droidtop"

    @Test
    fun `the plugin's directory goes before the image's own plugins`() {
        assertEquals("$dir:/img/usr/lib/gstreamer-1.0", WineMediaDecode.withPluginDir("/img/usr/lib/gstreamer-1.0", dir))
    }

    @Test
    fun `an empty path gets the plugin's directory alone`() {
        assertEquals(dir, WineMediaDecode.withPluginDir("", dir))
        assertEquals(dir, WineMediaDecode.withPluginDir(null, dir))
    }

    @Test
    fun `the directory is never listed twice`() {
        assertEquals("$dir:/a", WineMediaDecode.withPluginDir("/a:$dir", dir))
    }
}
