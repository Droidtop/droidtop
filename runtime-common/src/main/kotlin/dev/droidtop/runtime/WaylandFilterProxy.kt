package dev.droidtop.runtime

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import kotlin.concurrent.thread

/**
 * A Wayland socket that reaches the compositor with the clipboard kept out (docs/SPEC.md 3d "Clipboard per container",
 * Droidtop/tracker#787 slice 2). It listens on [listen]; each client that connects gets its own connection to the
 * compositor's socket ([upstream], looked up per client, since the compositor names it when it starts), and every
 * message both ways goes through that client's [WaylandClipboardFilter]. File descriptors (a buffer's memory, a
 * keymap) travel with the bytes they came with, so each still arrives no later than the message that uses it.
 *
 * It runs as droidtop itself, one per container whose clipboard is off, two threads per client.
 */
class WaylandFilterProxy(
    private val listen: File,
    private val upstream: () -> File?,
) : Closeable {
    private val bound = LocalSocket(LocalSocket.SOCKET_STREAM)
    private val server: LocalServerSocket

    @Volatile private var closed = false

    init {
        listen.parentFile?.mkdirs()
        // A socket file left by an earlier run is stale.
        listen.delete()
        bound.bind(LocalSocketAddress(listen.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        server = LocalServerSocket(bound.fileDescriptor)
        // The container's programs run as droidtop on proot and as root on droidspaces; both may connect.
        runCatching { Os.chmod(listen.absolutePath, 0x1b6) } // 0666
        thread(name = "wayland-filter-accept", isDaemon = true) { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!closed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            val target = upstream()
            val compositor = LocalSocket(LocalSocket.SOCKET_STREAM)
            try {
                compositor.connect(LocalSocketAddress(checkNotNull(target) { "no compositor" }.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            } catch (e: Exception) {
                Log.w(TAG, "No compositor for a client on ${listen.path}: ${e.message}")
                runCatching { client.close() }
                runCatching { compositor.close() }
                continue
            }
            val filter = WaylandClipboardFilter()
            val close: () -> Unit = {
                runCatching { client.shutdownInput() }
                runCatching { client.close() }
                runCatching { compositor.shutdownInput() }
                runCatching { compositor.close() }
            }
            pump(client, compositor, close) { synchronized(filter) { filter.fromClient(it) } }
            pump(compositor, client, close) { synchronized(filter) { filter.fromServer(it) } }
        }
    }

    private fun pump(
        from: LocalSocket,
        to: LocalSocket,
        close: () -> Unit,
        decide: (java.nio.ByteBuffer) -> WaylandClipboardFilter.Verdict,
    ) {
        thread(name = "wayland-filter", isDaemon = true) {
            val framer = WaylandClipboardFilter.Framer()
            val buffer = ByteArray(BUFFER)
            val fds = ArrayList<FileDescriptor>()
            val input = from.inputStream
            val output = to.outputStream
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    from.ancillaryFileDescriptors?.let { fds += it }
                    val out = java.io.ByteArrayOutputStream(n)
                    for (message in framer.add(buffer, n)) {
                        when (decide(message)) {
                            WaylandClipboardFilter.Verdict.PASS -> out.write(message.array())
                            WaylandClipboardFilter.Verdict.DROP -> Unit
                            WaylandClipboardFilter.Verdict.CUT_OFF -> {
                                Log.w(TAG, "A client on ${listen.path} asked for a global it was not shown; closed")
                                return@thread
                            }
                        }
                    }
                    if (out.size() == 0) continue
                    if (fds.isNotEmpty()) to.setFileDescriptorsForSend(fds.toTypedArray())
                    output.write(out.toByteArray())
                    output.flush()
                    // Sent: droidtop's own copies are closed.
                    fds.forEach { runCatching { Os.close(it) } }
                    fds.clear()
                }
            } catch (e: IOException) {
                // Either side went away; closing both ends the other pump.
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "A broken stream on ${listen.path}: ${e.message}")
            } finally {
                fds.forEach { runCatching { Os.close(it) } }
                close()
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        runCatching { bound.close() }
        listen.delete()
    }

    private companion object {
        const val TAG = "droidtop.WaylandFilter"
        const val BUFFER = 64 * 1024
    }
}
