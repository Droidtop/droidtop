package dev.droidtop.shell.gamepad.theme

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WavNormalizeTest {
    private val dir = File.createTempFile("navdir", "").let { it.delete(); it.mkdirs(); it.deleteOnExit(); it }

    private fun wav(tag: Int, bits: Int, channels: Int, samples: ByteArray): File {
        val rate = 48000
        val buffer = ByteBuffer.allocate(44 + samples.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples.size).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16)
            .putShort(tag.toShort()).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        buffer.put("data".toByteArray()).putInt(samples.size).put(samples)
        return File.createTempFile("nav", ".wav").apply { deleteOnExit(); writeBytes(buffer.array()) }
    }

    private fun pcm16(file: File): List<Int> {
        val info = wavInfo(file)!!
        assertEquals(16, info.bitsPerSample)
        assertEquals(1, info.encoding)
        val start = info.dataOffset.toInt()
        val data = file.readBytes().copyOfRange(start, start + info.dataBytes.toInt())
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return List(data.size / 2) { b.getShort(it * 2).toInt() }
    }

    @Test
    fun thirtyTwoBitIntegerKeepsItsTopSixteenBits() {
        val data = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x40000000).putInt(-0x40000000).putInt(0).putInt(0x7FFFFFFF).array()
        val out = playableWav(wav(1, 32, 2, data), dir)
        assertEquals(listOf(0x4000, -0x4000, 0, 0x7FFF), pcm16(out))
        val info = wavInfo(out)!!
        assertEquals(2, info.channels)
        assertEquals(48000L, info.sampleRate)
    }

    @Test
    fun twentyFourBitIntegerKeepsItsTopSixteenBits() {
        val data = byteArrayOf(0x00, 0x00, 0x40, 0x00, 0x00, 0xC0.toByte())
        assertEquals(listOf(0x4000, -0x4000), pcm16(playableWav(wav(1, 24, 1, data), dir)))
    }

    @Test
    fun eightBitUnsignedIsCentred() {
        val data = byteArrayOf(128.toByte(), 255.toByte(), 0)
        assertEquals(listOf(0, 0x7F00, -0x8000), pcm16(playableWav(wav(1, 8, 1, data), dir)))
    }

    @Test
    fun floatSamplesAreScaledAndClamped() {
        val data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.5f).putFloat(-2f).putFloat(1f).array()
        assertEquals(listOf(16384, -32767, 32767), pcm16(playableWav(wav(3, 32, 1, data), dir)))
    }

    @Test
    fun sixteenBitIsLeftAlone() {
        val file = wav(1, 16, 2, ByteArray(8))
        assertSame(file, playableWav(file, dir))
    }

    @Test
    fun notAWavIsLeftAlone() {
        val file = File.createTempFile("nav", ".ogg").apply { deleteOnExit(); writeBytes(ByteArray(64)) }
        assertSame(file, playableWav(file, dir))
    }

    @Test
    fun theRewrittenFileIsReusedForTheSameSource() {
        val file = wav(1, 32, 1, ByteArray(8))
        assertEquals(playableWav(file, dir).path, playableWav(file, dir).path)
    }
}
