package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.FlutterPointerPacket as P
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A plugin screen's touches packed without the input service (Droidtop/tracker#511): the engine's layout, field for field. */
class FlutterPointerPacketTest {
    private val stride = P.FIELD_COUNT * P.BYTES_PER_FIELD

    private fun finger(id: Int, x: Float, y: Float) =
        P.Pointer(id = id, toolType = P.TOOL_TYPE_FINGER, x = x, y = y, pressure = 0.5f, size = 0.1f, toolMajor = 3f, toolMinor = 2f, orientation = 0f)

    private fun long(packet: ByteBuffer, pointer: Int, field: Int) = packet.getLong(pointer * stride + field * 8)
    private fun double(packet: ByteBuffer, pointer: Int, field: Int) = packet.getDouble(pointer * stride + field * 8)

    @Test
    fun `a tap is one pointer in pointer_data order with a fixed pressure range`() {
        val packet = P.pack(P.TouchEvent(P.ACTION_DOWN, 0, 1_000L, 0, listOf(finger(2, 10f, 20f))))!!
        assertEquals(stride, packet.position())
        assertEquals(1_000_000L, long(packet, 0, 1))
        assertEquals(P.DOWN.toLong(), long(packet, 0, 2))
        assertEquals(P.KIND_TOUCH.toLong(), long(packet, 0, 3))
        assertEquals(((2 shl 3) or P.TOOL_TYPE_FINGER).toLong(), long(packet, 0, 5))
        assertEquals(10.0, double(packet, 0, 7), 0.0)
        assertEquals(20.0, double(packet, 0, 8), 0.0)
        assertEquals(0.5, double(packet, 0, 14), 1e-6)
        assertEquals(0.0, double(packet, 0, 15), 0.0)
        assertEquals(1.0, double(packet, 0, 16), 0.0)
        assertEquals(1.0, double(packet, 0, 33), 0.0)
    }

    @Test
    fun `lifting a finger sends the other fingers as batched moves, then the up, then a remove`() {
        val packet = P.pack(P.TouchEvent(P.ACTION_POINTER_UP, 1, 5L, 0, listOf(finger(0, 1f, 1f), finger(1, 2f, 2f))))!!
        assertEquals(3 * stride, packet.position())
        assertEquals(P.MOVE.toLong(), long(packet, 0, 2))
        assertEquals(1L, long(packet, 0, 26))
        assertEquals(P.UP.toLong(), long(packet, 1, 2))
        assertEquals(P.REMOVE.toLong(), long(packet, 2, 2))
    }

    @Test
    fun `a move carries every pointer and how many there are`() {
        val packet = P.pack(P.TouchEvent(P.ACTION_MOVE, 0, 5L, 0, listOf(finger(0, 1f, 1f), finger(1, 2f, 2f))))!!
        assertEquals(2 * stride, packet.position())
        assertEquals((2L or (2L shl 8)), long(packet, 1, 26))
    }

    @Test
    fun `a mouse keeps its buttons and an unknown action sends nothing`() {
        val mouse = finger(0, 1f, 1f).copy(toolType = P.TOOL_TYPE_MOUSE)
        val packet = P.pack(P.TouchEvent(P.ACTION_DOWN, 0, 5L, 1, listOf(mouse)))!!
        assertEquals(P.KIND_MOUSE.toLong(), long(packet, 0, 3))
        assertEquals(1L, long(packet, 0, 11))
        assertNull(P.pack(P.TouchEvent(8, 0, 5L, 0, listOf(finger(0, 1f, 1f)))))
    }
}
