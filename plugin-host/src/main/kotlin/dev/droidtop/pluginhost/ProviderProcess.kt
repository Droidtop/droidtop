package dev.droidtop.pluginhost

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * A long-running privileged command through a `priv.shell` provider plugin (docs/plugin-api.md 2.7, "Stream
 * sessions"), as a [Process]: droidtop's `PrivilegedShell.spawn` shape, for a command that outlives one call or takes
 * input (the rooted desktop stack, Droidtop/tracker#394), with no `su` anywhere. The provider runs it (Shizuku's
 * server, as the shell user or as root); this object turns the session ops into streams:
 *
 * - stdout and stderr: a pump thread long-polls `stream_read` and feeds two pipes until the provider says it exited;
 * - stdin: buffered, sent with `stream_write` on [OutputStream.flush], when the buffer fills, and with `close` on close;
 * - [destroy]: `stream_kill`.
 *
 * Every call blocks on the provider's process: never construct or read one on the main thread.
 */
class ProviderProcess private constructor(
    private val caller: HostApiCaller,
    private val session: String,
    private val minLevel: String?,
) : Process() {
    private val stdoutIn = PipedInputStream(PIPE_BYTES)
    private val stdoutSink = PipedOutputStream(stdoutIn)
    private val stderrIn = PipedInputStream(PIPE_BYTES)
    private val stderrSink = PipedOutputStream(stderrIn)
    private val exited = CountDownLatch(1)

    @Volatile private var exit: Int? = null

    private val stdin = object : OutputStream() {
        private val buffer = java.io.ByteArrayOutputStream()
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("stdin is closed")
            buffer.write(b, off, len)
            if (buffer.size() >= WRITE_CHUNK) flush()
        }

        @Synchronized
        override fun flush() = send(close = false)

        @Synchronized
        override fun close() {
            if (closed) return
            send(close = true)
            closed = true
        }

        private fun send(close: Boolean) {
            if (buffer.size() == 0 && !close) return
            val bytes = buffer.toByteArray()
            buffer.reset()
            var start = 0
            do {
                val end = minOf(bytes.size, start + WRITE_CHUNK)
                val last = end >= bytes.size
                val args = JSONObject().put("session", session).put("data", Base64.getEncoder().encodeToString(bytes.copyOfRange(start, end)))
                if (close && last) args.put("close", true)
                val reply = caller.call(API, 1, "stream_write", args, minLevel = minLevel)
                if (!reply.ok) throw IOException(reply.message ?: "the provider refused the input")
                start = end
            } while (!last)
        }
    }

    private val pump = Thread({ pumpOutput() }, "provider-process").apply { isDaemon = true }

    private fun pumpOutput() {
        var code = -1
        try {
            while (true) {
                val reply = caller.call(API, 1, "stream_read", JSONObject().put("session", session).put("waitMs", READ_WAIT_MS).put("maxBytes", READ_BYTES), minLevel = minLevel)
                if (!reply.ok) break
                val data = reply.data
                data.optString("stdout").takeIf { it.isNotEmpty() }?.let { stdoutSink.write(Base64.getDecoder().decode(it)) }
                data.optString("stderr").takeIf { it.isNotEmpty() }?.let { stderrSink.write(Base64.getDecoder().decode(it)) }
                if (data.optBoolean("exited")) {
                    code = data.optInt("exit", -1)
                    break
                }
            }
        } catch (e: IOException) {
            // Nobody reads the output any more; the session ends with the process below.
            runCatching { caller.call(API, 1, "stream_kill", JSONObject().put("session", session), minLevel = minLevel) }
        } finally {
            exit = code
            runCatching { stdoutSink.close() }
            runCatching { stderrSink.close() }
            exited.countDown()
        }
    }

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = stdoutIn

    override fun getErrorStream(): InputStream = stderrIn

    override fun waitFor(): Int {
        exited.await()
        return exit ?: -1
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)

    override fun exitValue(): Int = exit ?: throw IllegalThreadStateException("the command is still running")

    override fun isAlive(): Boolean = exit == null

    override fun destroy() {
        if (exit != null) return
        runCatching { caller.call(API, 1, "stream_kill", JSONObject().put("session", session), minLevel = minLevel) }
    }

    companion object {
        private const val API = "priv.shell"
        private const val PIPE_BYTES = 256 * 1024
        private const val WRITE_CHUNK = 64 * 1024
        private const val READ_BYTES = 64 * 1024
        private const val READ_WAIT_MS = 2_000

        /**
         * Starts [argv] through the `priv.shell` provider at [minLevel] or above ("root" for the rooted desktop stack),
         * directly (never through a shell unless [argv] names one). Null when no running provider serves that level or it
         * refused to start the command.
         */
        fun start(caller: HostApiCaller, argv: List<String>, minLevel: String? = null): Process? {
            if (argv.isEmpty() || !caller.hasProvider(API, 1, minLevel)) return null
            val session = UUID.randomUUID().toString()
            val reply = caller.call(API, 1, "exec_stream", JSONObject().put("argv", JSONArray(argv)).put("session", session), minLevel = minLevel)
            if (!reply.ok) return null
            return ProviderProcess(caller, session, minLevel).also { it.pump.start() }
        }
    }
}
