package dev.droidtop.runtime.systemstatus

import dev.droidtop.library.settings.UiMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one volume write path and Kid's cap (VolumeControl.kt, Droidtop/tracker#414 slice C19). */
class VolumeControlTest {
    @Test fun `Kid's cap clamps every stream's slider, other modes are not capped`() {
        // Media 0..15, ring 0..7: a 50% cap is 7 and 3.
        assertEquals(7, VolumeControl.clamp(15, max = 15, mode = UiMode.KID, capPercent = 50))
        assertEquals(3, VolumeControl.clamp(7, max = 7, mode = UiMode.KID, capPercent = 50))
        assertEquals(5, VolumeControl.clamp(5, max = 15, mode = UiMode.KID, capPercent = 50))
        assertEquals(15, VolumeControl.clamp(15, max = 15, mode = UiMode.FULL, capPercent = 50))
        assertEquals(15, VolumeControl.clamp(15, max = 15, mode = UiMode.KIOSK, capPercent = 50))
    }

    @Test fun `no cap, out-of-range values, and a tiny cap still leaves some sound`() {
        assertEquals(15, VolumeControl.ceiling(15, UiMode.KID, 0))
        assertEquals(0, VolumeControl.clamp(-3, max = 15, mode = UiMode.KID, capPercent = 0))
        assertEquals(1, VolumeControl.ceiling(2, UiMode.KID, 30))
    }
}
