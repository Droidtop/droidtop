package dev.droidtop.app.vpn

import android.net.ConnectivityManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.Process
import android.system.OsConstants
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Carries hev-socks5-tunnel's SOCKS5 connections to the container's VPN
 * socket ([socket], `vpn.sock` in the shared socket directory, docs/SPEC.md
 * 4a). hev speaks SOCKS5 over TCP only, and the container's endpoint is a
 * Unix socket in droidtop's private storage, so this listens on a loopback
 * port and copies bytes both ways, one connection for one connection; the
 * SOCKS5 conversation itself passes through untouched.
 *
 * A loopback port is reachable by every app on the device. On API 29 and
 * up a VPN app may ask who owns a connection
 * ([ConnectivityManager.getConnectionOwnerUid]), and anything that is not
 * droidtop's own stack is refused; below 29 the platform offers no way to
 * tell, and the port is open while the VPN is.
 */
internal class SocksUnixRelay(
    private val socket: File,
    private val connectivity: ConnectivityManager?,
) : Closeable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())

    val port: Int get() = server.localPort

    init {
        thread(name = "vpn-relay-accept", isDaemon = true) { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            if (!isOurs(client)) {
                runCatching { client.close() }
                continue
            }
            val upstream = LocalSocket()
            try {
                upstream.connect(LocalSocketAddress(socket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            } catch (e: IOException) {
                runCatching { client.close() }
                runCatching { upstream.close() }
                continue
            }
            val close = {
                runCatching { client.close() }
                runCatching { upstream.close() }
            }
            pump(client.getInputStream(), upstream.outputStream, close)
            pump(upstream.inputStream, client.getOutputStream(), close)
        }
    }

    private fun isOurs(client: Socket): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        val cm = connectivity ?: return true
        val owner = runCatching {
            cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_TCP,
                client.remoteSocketAddress as InetSocketAddress,
                client.localSocketAddress as InetSocketAddress,
            )
        }.getOrElse { return true }
        return owner == Process.myUid()
    }

    private fun pump(from: InputStream, to: OutputStream, close: () -> Unit) {
        thread(name = "vpn-relay", isDaemon = true) {
            val buffer = ByteArray(BUFFER)
            try {
                while (true) {
                    val n = from.read(buffer)
                    if (n < 0) break
                    to.write(buffer, 0, n)
                    to.flush()
                }
            } catch (_: IOException) {
                // Either side went away; closing both ends the other pump.
            } finally {
                close()
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }

    companion object {
        private const val BACKLOG = 64
        private const val BUFFER = 32 * 1024

        /** Whether something is serving [socket] right now. */
        fun answers(socket: File): Boolean = try {
            LocalSocket().use { it.connect(LocalSocketAddress(socket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)) }
            true
        } catch (_: IOException) {
            false
        } catch (e: SecurityException) {
            Log.w("droidtop.vpn", "can't reach ${socket.path}", e)
            false
        }
    }
}
