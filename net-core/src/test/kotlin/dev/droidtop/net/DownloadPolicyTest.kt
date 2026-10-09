package dev.droidtop.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {
    private val mb = 1024L * 1024L
    private val defaults = DownloadSettings()
    private fun wifi(minute: Int = 12 * 60, playing: Boolean = false) = Conditions(NetworkClass.UNMETERED, minute, playing)
    private fun mobile(minute: Int = 12 * 60, playing: Boolean = false) = Conditions(NetworkClass.METERED, minute, playing)

    @Test
    fun `no network holds every download and says so`() {
        val off = Conditions(NetworkClass.OFFLINE, 0, false)
        assertEquals(Verdict.Hold("Waiting for a network"), DownloadPolicy.decide(defaults, off, JobFacts(10 * mb)))
    }

    @Test
    fun `wifi only holds on a metered network whatever the size`() {
        val s = defaults.copy(network = NetworkRule.WIFI_ONLY)
        assertEquals(Verdict.Hold("Waiting for Wi-Fi"), DownloadPolicy.decide(s, mobile(), JobFacts(1 * mb)))
        assertEquals(Verdict.Go, DownloadPolicy.decide(s, wifi(), JobFacts(5000 * mb)))
    }

    @Test
    fun `the default lets a small download use mobile data and holds a big or unknown one`() {
        assertEquals(Verdict.Go, DownloadPolicy.decide(defaults, mobile(), JobFacts(20 * mb)))
        assertEquals(Verdict.Hold("Waiting for Wi-Fi"), DownloadPolicy.decide(defaults, mobile(), JobFacts(500 * mb)))
        assertEquals(Verdict.Hold("Waiting for Wi-Fi"), DownloadPolicy.decide(defaults, mobile(), JobFacts(0)))
    }

    @Test
    fun `any connection never holds for the network`() {
        val s = defaults.copy(network = NetworkRule.ANY)
        assertEquals(Verdict.Go, DownloadPolicy.decide(s, mobile(), JobFacts(9000 * mb)))
        assertTrue(DownloadPolicy.allowsMetered(s, 0))
        assertFalse(DownloadPolicy.allowsMetered(defaults, 0))
    }

    @Test
    fun `the window and not while playing apply to automatic downloads only`() {
        val s = defaults.copy(windowEnabled = true, windowStartMinute = 2 * 60, windowEndMinute = 6 * 60)
        val automatic = JobFacts(10 * mb, automatic = true)
        val asked = JobFacts(10 * mb, automatic = false)
        assertEquals(Verdict.Hold("Waiting for 02:00"), DownloadPolicy.decide(s, wifi(minute = 12 * 60), automatic))
        assertEquals(Verdict.Go, DownloadPolicy.decide(s, wifi(minute = 3 * 60), automatic))
        assertEquals(Verdict.Go, DownloadPolicy.decide(s, wifi(minute = 12 * 60), asked))
        assertEquals(Verdict.Hold("Waiting until you stop playing"), DownloadPolicy.decide(defaults, wifi(playing = true), automatic))
        assertEquals(Verdict.Go, DownloadPolicy.decide(defaults, wifi(playing = true), asked))
        assertEquals(Verdict.Go, DownloadPolicy.decide(defaults.copy(whilePlaying = true), wifi(playing = true), automatic))
    }

    @Test
    fun `a window may run past midnight and equal ends are always open`() {
        assertTrue(DownloadPolicy.inWindow(23 * 60, 22 * 60, 6 * 60))
        assertTrue(DownloadPolicy.inWindow(2 * 60, 22 * 60, 6 * 60))
        assertFalse(DownloadPolicy.inWindow(12 * 60, 22 * 60, 6 * 60))
        assertFalse(DownloadPolicy.inWindow(6 * 60, 2 * 60, 6 * 60))
        assertTrue(DownloadPolicy.inWindow(5 * 60, 3 * 60, 3 * 60))
        assertEquals("22:05", DownloadPolicy.clock(22 * 60 + 5))
    }

    @Test
    fun `a game keeps itself up to date by its own choice and otherwise by the default`() {
        val off = defaults
        val on = defaults.copy(autoUpdateDefault = true)
        assertFalse(DownloadPolicy.keepsUpToDate(off, AutoUpdate.FOLLOW))
        assertTrue(DownloadPolicy.keepsUpToDate(on, AutoUpdate.FOLLOW))
        assertTrue(DownloadPolicy.keepsUpToDate(off, AutoUpdate.ON))
        assertFalse(DownloadPolicy.keepsUpToDate(on, AutoUpdate.OFF))
        assertEquals(AutoUpdate.FOLLOW, AutoUpdate.fromKey("nonsense"))
        assertEquals(NetworkRule.SMALL_ON_MOBILE, NetworkRule.fromKey(null))
    }
}
