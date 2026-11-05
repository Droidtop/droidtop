package dev.droidtop.runtime.windows.ui.screen.xserver

import org.junit.Assert.assertEquals
import org.junit.Test

/** The vkBasalt sharpening a prefix's setting writes (Droidtop/tracker#731). */
class VkBasaltConfigTest {
    @Test
    fun `cas and dls carry their strengths as fractions, clamped`() {
        assertEquals("effects=cas;casSharpness=0.5;enableOnLaunch=True", buildVkBasaltConfig("CAS", 50, 0))
        assertEquals(
            "effects=dls;dlsSharpness=1.0;dlsDenoise=0.0;enableOnLaunch=True",
            buildVkBasaltConfig(" dls ", 250, -5),
        )
    }

    @Test
    fun `an unknown or empty effect writes nothing`() {
        assertEquals("", buildVkBasaltConfig("", 50, 50))
        assertEquals("", buildVkBasaltConfig("smaa", 50, 50))
    }
}
