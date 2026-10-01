package dev.droidtop.shell.gamepad.theme

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WavDurationTest {
    private fun wav(dataBytes: Int, byteRate: Int, extraChunk: Boolean): File {
        val extra = if (extraChunk) 8 + 3 + 1 else 0
        val buffer = ByteBuffer.allocate(12 + 24 + extra + 8 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(buffer.capacity() - 8).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(2).putInt(byteRate / 4).putInt(byteRate).putShort(4).putShort(16)
        if (extraChunk) buffer.put("LIST".toByteArray()).putInt(3).put(byteArrayOf(1, 2, 3, 0))
        buffer.put("data".toByteArray()).putInt(dataBytes)
        return File.createTempFile("nav", ".wav").apply {
            deleteOnExit()
            writeBytes(buffer.array())
        }
    }

    @Test
    fun durationComesFromDataSizeOverByteRate() {
        assertEquals(500L, wavDurationMs(wav(dataBytes = 88200, byteRate = 176400, extraChunk = false)))
    }

    @Test
    fun oddSizedChunksBeforeDataArePaddedAndSkipped() {
        assertEquals(250L, wavDurationMs(wav(dataBytes = 44100, byteRate = 176400, extraChunk = true)))
    }

    @Test
    fun notAWaveFileIsNull() {
        val file = File.createTempFile("nav", ".ogg").apply { deleteOnExit(); writeBytes(ByteArray(64)) }
        assertNull(wavDurationMs(file))
    }
}
