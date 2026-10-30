package dev.droidtop.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The clipboard, kept from one Wayland client (docs/SPEC.md 3d "Clipboard per container", Droidtop/tracker#787 slice
 * 2). A container whose clipboard is off reaches the compositor only through droidtop's proxy, and this is the proxy's
 * whole decision, without sockets in it: which messages pass. Pure, one instance per client connection.
 *
 * Every way a client reaches the clipboard is a global it binds from the registry: the core `wl_data_device_manager`,
 * the two primary-selection managers, and the two data-control managers, which read and set the selection with no
 * focus at all (sway creates both, and host-bridge's own clipboard bridge uses the ext one, SPEC 6d). Their
 * `wl_registry.global` events never reach the client, nor their `global_remove`. A client that binds any name it was
 * not shown is cut off: names are the compositor's numbers, so a guessed one would otherwise reach a hidden global.
 *
 * The wire format (Wayland's own, native byte order, little-endian on every Android ABI): each message is a sender
 * object id, then its size (high 16 bits, header included, a multiple of 4) and opcode (low 16 bits), then its
 * arguments, a string as its length (with the NUL) and its bytes padded to 4.
 */
class WaylandClipboardFilter(private val hidden: Set<String> = CLIPBOARD_GLOBALS) {
    /** What happens to one message. */
    enum class Verdict { PASS, DROP, CUT_OFF }

    private val registries = HashSet<Int>()
    private val shown = HashSet<Int>()
    private val hiddenNames = HashSet<Int>()

    /** A message the client sent. [message] is one whole message, as [Framer] cuts them. */
    fun fromClient(message: ByteBuffer): Verdict {
        val m = message.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val sender = m.getInt(0)
        val opcode = m.getInt(4) and 0xFFFF
        when {
            sender == DISPLAY_ID && opcode == DISPLAY_GET_REGISTRY -> registries += m.getInt(HEADER)
            sender in registries && opcode == REGISTRY_BIND -> if (m.getInt(HEADER) !in shown) return Verdict.CUT_OFF
        }
        return Verdict.PASS
    }

    /** A message the compositor sent. */
    fun fromServer(message: ByteBuffer): Verdict {
        val m = message.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val sender = m.getInt(0)
        if (sender !in registries) return Verdict.PASS
        val opcode = m.getInt(4) and 0xFFFF
        val name = m.getInt(HEADER)
        return when (opcode) {
            REGISTRY_GLOBAL -> {
                if (string(m, HEADER + 4)?.let { it in hidden } == true) {
                    hiddenNames += name
                    Verdict.DROP
                } else {
                    shown += name
                    Verdict.PASS
                }
            }
            REGISTRY_GLOBAL_REMOVE -> {
                shown -= name
                if (name in hiddenNames) Verdict.DROP else Verdict.PASS
            }
            else -> Verdict.PASS
        }
    }

    /** The string argument at [offset]: its length with the NUL, then its bytes. Null when it runs past the message. */
    private fun string(m: ByteBuffer, offset: Int): String? {
        val length = m.getInt(offset)
        if (length <= 0 || offset + 4 + length > m.limit()) return null
        val bytes = ByteArray(length - 1)
        for (i in bytes.indices) bytes[i] = m.get(offset + 4 + i)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Cuts a byte stream into whole messages. Bytes arrive in any pieces; [add] keeps what does not yet make a whole
     * message for the next call. A size that cannot be a message (under the header, or not a multiple of 4) is a
     * broken stream, and [add] throws: the proxy then closes the connection.
     */
    class Framer {
        private var pending = ByteArray(0)

        fun add(bytes: ByteArray, length: Int): List<ByteBuffer> {
            val all = if (pending.isEmpty()) bytes.copyOf(length) else pending + bytes.copyOf(length)
            val messages = ArrayList<ByteBuffer>()
            var at = 0
            while (all.size - at >= HEADER) {
                val size = ByteBuffer.wrap(all, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int ushr 16
                require(size >= HEADER && size % 4 == 0) { "not a Wayland message: size $size" }
                if (all.size - at < size) break
                messages += ByteBuffer.wrap(all.copyOfRange(at, at + size))
                at += size
            }
            pending = all.copyOfRange(at, all.size)
            return messages
        }
    }

    companion object {
        /** Every global that reaches the clipboard, by interface name. */
        val CLIPBOARD_GLOBALS = setOf(
            "wl_data_device_manager",
            "zwp_primary_selection_device_manager_v1",
            "gtk_primary_selection_device_manager",
            "ext_data_control_manager_v1",
            "zwlr_data_control_manager_v1",
        )

        const val HEADER = 8
        private const val DISPLAY_ID = 1
        private const val DISPLAY_GET_REGISTRY = 1
        private const val REGISTRY_BIND = 0
        private const val REGISTRY_GLOBAL = 0
        private const val REGISTRY_GLOBAL_REMOVE = 1
    }
}
