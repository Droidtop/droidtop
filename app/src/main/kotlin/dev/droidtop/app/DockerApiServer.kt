package dev.droidtop.app

import android.app.ActivityManager
import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import dev.droidtop.runtime.ContainerInfo
import dev.droidtop.runtime.DockerApi
import dev.droidtop.runtime.EngineFeatureProbe
import dev.droidtop.runtime.EngineHttp
import dev.droidtop.runtime.OciImageStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The Docker Engine API socket (docs/SPEC.md 3d, "The Docker Engine API"; Droidtop/tracker#555 D14). While the
 * desktop runs, droidtop listens on `docker.sock` in its app storage, which every container sees as
 * [DockerApi.DOCKER_HOST]; nothing runs inside a container for it. Each connection is served off the main thread,
 * one request at a time as Docker's clients send them, and answered from the same facts the docker command answers
 * from ([EngineFacts]). What a request may make droidtop read is bounded ([EngineHttp]), a connection idle for a
 * minute is closed, and at most [MAX_CONNECTIONS] are served at once.
 */
internal object DockerApiServer {
    private const val TAG = "droidtop.DockerApi"
    private const val MAX_CONNECTIONS = 16
    private const val IDLE_MS = 60_000

    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var stopping = false
    private var job: Job? = null
    private val open = AtomicInteger(0)

    @Synchronized
    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val file = socketFile(app)
        stopping = false
        job = scope.launch(Dispatchers.IO) {
            val listening = runCatching {
                // A socket file left by an earlier session is stale: one file, removed before binding.
                file.delete()
                val bound = LocalSocket(LocalSocket.SOCKET_STREAM)
                bound.bind(LocalSocketAddress(file.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                LocalServerSocket(bound.fileDescriptor)
            }.onFailure { Log.w(TAG, "Could not listen on ${file.path}", it) }.getOrNull() ?: return@launch
            server = listening
            Log.i(TAG, "Listening on ${file.path}")
            while (isActive && !stopping) {
                val connection = runCatching { listening.accept() }.getOrNull() ?: break
                if (stopping) {
                    runCatching { connection.close() }
                    break
                }
                if (open.incrementAndGet() > MAX_CONNECTIONS) {
                    open.decrementAndGet()
                    runCatching { connection.close() }
                    continue
                }
                launch {
                    try {
                        serve(app, connection)
                    } finally {
                        open.decrementAndGet()
                    }
                }
            }
        }
    }

    /**
     * Stops listening and removes the socket file, off the main thread (the session ends from the service's
     * teardown). The blocked accept is woken by one connection of droidtop's own.
     */
    @Synchronized
    fun stop(context: Context) {
        stopping = true
        val file = socketFile(context.applicationContext)
        val listening = server
        server = null
        job?.cancel()
        job = null
        cleanup.launch {
            runCatching {
                LocalSocket(LocalSocket.SOCKET_STREAM).use { it.connect(LocalSocketAddress(file.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)) }
            }
            runCatching { listening?.close() }
            file.delete()
        }
    }

    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun socketFile(app: Context) = File(app.filesDir, DockerApi.SOCKET_NAME)

    private suspend fun serve(app: Context, connection: LocalSocket) {
        connection.use { socket ->
            socket.soTimeout = IDLE_MS
            val input = socket.inputStream.buffered()
            val output = socket.outputStream
            while (!stopping) {
                val request = try {
                    EngineHttp.read(input) ?: return
                } catch (e: EngineHttp.BadRequest) {
                    runCatching { EngineHttp.write(output, DockerApi.error(e.status, e.message ?: "bad request"), DockerApi.headers + ("Connection" to "close")) }
                    return
                } catch (e: java.io.IOException) {
                    return
                }
                val response = runCatching { answer(app, request) }.getOrElse {
                    Log.w(TAG, "${request.method} ${request.path} failed", it)
                    DockerApi.error(500, it.message ?: it.javaClass.simpleName)
                }
                val head = request.method == "HEAD"
                runCatching {
                    EngineHttp.write(output, if (head) response.copy(body = ByteArray(0)) else response, DockerApi.headers)
                }.onFailure { return }
                if (request.closes) return
            }
        }
    }

    /** What [request] asks, answered from droidtop's own state. */
    private suspend fun answer(app: Context, request: EngineHttp.Request): EngineHttp.Response {
        val call = DockerApi.route(request.method, request.path, request.query)
        val runtime = (DesktopSessionService.state.value as? DesktopSessionState.Connected)?.runtime
        return when (call) {
            DockerApi.Call.Ping -> EngineHttp.Response.text(200, "OK")
            DockerApi.Call.Version -> EngineHttp.Response.json(200, DockerApi.version(arch(), kernel()))
            DockerApi.Call.Info -> {
                val containers = runtime?.let { runCatching { it.listContainers() }.getOrNull() }.orEmpty()
                val memory = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
                EngineHttp.Response.json(
                    200,
                    DockerApi.info(
                        DockerApi.InfoFacts(
                            containers = containers.size,
                            running = containers.count { ContainerPrograms.isRunning(it.container.id) },
                            images = runCatching { OciImageStore.of(app).entries().size }.getOrDefault(0),
                            arch = arch(),
                            kernel = kernel(),
                            cpus = Runtime.getRuntime().availableProcessors(),
                            memoryBytes = memory.totalMem,
                            features = runCatching { EngineFeatureProbe.current(app) }.getOrNull(),
                        ),
                    ),
                )
            }
            is DockerApi.Call.Containers -> {
                runtime ?: return DockerApi.error(500, "the desktop is not running")
                val pods = EngineFacts.pods(app)
                val rows = runtime.listContainers().map { info ->
                    val id = info.container.id
                    DockerApi.Summary(
                        EngineFacts.of(app, runtime, info, pods),
                        EngineFacts.statusText(ContainerPrograms.state(id), ContainerPrograms.isRunning(id), info.running),
                    )
                }
                EngineHttp.Response.json(200, DockerApi.containers(rows, call.all))
            }
            is DockerApi.Call.Container -> {
                runtime ?: return DockerApi.error(500, "the desktop is not running")
                val info = find(runtime.listContainers(), call.id) ?: return DockerApi.error(404, "No such container: ${call.id}")
                EngineHttp.Response.json(200, DockerApi.container(EngineFacts.of(app, runtime, info, EngineFacts.pods(app))))
            }
            DockerApi.Call.Images -> EngineHttp.Response.json(200, DockerApi.images(OciImageStore.of(app).entries()))
            is DockerApi.Call.NotBuilt -> DockerApi.notBuilt(call.what)
            DockerApi.Call.NotFound -> DockerApi.error(404, "page not found")
        }
    }

    /** A container by name, id, or a unique start of its id, as Docker's daemon takes them. */
    private fun find(containers: List<ContainerInfo>, ref: String): ContainerInfo? =
        containers.firstOrNull { it.displayName == ref || it.displayName == ref.removePrefix("/") }
            ?: containers.firstOrNull { it.container.id == ref }
            ?: containers.filter { ref.length >= 3 && it.container.id.startsWith(ref) }.singleOrNull()

    private fun arch(): String = DockerApi.arch(System.getProperty("os.arch").orEmpty())

    private fun kernel(): String = System.getProperty("os.version").orEmpty()
}
