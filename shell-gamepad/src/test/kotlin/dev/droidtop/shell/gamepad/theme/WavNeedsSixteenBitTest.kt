package dev.droidtop.shell.gamepad.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [needsSixteenBit]: 8, 24 and 32 bit integer PCM and 32 and 64 bit float are converted; 16 bit PCM and other formats are not. */
class WavNeedsSixteenBitTest {
    private fun info(tag: Int, bits: Int, channels: Int = 2, data: Long = 1000L, encoding: Int = tag) =
        WavInfo(formatTag = tag, channels = channels, sampleRate = 44100L, byteRate = 176400L, bitsPerSample = bits, dataBytes = data, encoding = encoding)

    @Test
    fun `integer pcm other than 16 bit needs converting`() {
        assertTrue(info(1, 8).needsSixteenBit())
        assertTrue(info(1, 24).needsSixteenBit())
        assertTrue(info(1, 32).needsSixteenBit())
        assertFalse(info(1, 16).needsSixteenBit())
    }

    @Test
    fun `float samples need converting and a compressed format does not`() {
        assertTrue(info(3, 32).needsSixteenBit())
        assertTrue(info(3, 64).needsSixteenBit())
        assertFalse(info(2, 4).needsSixteenBit())
        assertTrue(info(0xFFFE, 24, encoding = 1).needsSixteenBit())
    }

    @Test
    fun `channel count and size outside the limits are left alone`() {
        assertFalse(info(1, 32, channels = 0).needsSixteenBit())
        assertFalse(info(1, 32, channels = 9).needsSixteenBit())
        assertFalse(info(1, 32, data = 0L).needsSixteenBit())
        assertTrue(info(1, 32, data = 8L * 1024L * 1024L).needsSixteenBit())
        assertFalse(info(1, 32, data = 8L * 1024L * 1024L + 1L).needsSixteenBit())
    }
}
