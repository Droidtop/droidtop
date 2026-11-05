package dev.droidtop.shell.gamepad

import org.junit.Assert.assertEquals
import org.junit.Test

/** A status value's box is sized to the widest value of its shape, so the row does not move (Droidtop/tracker#886). */
class CompactStatusTest {
    @Test fun `each shape reserves its widest value`() {
        assertEquals("8888 MB/888.8 GB", compactTemplate("1.2/3.4 GB"))
        assertEquals("8888 MB/888.8 GB", compactTemplate("640 MB"))
        assertEquals("88/88", compactTemplate("3/40"))
        assertEquals("100%", compactTemplate("7%"))
    }
}
