package dev.droidtop.app

import android.app.ActivityManager
import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import dev.droidtop.runtime.ContainerInfo
import dev.droidtop.runtime.ContainerNetworks
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private const val WAIT_LOOK_MS = 250L

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
                DockerApi.unknownFilters(call.filters).takeIf { it.isNotEmpty() }?.let { return DockerApi.notBuilt("filters ${it.joinToString(", ")}") }
                val pods = EngineFacts.pods(app)
                val rows = runtime.listContainers().map { info ->
                    val id = info.container.id
                    DockerApi.Summary(
                        EngineFacts.of(app, runtime, info, pods),
                        EngineFacts.statusText(ContainerPrograms.state(id), ContainerPrograms.isRunning(id), info.running),
                    )
                }
                EngineHttp.Response.json(200, DockerApi.containers(rows, call.all, call.filters))
            }
            is DockerApi.Call.Container -> {
                runtime ?: return DockerApi.error(500, "the desktop is not running")
                val info = find(runtime.listContainers(), call.id) ?: return DockerApi.error(404, "No such container: ${call.id}")
                EngineHttp.Response.json(200, DockerApi.container(EngineFacts.of(app, runtime, info, EngineFacts.pods(app))))
            }
            DockerApi.Call.Images -> EngineHttp.Response.json(200, DockerApi.images(OciImageStore.of(app).entries()))
            is DockerApi.Call.Create -> {
                runtime ?: return DockerApi.error(500, "the desktop is not running")
                val features = runCatching { EngineFeatureProbe.current(app) }.getOrNull()
                val networks = withContext(Dispatchers.IO) { ContainerNetworks.read(app.filesDir) }
                val (create, refused) = DockerApi.createRequest(String(request.body, Charsets.UTF_8), call.name, features, networks)
                refused?.let { return it }
                val made = EngineActions.make(app, runtime, runtime.listContainers(), create!!).getOrElse {
                    return DockerApi.error(409, it.message ?: "could not create the container")
                }
                EngineHttp.Response.json(201, DockerApi.created(made.container.id, made.notes))
            }
            is DockerApi.Call.Start -> withContainer(runtime, call.id) { rt, info ->
                when {
                    ContainerPrograms.isRunning(info.container.id) -> EngineHttp.Response(304, "text/plain", ByteArray(0))
                    EngineActions.start(app, rt, info) -> EngineHttp.Response(204, "text/plain", ByteArray(0))
                    else -> DockerApi.error(500, "${info.displayName} has no program of its own to start (its image names none)")
                }
            }
            is DockerApi.Call.Stop -> withContainer(runtime, call.id) { _, info ->
                if (!ContainerPrograms.isRunning(info.container.id)) {
                    EngineHttp.Response(304, "text/plain", ByteArray(0))
                } else {
                    EngineActions.stop(app, info)
                    EngineHttp.Response(204, "text/plain", ByteArray(0))
                }
            }
            is DockerApi.Call.Restart -> withContainer(runtime, call.id) { rt, info ->
                if (EngineActions.restart(app, rt, info)) {
                    EngineHttp.Response(204, "text/plain", ByteArray(0))
                } else {
                    DockerApi.error(500, "${info.displayName} has no program of its own to restart (its image names none)")
                }
            }
            is DockerApi.Call.Kill -> withContainer(runtime, call.id) { _, info ->
                if (!ContainerPrograms.isRunning(info.container.id)) {
                    DockerApi.error(409, "Container ${info.displayName} is not running")
                } else {
                    EngineActions.stop(app, info)
                    EngineHttp.Response(204, "text/plain", ByteArray(0))
                }
            }
            is DockerApi.Call.Remove -> withContainer(runtime, call.id) { rt, info ->
                when (val removal = EngineActions.remove(app, rt, info, call.force)) {
                    EngineActions.Removal.Removed -> EngineHttp.Response(204, "text/plain", ByteArray(0))
                    EngineActions.Removal.Primary -> DockerApi.error(409, "${info.displayName} is the desktop's own container; droidtop does not remove it")
                    EngineActions.Removal.Running -> DockerApi.error(
                        409,
                        "You cannot remove a running container ${info.displayName}. Stop the container before attempting removal or force remove",
                    )
                    is EngineActions.Removal.Failed -> DockerApi.error(500, removal.message)
                }
            }
            is DockerApi.Call.Pull -> EngineActions.pull(app, call.reference).fold(
                onSuccess = { EngineHttp.Response.json(200, DockerApi.pulled(call.reference, it)) },
                onFailure = { DockerApi.error(500, it.message ?: it.javaClass.simpleName) },
            )
            is DockerApi.Call.Logs -> withContainer(runtime, call.id) { _, info ->
                EngineHttp.Response(200, "application/vnd.docker.multiplexed-stream", DockerApi.logFrames(EngineActions.logs(app, info, call.tail)))
            }
            is DockerApi.Call.Wait -> withContainer(runtime, call.id) { _, info ->
                val id = info.container.id
                if (!ContainerPrograms.isRunning(id) && call.condition == "next-exit") {
                    DockerApi.notBuilt("wait?condition=next-exit on a stopped container")
                } else {
                    // Until the program is no longer running; its exit code, 125 when it could not start.
                    // Both the job and the state must say it is over: the state lags a start that has just been asked
                    // for, and the job outlives the state's last change by its clean-up. A quarter-second look covers both.
                    while (ContainerPrograms.isRunning(id) || ContainerPrograms.state(id).let {
                            it is ContainerPrograms.State.Running || it is ContainerPrograms.State.Waiting
                        }
                    ) {
                        delay(WAIT_LOOK_MS)
                    }
                    val code = when (val state = ContainerPrograms.state(id)) {
                        is ContainerPrograms.State.Ended -> state.exitCode
                        is ContainerPrograms.State.Failed -> 125
                        else -> 0
                    }
                    EngineHttp.Response.json(200, DockerApi.waited(code))
                }
            }
            is DockerApi.Call.ImageGet -> {
                val store = OciImageStore.of(app)
                val entry = DockerApi.findImage(store.entries(), call.name) ?: return DockerApi.error(404, "No such image: ${call.name}")
                EngineHttp.Response.json(200, DockerApi.image(entry, store.runConfig(entry.digest), arch()))
            }
            is DockerApi.Call.Networks -> {
                DockerApi.unknownNetworkFilters(call.filters).takeIf { it.isNotEmpty() }?.let { return DockerApi.notBuilt("network filters ${it.joinToString(", ")}") }
                val all = ContainerNetworks.predefined() + withContext(Dispatchers.IO) { ContainerNetworks.read(app.filesDir) }
                EngineHttp.Response.json(200, DockerApi.networks(all, containerNames(runtime), call.filters))
            }
            is DockerApi.Call.NetworkGet -> {
                val all = ContainerNetworks.predefined() + withContext(Dispatchers.IO) { ContainerNetworks.read(app.filesDir) }
                val network = ContainerNetworks.find(all, call.id) ?: return DockerApi.error(404, "network ${call.id} not found")
                EngineHttp.Response.json(200, DockerApi.network(network, containerNames(runtime)).toString() + "\n")
            }
            DockerApi.Call.NetworkCreate -> networksLock.withLock {
                val existing = withContext(Dispatchers.IO) { ContainerNetworks.read(app.filesDir) }
                val features = runCatching { EngineFeatureProbe.current(app) }.getOrNull()
                val (network, refused) = DockerApi.networkRequest(String(request.body, Charsets.UTF_8), existing, features)
                refused?.let { return@withLock it }
                withContext(Dispatchers.IO) { ContainerNetworks.save(app.filesDir, ContainerNetworks.with(existing, network!!)) }
                EngineHttp.Response.json(201, buildString { append("{\"Id\":\"").append(network!!.id).append("\",\"Warning\":\"\"}\n") })
            }
            is DockerApi.Call.NetworkRemove -> networksLock.withLock {
                if (ContainerNetworks.PREDEFINED.any { it == call.id || ContainerNetworks.predefinedId(it) == call.id }) {
                    return@withLock DockerApi.error(403, "${call.id} is a pre-defined network and cannot be removed")
                }
                val existing = withContext(Dispatchers.IO) { ContainerNetworks.read(app.filesDir) }
                val network = ContainerNetworks.find(existing, call.id) ?: return@withLock DockerApi.error(404, "network ${call.id} not found")
                val live = runtime?.let { runCatching { it.listContainers() }.getOrNull() }.orEmpty().map { it.container.id }.toSet()
                if (network.members.any { it in live }) {
                    return@withLock DockerApi.error(403, "error while removing network: network ${network.name} has active endpoints")
                }
                withContext(Dispatchers.IO) { ContainerNetworks.save(app.filesDir, ContainerNetworks.without(existing, network.id)) }
                EngineHttp.Response(204, "text/plain", ByteArray(0))
            }
            is DockerApi.Call.NetworkConnect -> networksLock.withLock {
                runtime ?: return@withLock DockerApi.error(500, "the desktop is not running")
                val body = runCatching { Json.parseToJsonElement(String(request.body, Charsets.UTF_8)) as JsonObject }.getOrNull()
                val ref = (body?.get("Container") as? JsonPrimitive)?.content ?: return@withLock DockerApi.error(400, "name a Container")
                val info = find(runtime.listContainers(), ref) ?: return@withLock DockerApi.error(404, "No such container: $ref")
                val existing = withContext(Dispatchers.IO) { ContainerNetworks.read(app.filesDir) }
                if (call.id in ContainerNetworks.PREDEFINED) return@withLock EngineHttp.Response(200, "text/plain", ByteArray(0))
                val network = ContainerNetworks.find(existing, call.id) ?: return@withLock DockerApi.error(404, "network ${call.id} not found")
                val next = if (call.connect) ContainerNetworks.withMember(existing, network.id, info.container.id)
                else ContainerNetworks.withoutMember(existing, info.container.id, network.id)
                withContext(Dispatchers.IO) { ContainerNetworks.save(app.filesDir, next) }
                EngineHttp.Response(200, "text/plain", ByteArray(0))
            }
            is DockerApi.Call.Refused -> DockerApi.error(call.status, call.message)
            is DockerApi.Call.NotBuilt -> DockerApi.notBuilt(call.what)
            DockerApi.Call.NotFound -> DockerApi.error(404, "page not found")
        }
    }

    /** One network change at a time: each reads, changes and writes networks.json whole. */
    private val networksLock = Mutex()

    /** Every container's name by its id, for a network's members; empty with the desktop down. */
    private suspend fun containerNames(runtime: dev.droidtop.runtime.ContainerRuntime?): Map<String, String> =
        runtime?.let { runCatching { it.listContainers() }.getOrNull() }.orEmpty().associate { it.container.id to it.displayName }

    /** [block] with the running desktop's runtime and the container [ref] names; 500 or 404 otherwise. */
    private suspend fun withContainer(
        runtime: dev.droidtop.runtime.ContainerRuntime?,
        ref: String,
        block: suspend (dev.droidtop.runtime.ContainerRuntime, ContainerInfo) -> EngineHttp.Response,
    ): EngineHttp.Response {
        runtime ?: return DockerApi.error(500, "the desktop is not running")
        val info = find(runtime.listContainers(), ref) ?: return DockerApi.error(404, "No such container: $ref")
        return block(runtime, info)
    }

    /** A container by name, id, or a unique start of its id, as Docker's daemon takes them. */
    private fun find(containers: List<ContainerInfo>, ref: String): ContainerInfo? =
        containers.firstOrNull { it.displayName == ref || it.displayName == ref.removePrefix("/") }
            ?: containers.firstOrNull { it.container.id == ref }
            ?: containers.filter { ref.length >= 3 && it.container.id.startsWith(ref) }.singleOrNull()

    private fun arch(): String = DockerApi.arch(System.getProperty("os.arch").orEmpty())

    private fun kernel(): String = System.getProperty("os.version").orEmpty()
}
