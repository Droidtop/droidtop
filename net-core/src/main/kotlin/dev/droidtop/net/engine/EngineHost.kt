package dev.droidtop.net.engine

import android.content.Context
import android.os.DeadObjectException
import android.os.RemoteException
import dev.droidtop.net.DownloadCategory
import dev.droidtop.net.DownloadPools
import io.github.bi0shacker001.aria2engine.DownloadEngine
import io.github.bi0shacker001.aria2engine.DownloadKind
import io.github.bi0shacker001.aria2engine.DownloadRequest
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * droidtop's one download engine, as the main process holds it (docs/SPEC.md 12a "One download engine"): libaria2 in a
 * process of its own, confined by the sandbox library to droidtop's broker, which opens every file and every socket.
 * [engine] starts it on first use and again after its process ended; nothing starts it at app start, so a person who
 * downloads nothing pays nothing.
 *
 * aria2's JSON-RPC runs for droidtop alone: [rpcEndpoint] is a port on loopback chosen for this start and a secret made
 * for it, held here and given to nobody else. A plugin reaches downloads only through the plugin API's broker, which
 * starts them as jobs, never through this port.
 *
 * The engine's root is the folder every download lands in; the first call fixes it. Android isolation (an engine under
 * a UID of its own, API 30 and later) is [androidIsolation], off unless the person turned it on.
 */
object EngineHost {
    /**
     * How many downloads aria2 runs at once: every pool full at its highest limit. The pools ([DownloadPools]) decide
     * what runs; aria2's own default of 3 would leave a download the pools started waiting in aria2's queue, "Connecting…".
     */
    val ENGINE_SLOTS = DownloadCategory.entries.size * DownloadPools.MAX_LIMIT

    private val lock = Mutex()
    private var running: DownloadEngine? = null
    private var root: File? = null
    private var isolated = false

    /** The loopback port and secret of aria2's JSON-RPC for droidtop's own use, once the engine has started. */
    data class Rpc(val port: Int, val secret: String)

    @Volatile
    var rpcEndpoint: Rpc? = null
        private set

    /** The engine to hand a transfer to, started if it is not running. Blocking inside; call from any dispatcher. */
    suspend fun engine(context: Context, root: File, androidIsolation: Boolean = false): TransferEngine {
        lock.withLock {
            settings = Settings(context.applicationContext, root, androidIsolation)
            startIfNeeded()
        }
        return Adapter
    }

    private class Settings(val context: Context, val root: File, val androidIsolation: Boolean)

    private var settings: Settings? = null

    /** Starts the engine unless the one running is alive and was started the way [settings] say. Holds [lock]. */
    private suspend fun startIfNeeded() = withContext(Dispatchers.IO) {
        val wanted = checkNotNull(settings)
        val current = running
        if (current != null && wanted.root == root && wanted.androidIsolation == isolated && alive(current)) return@withContext
        current?.let { stopQuietly(it) }
        running = null
        rpcEndpoint = null
        val port = freeLoopbackPort()
        val started = DownloadEngine(wanted.context, wanted.root, rpc = true, rpcPort = port, androidIsolation = wanted.androidIsolation)
            .start(mapOf("max-concurrent-downloads" to ENGINE_SLOTS.toString()))
        running = started
        root = wanted.root
        isolated = wanted.androidIsolation
        rpcEndpoint = Rpc(port, started.rpcSecret)
    }

    private fun alive(engine: DownloadEngine): Boolean = try {
        engine.active()
        true
    } catch (e: Exception) {
        false
    }

    private fun stopQuietly(engine: DownloadEngine) {
        try {
            engine.stop()
        } catch (_: Exception) {
        }
    }

    private fun freeLoopbackPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    /** The running engine as a [TransferEngine]; a call after the engine's process ended becomes an [EngineGoneException]. */
    private object Adapter : TransferEngine {
        private fun current(): DownloadEngine = running ?: throw EngineGoneException("the download engine is not running")

        private inline fun <T> guarded(block: () -> T): T = try {
            block()
        } catch (e: DeadObjectException) {
            throw EngineGoneException("the download engine ended", e)
        } catch (e: RemoteException) {
            throw EngineGoneException("the download engine ended", e)
        } catch (e: IllegalStateException) {
            // "the engine is not running": its service connection dropped.
            throw EngineGoneException(e.message ?: "the download engine is not running", e)
        }

        /** A new download is the moment to bring a dead engine back; a call on a download of the old process is not. */
        private fun addOnce(request: (DownloadEngine) -> DownloadRequest): String = try {
            guarded { current().let { it.add(request(it)) } }
        } catch (e: EngineGoneException) {
            runBlocking { lock.withLock { startIfNeeded() } }
            guarded { current().let { it.add(request(it)) } }
        }

        override fun add(url: String, options: Map<String, String>): String =
            addOnce { DownloadRequest(DownloadKind.URI, uris = listOf(url), options = options) }

        override fun addSwarm(source: SwarmSource, folder: File, options: Map<String, String>): String = addOnce { engine ->
            val placed = options + ("dir" to engine.enginePath(folder))
            when (source) {
                is SwarmSource.Magnet -> DownloadRequest(DownloadKind.URI, uris = listOf(source.uri), options = placed)
                is SwarmSource.Torrent -> DownloadRequest(DownloadKind.TORRENT, metadata = source.bytes, options = placed)
                is SwarmSource.Metalink -> DownloadRequest(DownloadKind.METALINK, metadata = source.bytes, options = placed)
            }
        }

        override fun allowLocal(address: java.net.InetAddress, port: Int) {
            guarded { current().allowLocal(address, port) }
        }

        override fun revokeLocal(address: java.net.InetAddress, port: Int) {
            guarded { current().revokeLocal(address, port) }
        }

        override fun status(gid: String): Transfer? = guarded {
            current().status(gid)?.let { Transfer(it.state, it.totalLength, it.completedLength, it.downloadSpeed.toLong(), it.errorCode, it.followedBy) }
        }

        override fun remove(gid: String) {
            guarded { current().remove(gid, force = true) }
        }
    }
}
