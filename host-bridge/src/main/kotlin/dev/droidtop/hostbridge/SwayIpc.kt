package dev.droidtop.hostbridge

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A client of sway's IPC (`man sway-ipc`), for what wlr-foreign-toplevel-management cannot ask sway to do:
 * sway ignores the protocol's set_minimized, so the taskbar's Minimize moves the window to the scratchpad
 * over IPC instead ([DesktopWindows], Droidtop/tracker#391). The socket is the one sway creates in the shared
 * `XDG_RUNTIME_DIR` (ContainerLayout.findSwayIpcSocket), which host-bridge already reaches from the Android
 * side for the Wayland socket; nothing runs in the container for it.
 *
 * Blocking socket I/O, one connection per call: call it off the main thread.
 */
class SwayIpc(private val socketPath: String) {
    /** Runs [command] (sway's command syntax, criteria and commas included); true when every part succeeded. */
    fun run(command: String): Boolean = commandSucceeded(request(RUN_COMMAND, command))

    /** The layout tree, as GET_TREE returns it. */
    fun tree(): JSONObject = JSONObject(request(GET_TREE, ""))

    /** The process of the window that has the focus, as sway sees it; null with none, or 0 when sway cannot see it. */
    fun focusedPid(): Int? = focusedPid(tree())

    /**
     * A connection subscribed to sway's [events] (`window`, `workspace`, `output`, ...; sway-ipc's SUBSCRIBE), for
     * following the tree without asking for it all the time (Droidtop/tracker#910). Its [SwayEvents.next] blocks until
     * the next event; closing it from another thread ends a wait. Null when sway refused the subscription.
     */
    fun subscribe(events: List<String>): SwayEvents? {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = TIMEOUT_MS
            val payload = events.joinToString(",", "[", "]") { JSONObject.quote(it) }
            socket.outputStream.apply { write(frame(SUBSCRIBE, payload)); flush() }
            val input = DataInputStream(socket.inputStream)
            val header = ByteArray(HEADER_SIZE).also { input.readFully(it) }
            val reply = String(ByteArray(replyLength(header, SUBSCRIBE)).also { input.readFully(it) }, Charsets.UTF_8)
            if (!JSONObject(reply).optBoolean("success")) {
                socket.close()
                return null
            }
            // Events come when sway has them; an idle desktop sends none for as long as it is idle.
            socket.soTimeout = 0
            return SwayEvents(socket, input)
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun request(type: Int, payload: String): String =
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = TIMEOUT_MS
            socket.outputStream.apply { write(frame(type, payload)); flush() }
            val input = DataInputStream(socket.inputStream)
            val header = ByteArray(HEADER_SIZE).also { input.readFully(it) }
            val length = replyLength(header, type)
            String(ByteArray(length).also { input.readFully(it) }, Charsets.UTF_8)
        }

    companion object {
        // sway/include/ipc.h, enum ipc_command_type.
        internal const val RUN_COMMAND = 0
        internal const val SUBSCRIBE = 2
        internal const val GET_TREE = 4

        /** An event's message type has the high bit set (sway/include/ipc.h, IPC_EVENT_*). */
        internal const val EVENT_BIT = 1 shl 31

        private val MAGIC = "i3-ipc".toByteArray(Charsets.US_ASCII)
        internal const val HEADER_SIZE = 14
        private const val TIMEOUT_MS = 1500
        private const val MAX_REPLY = 16 shl 20

        /**
         * One message: the magic, the payload's length and the type as 32-bit integers in the host's byte order
         * (sway-ipc(7); the container runs on the same CPU as droidtop), then the payload.
         */
        internal fun frame(type: Int, payload: String): ByteArray {
            val body = payload.toByteArray(Charsets.UTF_8)
            return ByteBuffer.allocate(HEADER_SIZE + body.size).order(ByteOrder.nativeOrder())
                .put(MAGIC).putInt(body.size).putInt(type).put(body)
                .array()
        }

        /** The payload length a reply [header] announces, checked against the [type] that was asked. */
        internal fun replyLength(header: ByteArray, type: Int): Int {
            val (length, replyType) = header(header)
            if (replyType != type) throw IOException("sway answered message type $replyType to $type")
            return length
        }

        /** A message [header]'s payload length and type, the magic and the length checked. */
        internal fun header(header: ByteArray): Pair<Int, Int> {
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.nativeOrder())
            val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
            if (!magic.contentEquals(MAGIC)) throw IOException("not a sway IPC reply")
            val length = buffer.int
            val type = buffer.int
            if (length < 0 || length > MAX_REPLY) throw IOException("bad sway reply length $length")
            return length to type
        }

        /** RUN_COMMAND answers one `{"success": ...}` per command; a comma chain succeeds only when all of them did. */
        internal fun commandSucceeded(reply: String): Boolean {
            val results = JSONArray(reply)
            return results.length() > 0 && (0 until results.length()).all { results.getJSONObject(it).optBoolean("success") }
        }

        /**
         * Every window in [tree] whose app id and title are [appId] and [title], the two things the
         * foreign-toplevel protocol tells droidtop about a window (sway sets both from the same view, see
         * sway/tree/view.c and ipc-json.c ipc_json_describe_view). A missing app id (an Xwayland window) is "".
         * The scratchpad's hidden windows are included, as sway lists them under its `__i3_scratch` workspace.
         */
        /** The focused view's `pid` in [tree], walking tiled and floating nodes alike. Pure. */
        internal fun focusedPid(tree: JSONObject): Int? {
            if (tree.optBoolean("focused") && tree.has("pid")) return tree.optInt("pid")
            for (key in listOf("nodes", "floating_nodes")) {
                val children = tree.optJSONArray(key) ?: continue
                for (i in 0 until children.length()) focusedPid(children.getJSONObject(i))?.let { return it }
            }
            return null
        }

        internal fun windows(tree: JSONObject, appId: String, title: String): List<SwayWindow> = buildList {
            fun walk(node: JSONObject, floating: Boolean) {
                val isView = node.has("pid") || node.has("app_id")
                if (isView) {
                    val nodeAppId = if (node.isNull("app_id")) "" else node.optString("app_id")
                    val nodeTitle = if (node.isNull("name")) "" else node.optString("name")
                    if (nodeAppId == appId && nodeTitle == title) {
                        val rect = node.optJSONObject("rect")?.let {
                            SwayRect(it.optInt("x"), it.optInt("y"), it.optInt("width"), it.optInt("height"))
                        }
                        add(SwayWindow(node.getLong("id"), floating, node.optBoolean("focused"), rect))
                    }
                }
                node.optJSONArray("nodes")?.let { children ->
                    for (i in 0 until children.length()) walk(children.getJSONObject(i), floating)
                }
                node.optJSONArray("floating_nodes")?.let { children ->
                    for (i in 0 until children.length()) walk(children.getJSONObject(i), true)
                }
            }
            walk(tree, false)
        }
    }
}

