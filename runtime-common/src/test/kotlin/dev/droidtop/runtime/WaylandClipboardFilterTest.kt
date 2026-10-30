package dev.droidtop.runtime

import dev.droidtop.runtime.WaylandClipboardFilter.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The clipboard kept from one Wayland client (docs/SPEC.md 3d "Clipboard per container", Droidtop/tracker#787). */
class WaylandClipboardFilterTest {
    private val registry = 2

    private fun message(sender: Int, opcode: Int, vararg args: Any): ByteArray {
        val body = ArrayList<Byte>()
        fun int(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array().forEach { body += it }
        for (arg in args) when (arg) {
            is Int -> int(arg)
            is String -> {
                val bytes = arg.toByteArray() + 0.toByte()
                int(bytes.size)
                bytes.forEach { body += it }
                repeat((4 - bytes.size % 4) % 4) { body += 0.toByte() }
            }
        }
        val size = 8 + body.size
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(sender).putInt((size shl 16) or opcode).put(body.toByteArray()).array()
    }

    private fun WaylandClipboardFilter.client(bytes: ByteArray) = fromClient(ByteBuffer.wrap(bytes))
    private fun WaylandClipboardFilter.server(bytes: ByteArray) = fromServer(ByteBuffer.wrap(bytes))

    private fun withRegistry() = WaylandClipboardFilter().also {
        assertEquals(Verdict.PASS, it.client(message(1, 1, registry)))
    }

    @Test
    fun `every clipboard global is kept from the client, and the rest pass`() {
        val filter = withRegistry()
        assertEquals(Verdict.PASS, filter.server(message(registry, 0, 1, "wl_compositor", 6)))
        assertEquals(Verdict.DROP, filter.server(message(registry, 0, 2, "wl_data_device_manager", 3)))
        assertEquals(Verdict.DROP, filter.server(message(registry, 0, 3, "zwp_primary_selection_device_manager_v1", 1)))
        assertEquals(Verdict.DROP, filter.server(message(registry, 0, 4, "ext_data_control_manager_v1", 1)))
        assertEquals(Verdict.DROP, filter.server(message(registry, 0, 5, "zwlr_data_control_manager_v1", 2)))
        assertEquals(Verdict.PASS, filter.server(message(registry, 0, 6, "xdg_wm_base", 5)))
    }

    @Test
    fun `binding a shown global passes, and a hidden or guessed name cuts the client off`() {
        val filter = withRegistry()
        filter.server(message(registry, 0, 1, "wl_compositor", 6))
        filter.server(message(registry, 0, 2, "wl_data_device_manager", 3))
        assertEquals(Verdict.PASS, filter.client(message(registry, 0, 1, "wl_compositor", 6, 3)))
        assertEquals(Verdict.CUT_OFF, filter.client(message(registry, 0, 2, "wl_data_device_manager", 3, 4)))
        // Sent before the compositor answered: the name was never shown.
        assertEquals(Verdict.CUT_OFF, withRegistry().client(message(registry, 0, 7, "wl_seat", 7, 3)))
    }

    @Test
    fun `a hidden global's removal is kept from the client too`() {
        val filter = withRegistry()
        filter.server(message(registry, 0, 2, "wl_data_device_manager", 3))
        filter.server(message(registry, 0, 8, "wl_output", 4))
        assertEquals(Verdict.DROP, filter.server(message(registry, 1, 2)))
        assertEquals(Verdict.PASS, filter.server(message(registry, 1, 8)))
    }

    @Test
    fun `messages from other objects pass untouched`() {
        val filter = withRegistry()
        assertEquals(Verdict.PASS, filter.server(message(9, 0, 2, "wl_data_device_manager", 3)))
        assertEquals(Verdict.PASS, filter.client(message(9, 0, 2)))
    }

    @Test
    fun `the framer cuts whole messages from any pieces`() {
        val a = message(1, 1, registry)
        val b = message(registry, 0, 1, "wl_compositor", 6)
        val stream = a + b
        val framer = WaylandClipboardFilter.Framer()
        assertEquals(0, framer.add(stream.copyOfRange(0, 5), 5).size)
        val first = framer.add(stream.copyOfRange(5, a.size + 3), a.size - 2)
        assertEquals(listOf(a.toList()), first.map { it.array().toList() })
        val rest = stream.copyOfRange(a.size + 3, stream.size)
        assertEquals(listOf(b.toList()), framer.add(rest, rest.size).map { it.array().toList() })
    }

    @Test
    fun `a size no message can have is a broken stream`() {
        val broken = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt((6 shl 16) or 1).array()
        assertThrows(IllegalArgumentException::class.java) { WaylandClipboardFilter.Framer().add(broken, broken.size) }
    }
}
