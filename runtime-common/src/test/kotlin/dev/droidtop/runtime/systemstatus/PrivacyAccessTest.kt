package dev.droidtop.runtime.systemstatus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Privacy card's reading of Android's own record (PrivacyAccess.kt, Droidtop/tracker#414 slice C17). */
class PrivacyAccessTest {
    // The shape of `dumpsys appops --op FINE_LOCATION` on Android 13 (emulator-5566, 2026-10-09), names shortened.
    private val dump = """
          Uid u0a117:
            state=cch
            Package com.example.assistant:
              FINE_LOCATION (allow):
                null=[
                  Access: [fg-s] 2026-10-09 15:23:47.654 (-4h42m14s943ms)
                ]
          Uid u0a120:
            Package com.example.maps:
              FINE_LOCATION (allow):
                network=[
                  Access: [fg-s] 2026-10-09 16:01:04.822 (-4h4m57s775ms)
                ]
                gcm=[
                  Access: [fg-s] 2026-10-09 15:30:04.789 (-12m3s1ms)
                ]
          Uid u0a121:
            Package com.example.old:
              FINE_LOCATION (allow):
                null=[
                  Access: [fg-s] 2026-10-07 10:00:00.000 (-2d3h0m0s0ms)
                ]
    """.trimIndent()

    @Test fun `each app's latest use within a day, most recent first`() {
        val uses = PrivacyAccess.parse(PrivacyAccess.Kind.LOCATION, dump)
        assertEquals(listOf("com.example.maps", "com.example.assistant"), uses.map { it.packageName })
        assertEquals(12 * 60_000L + 3_001L, uses.first().agoMs)
    }

    @Test fun `relative times read in milliseconds`() {
        assertEquals(4 * 3_600_000L + 42 * 60_000L + 14_000L + 943L, PrivacyAccess.parseAgo("-4h42m14s943ms"))
        assertEquals(2 * 86_400_000L + 3 * 3_600_000L, PrivacyAccess.parseAgo("-2d3h0m0s0ms"))
        assertNull(PrivacyAccess.parseAgo("+5s"))
    }

    @Test fun `ago reads as words`() {
        assertEquals("just now", PrivacyAccess.agoText(5_000))
        assertEquals("12 min ago", PrivacyAccess.agoText(12 * 60_000L + 3_001L))
        assertEquals("4 h ago", PrivacyAccess.agoText(4 * 3_600_000L + 1))
    }
}
