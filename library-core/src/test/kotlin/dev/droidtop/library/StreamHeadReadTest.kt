package dev.droidtop.library

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** [readHeadBytes]: exactly what the stream held, up to the cap, even when every read returns one byte. */
class StreamHeadReadTest {
    private class OneByteAtATime(private val data: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            b[off] = data[pos++]
            return 1
        }
    }

    @Test
    fun `a stream longer than the cap is cut at the cap`() {
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6)).readHeadBytes(4))
    }

    @Test
    fun `a shorter stream returns only what it held`() {
        assertArrayEquals(byteArrayOf(1, 2, 3), ByteArrayInputStream(byteArrayOf(1, 2, 3)).readHeadBytes(10))
        assertArrayEquals(byteArrayOf(), ByteArrayInputStream(byteArrayOf()).readHeadBytes(10))
    }

    @Test
    fun `short reads are joined until the cap is reached`() {
        assertArrayEquals(byteArrayOf(9, 8, 7), OneByteAtATime(byteArrayOf(9, 8, 7, 6)).readHeadBytes(3))
    }
}
