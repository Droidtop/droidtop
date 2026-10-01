package dev.droidtop.runtime.linux.noroot

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PulseSinkSuspendTest {
    @Test
    fun suspendAllIsCommand70WithInvalidIndexEmptyNameAndBoolean() {
        val packet = PulseSinkSuspend.suspendAllPacket(tag = 1, suspend = true)
        val expected = byteArrayOf(
            'L'.code.toByte(), 0, 0, 0, 70,
            'L'.code.toByte(), 0, 0, 0, 1,
            'L'.code.toByte(), -1, -1, -1, -1,
            't'.code.toByte(), 0,
            '1'.code.toByte(),
        )
        assertArrayEquals(expected, packet)
        assertEquals('0'.code.toByte(), PulseSinkSuspend.suspendAllPacket(tag = 1, suspend = false).last())
    }

    @Test
    fun authCarriesVersion32AndA256ByteCookie() {
        val packet = PulseSinkSuspend.authPacket(tag = 0)
        val buffer = ByteBuffer.wrap(packet)
        assertEquals('L'.code.toByte(), buffer.get()); assertEquals(8, buffer.int)
        assertEquals('L'.code.toByte(), buffer.get()); assertEquals(0, buffer.int)
        assertEquals('L'.code.toByte(), buffer.get()); assertEquals(32, buffer.int)
        assertEquals('x'.code.toByte(), buffer.get()); assertEquals(256, buffer.int)
        assertEquals(256, buffer.remaining())
    }

    @Test
    fun frameIsTheFiveWordControlDescriptor() {
        val framed = PulseSinkSuspend.frame(byteArrayOf(1, 2, 3))
        val buffer = ByteBuffer.wrap(framed)
        assertEquals(3, buffer.int)
        assertEquals(-1, buffer.int)
        assertEquals(0, buffer.int); assertEquals(0, buffer.int); assertEquals(0, buffer.int)
        assertEquals(3, buffer.remaining())
    }
}
