package dev.droidtop.shell.gamepad

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Text size sample's scale against the size in force (Droidtop/tracker#673). */
class CatalogChoicePopupTest {
    @Test fun `the sample is drawn relative to the size in force`() {
        assertEquals(1.5f, sampleRatio(1.5f, 1.0f), 0.001f)
        assertEquals(1.0f, sampleRatio(1.3f, 1.3f), 0.001f)
        assertEquals(1.0f, sampleRatio(1.3f, null), 0.001f)
        assertEquals(0.5f, sampleRatio(0.1f, 1.0f), 0.001f)
    }
}