/**
 * A subscribed connection to sway ([SwayIpc.subscribe]). [next] blocks for the next event and returns its type
 * (IPC_EVENT_*, high bit set), its payload read and dropped: what changed is read from the tree again. [close] from any
 * thread ends it; a [next] waiting then throws.
 */
class SwayEvents internal constructor(private val socket: LocalSocket, private val input: DataInputStream) : java.io.Closeable {
    fun next(): Int {
        while (true) {
            val header = ByteArray(SwayIpc.HEADER_SIZE).also { input.readFully(it) }
            val (length, type) = SwayIpc.header(header)
            input.readFully(ByteArray(length))
            if ((type and SwayIpc.EVENT_BIT) != 0) return type
        }
    }

    override fun close() {
        runCatching { socket.shutdownInput() }
        runCatching { socket.close() }
    }
}

/**
 * One window in sway's tree: its container id (criteria `con_id`), whether it floats, whether it has focus, and
 * where it is in the layout's absolute pixels ([rect], decorations included; null when the tree did not say).
 */
data class SwayWindow(val conId: Long, val floating: Boolean, val focused: Boolean, val rect: SwayRect? = null)

/** A container's place in sway's layout, absolute pixels (GET_TREE's `rect`). */
data class SwayRect(val x: Int, val y: Int, val width: Int, val height: Int)
