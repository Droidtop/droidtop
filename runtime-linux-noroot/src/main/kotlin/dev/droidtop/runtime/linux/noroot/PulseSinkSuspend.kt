package dev.droidtop.runtime.linux.noroot

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Suspends or resumes every sink of [HostAudioServer]'s PulseAudio over
 * its own native-protocol socket, the same request `pactl suspend-sink`
 * sends. A suspended module-aaudio-sink closes its AAudio stream
 * (module-aaudio-sink.c state_func_io, SUSPENDED: AAudioStream_close) and
 * reopens it on resume (state_func_main, SINK_MESSAGE_OPEN_STREAM), which
 * is how the Desktop session gives up its output while another app is in
 * front (docs/SPEC.md "Launch audio hand-off", Droidtop/tracker#160).
 *
 * Not pactl itself: the packaged pactl sits in the extracted modules
 * asset under the app's data directory, which Android does not let an app
 * execute; and the asset has no module-suspend-on-idle or CLI module to
 * do it from inside the server. The request is two packets of PulseAudio
 * 13's native protocol (src/pulsecore/pstream.c framing, tagstruct.h
 * tags, protocol-native.c command_auth and command_suspend); the server
 * runs with auth-anonymous=1 (HostAudioServer.defaultPaConfig), so the
 * cookie is not checked.
 */
internal object PulseSinkSuspend {
    // src/pulsecore/native-common.h, enum order.
    private const val COMMAND_ERROR = 0
    private const val COMMAND_REPLY = 2
    private const val COMMAND_AUTH = 8
    private const val COMMAND_SUSPEND_SINK = 70

    /** PulseAudio 13.0's PA_PROTOCOL_VERSION, without the shm/memfd flag bits. */
    private const val PROTOCOL_VERSION = 32
    private const val COOKIE_LENGTH = 256
    private const val INVALID_INDEX = 0xFFFFFFFFL
    private const val CONTROL_CHANNEL = -1
    private const val TIMEOUT_MS = 1500
    private const val MAX_PACKETS = 16

    /**
     * Suspends ([suspend] true) or resumes all sinks of the server listening
     * at [socketPath]. Blocking socket I/O: call it off the main thread.
     */
    fun setAllSuspended(socketPath: String, suspend: Boolean) {
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = TIMEOUT_MS
            val out = socket.outputStream
            val input = DataInputStream(socket.inputStream)
            send(out, authPacket(tag = 0))
            awaitReply(input, tag = 0)
            send(out, suspendAllPacket(tag = 1, suspend = suspend))
            awaitReply(input, tag = 1)
        }
    }

    internal fun authPacket(tag: Int): ByteArray = Tagstruct()
        .u32(COMMAND_AUTH.toLong()).u32(tag.toLong())
        .u32(PROTOCOL_VERSION.toLong())
        .arbitrary(ByteArray(COOKIE_LENGTH))
        .bytes()

    /** command_suspend: index invalid plus an empty name means every sink (pa_sink_suspend_all). */
    internal fun suspendAllPacket(tag: Int, suspend: Boolean): ByteArray = Tagstruct()
        .u32(COMMAND_SUSPEND_SINK.toLong()).u32(tag.toLong())
        .u32(INVALID_INDEX)
        .string("")
        .boolean(suspend)
        .bytes()

    /** pstream.c: a five-word big-endian descriptor (length, channel, offset hi/lo, flags), then the payload. */
    internal fun frame(payload: ByteArray): ByteArray = ByteBuffer.allocate(20 + payload.size)
        .putInt(payload.size).putInt(CONTROL_CHANNEL).putInt(0).putInt(0).putInt(0)
        .put(payload)
        .array()

    private fun send(out: OutputStream, payload: ByteArray) {
        out.write(frame(payload))
        out.flush()
    }

    private fun awaitReply(input: DataInputStream, tag: Int) {
        repeat(MAX_PACKETS) {
            val length = input.readInt()
            val channel = input.readInt()
            input.skipFully(12)
            if (length < 0 || length > 1 shl 20) throw IOException("bad PulseAudio packet length $length")
            val payload = ByteArray(length).also { input.readFully(it) }
            if (channel != CONTROL_CHANNEL || payload.size < 10) return@repeat
            val buffer = ByteBuffer.wrap(payload)
            val command = readU32(buffer)
            val replyTag = readU32(buffer)
            if (replyTag != tag.toLong()) return@repeat
            when (command.toInt()) {
                COMMAND_REPLY -> return
                COMMAND_ERROR -> throw IOException("PulseAudio refused the request (error ${if (buffer.remaining() >= 5) readU32(buffer) else -1})")
            }
        }
        throw IOException("no PulseAudio reply for request $tag")
    }

    private fun readU32(buffer: ByteBuffer): Long {
        if (buffer.get() != 'L'.code.toByte()) throw IOException("unexpected PulseAudio tag")
        return buffer.int.toLong() and 0xFFFFFFFFL
    }

    private fun InputStream.skipFully(count: Int) {
        var left = count
        while (left > 0) {
            if (read() < 0) throw IOException("PulseAudio closed the connection")
            left--
        }
    }

    /** tagstruct.h: a type byte per value, big-endian numbers, NUL-terminated strings. */
    private class Tagstruct {
        private val out = ByteArrayOutputStream()

        fun u32(value: Long) = apply {
            out.write('L'.code)
            out.write(ByteBuffer.allocate(4).putInt(value.toInt()).array())
        }

        fun string(value: String) = apply {
            out.write('t'.code)
            out.write(value.toByteArray(Charsets.UTF_8))
            out.write(0)
        }

        fun boolean(value: Boolean) = apply { out.write(if (value) '1'.code else '0'.code) }

        fun arbitrary(value: ByteArray) = apply {
            out.write('x'.code)
            out.write(ByteBuffer.allocate(4).putInt(value.size).array())
            out.write(value)
        }

        fun bytes(): ByteArray = out.toByteArray()
    }
}
