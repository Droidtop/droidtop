package dev.droidtop.app

import android.app.ActivityManager
import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import dev.droidtop.runtime.ContainerInfo
import dev.droidtop.runtime.ContainerNetworks
import dev.droidtop.runtime.ContainerPods
import dev.droidtop.runtime.Pod
import dev.droidtop.runtime.DockerApi
import dev.droidtop.runtime.EngineFeatureProbe
import dev.droidtop.runtime.EngineHttp
import dev.droidtop.runtime.LibpodApi
import dev.droidtop.runtime.OciImageStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The engine's API sockets (docs/SPEC.md 3d, "The Docker Engine API" and "The libpod API"; Droidtop/tracker#555 D14,
 * D15). While the desktop runs, droidtop listens on `docker.sock` and `podman.sock` in its app storage, which every
 * container sees as [DockerApi.DOCKER_HOST] and [LibpodApi.CONTAINER_HOST]; nothing runs inside a container for them.
 * Both sockets answer both APIs, as Podman's own service does: a `/libpod/` path is [LibpodApi]'s, any other
 * [DockerApi]'s, read into the same calls. Each connection is served off the main thread, one request at a time as
 * the clients send them, and answered from the same facts the command fronts answer from ([EngineFacts]). What a
 * request may make droidtop read is bounded ([EngineHttp]), a connection idle for a minute is closed, and at most
 * [MAX_CONNECTIONS] are served at once across both sockets.
 */
internal object EngineApiServer {
    private const val TAG = "droidtop.EngineApi"
    private const val MAX_CONNECTIONS = 16
    private const val IDLE_MS = 60_000
    private const val WAIT_LOOK_MS = 250L

    private val SOCKETS = listOf(DockerApi.SOCKET_NAME, LibpodApi.SOCKET_NAME)

    @Volatile private var servers: List<LocalServerSocket> = emptyList()
    @Volatile private var stopping = false
    private var job: Job? = null
    private val open = AtomicInteger(0)

    @Synchronized
    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        stopping = false
        servers = emptyList()
        job = scope.launch(Dispatchers.IO) {
            SOCKETS.forEach { name -> launch { listen(app, File(app.filesDir, name)) } }
        }
    }

    private suspend fun listen(app: Context, file: File): Unit = coroutineScope {
        val listening = runCatching {
            // A socket file left by an earlier session is stale: one file, removed before binding.
            file.delete()
            val bound = LocalSocket(LocalSocket.SOCKET_STREAM)
            bound.bind(LocalSocketAddress(file.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            LocalServerSocket(bound.fileDescriptor)
        }.onFailure { Log.w(TAG, "Could not listen on ${file.path}", it) }.getOrNull() ?: return@coroutineScope
        synchronized(this@EngineApiServer) { servers = servers + listening }
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
        // Closed here too: a stop that came before this socket was listed only woke it.
        runCatching { listening.close() }
    }

    /**
     * Stops listening and removes the socket files, off the main thread (the session ends from the service's
     * teardown). Each blocked accept is woken by one connection of droidtop's own.
     */
    @Synchronized
    fun stop(context: Context) {
        stopping = true
        val app = context.applicationContext
        val listening = servers
        servers = emptyList()
        job?.cancel()
        job = null
        cleanup.launch {
            SOCKETS.map { File(app.filesDir, it) }.forEach { file ->
                runCatching {
                    LocalSocket(LocalSocket.SOCKET_STREAM).use { it.connect(LocalSocketAddress(file.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)) }
                }
            }
            listening.forEach { runCatching { it.close() } }
            SOCKETS.forEach { File(app.filesDir, it).delete() }
        }
    }

    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                val headers = DockerApi.headers + (LibpodApi.version(request.path)?.let { LibpodApi.headers(it) } ?: emptyMap())
                runCatching {
                    EngineHttp.write(output, if (head) response.copy(body = ByteArray(0)) else response, headers)
                }.onFailure { return }
                // A streamed body (attach) is written as it comes, until it ends or the client goes.
                response.stream?.let { stream ->
                    runCatching { stream(output) }
                    return
                }
                // A hijacked connection (101) carries one stream and then ends.
                if (request.closes || response.status == 101) return
            }
        }
    }

    /** What [request] asks, answered from droidtop's own state. */
    private suspend fun answer(app: Context, request: EngineHttp.Request): EngineHttp.Response {
        // A libpod path is read by LibpodApi into the same calls, and answered in its version's shapes.
        val libpod = LibpodApi.version(request.path)
        val call = if (libpod != null) LibpodApi.route(request.method, request.path, request.query) else DockerApi.route(request.method, request.path, request.query)
        val runtime = (DesktopSessionService.state.value as? DesktopSessionState.Connected)?.runtime
        return when (call) {
            DockerApi.Call.Ping -> EngineHttp.Response.text(200, "OK")
            DockerApi.Call.Version -> EngineHttp.Response.json(200, libpod?.let { LibpodApi.version(it, arch(), kernel()) } ?: DockerApi.version(arch(), kernel()))
            DockerApi.Call.Info -> {
                val containers = runtime?.let { runCatching { it.listContainers() }.getOrNull() }.orEmpty()
                val memory = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
                val facts = DockerApi.InfoFacts(
                    containers = containers.size,
                    running = containers.count { ContainerPrograms.isRunning(it.container.id) },
                    images = runCatching { OciImageStore.of(app).entries().size }.getOrDefault(0),
                    arch = arch(),
                    kernel = kernel(),
                    cpus = Runtime.getRuntime().availableProcessors(),
                    memoryBytes = memory.totalMem,
                    features = runCatching { EngineFeatureProbe.current(app) }.getOrNull(),
                )
                EngineHttp.Response.json(200, libpod?.let { LibpodApi.info(facts, it) } ?: DockerApi.info(facts))
            }
            is DockerApi.Call.Containers -> {
                runtime ?: return DockerApi.error(500, "the desktop is not running")
                DockerApi.unknownFilters(call.filters).takeIf { it.isNotEmpty() }?.let { return notBuilt(libpod, "filters ${it.joinToString(", ")}") }
                val pods = EngineFacts.pods(app)
                val rows = runtime.listContainers().map { info ->
                    val id = info.container.id
                    DockerApi.Summary(
                        EngineFacts.of(app, runtime, info, pods),
                        EngineFacts.statusText(ContainerPrograms.state(id), ContainerPrograms.isRunning(id), info.running),
                    )
                }
                EngineHttp.Response.json(200, if (libpod != null) LibpodApi.containers(rows, call.all, call.filters) else DockerApi.containers(rows, call.all, call.filters))
            }
            is DockerApi.Call.Container -> {
                runtime ?: return DockerApi.error(500, "the desktop is not running")
                val info = find(runtime.listContainers(), call.id) ?: return missing(libpod, DockerApi.Kind.CONTAINER, call.id)
                val facts = EngineFacts.of(app, runtime, info, EngineFacts.pods(app))
                EngineHttp.Response.json(200, libpod?.let { LibpodApi.inspect(facts, it) } ?: DockerApi.container(facts))
            }
            DockerApi.Call.Images -> OciImageStore.of(app).entries().let {
                EngineHttp.Response.json(200, if (libpod != null) LibpodApi.images(it) else DockerApi.images(it))
            }
            is DockerApi.Call.Create -> {
                runtime ?: return DockerApi.error(500, "the desktop is not running")
                val features = runCatching { EngineFeatureProbe.current(app) }.getOrNull()
                val networks = withContext(Dispatchers.IO) { ContainerNetworks.read(app.filesDir) }
                val body = String(request.body, Charsets.UTF_8)
                val (create, refused) = if (libpod != null) LibpodApi.createRequest(body, features, networks)
                else DockerApi.createRequest(body, call.name, features, networks)
                refused?.let { return it }
                val made = EngineActions.make(app, runtime, runtime.listContainers(), create!!).getOrElse {
                    return DockerApi.error(409, it.message ?: "could not create the container")
                }
                EngineHttp.Response.json(201, DockerApi.created(made.container.id, made.notes))
            }
            is DockerApi.Call.Start -> withContainer(libpod, runtime, call.id) { rt, info ->
                when {
                    ContainerPrograms.isRunning(info.container.id) -> EngineHttp.Response(304, "text/plain", ByteArray(0))
                    EngineActions.start(app, rt, info) -> EngineHttp.Response(204, "text/plain", ByteArray(0))
                    else -> DockerApi.error(500, "${info.displayName} has no program of its own to start (its image names none)")
                }
            }
            is DockerApi.Call.Stop -> withContainer(libpod, runtime, call.id) { _, info ->
                if (!ContainerPrograms.isRunning(info.container.id)) {
                    EngineHttp.Response(304, "text/plain", ByteArray(0))
                } else {
                    EngineActions.stop(app, info)
                    EngineHttp.Response(204, "text/plain", ByteArray(0))
                }
            }
            is DockerApi.Call.Restart -> withContainer(libpod, runtime, call.id) { rt, info ->
                if (EngineActions.restart(app, rt, info)) {
                    EngineHttp.Response(204, "text/plain", ByteArray(0))
                } else {
                    DockerApi.error(500, "${info.displayName} has no program of its own to restart (its image names none)")
                }
            }
            is DockerApi.Call.Kill -> withContainer(libpod, runtime, call.id) { _, info ->
                if (!ContainerPrograms.isRunning(info.container.id)) {
                    DockerApi.error(409, "Container ${info.displayName} is not running")
                } else {
                    EngineActions.stop(app, info)
                    EngineHttp.Response(204, "text/plain", ByteArray(0))
                }
            }
            is DockerApi.Call.Remove -> {
                // libpod's ignore: a container that is not there is no error, and no report.
                if (call.ignoreMissing && runtime != null && find(runtime.listContainers(), call.id) == null) {
                    return EngineHttp.Response.json(200, LibpodApi.removed(emptyList()))
                }
                withContainer(libpod, runtime, call.id) { rt, info -> remove(app, libpod, rt, info, call.force) }
            }
            is DockerApi.Call.Pull -> pull(app, libpod, call)
            is DockerApi.Call.Logs -> withContainer(libpod, runtime, call.id) { _, info ->
                EngineHttp.Response(200, "application/vnd.docker.multiplexed-stream", DockerApi.logFrames(EngineActions.logs(app, info, call.tail)))
            }
            is DockerApi.Call.Wait -> withContainer(libpod, runtime, call.id) { _, info ->
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
                    // removed (docker run --rm): the exit code once --rm has removed it, or after half a minute.
                    if (call.condition == "removed") {
                        val giveUp = System.currentTimeMillis() + REMOVED_WAIT_MS
                        while (System.currentTimeMillis() < giveUp &&
                            runCatching { runtime?.listContainers() }.getOrNull().orEmpty().any { it.container.id == id }
                        ) {
                            delay(WAIT_LOOK_MS)
                        }
                    }
                    EngineHttp.Response.json(200, if (libpod != null) LibpodApi.waited(code) else DockerApi.waited(code))
                }
            }
            is DockerApi.Call.ImageGet -> {
                val store = OciImageStore.of(app)
                val entry = DockerApi.findImage(store.entries(), call.name) ?: return missing(libpod, DockerApi.Kind.IMAGE, call.name)
                val config = store.runConfig(entry.digest)
                EngineHttp.Response.json(200, if (libpod != null) LibpodApi.image(entry, config, arch()) else DockerApi.image(entry, config, arch()))
            }
            is DockerApi.Call.Networks -> {
                DockerApi.unknownNetworkFilters(call.filters).takeIf { it.isNotEmpty() }?.let { return notBuilt(libpod, "network filters ${it.joinToString(", ")}") }
                val all = ContainerNetworks.predefined() + withContext(Dispatchers.IO) { ContainerNetworks.read(app.filesDir) }
                val names = containerNames(runtime)
                EngineHttp.Response.json(200, if (libpod != null) LibpodApi.networks(all, names, call.filters) else DockerApi.networks(all, names, call.filters))
            }
            is DockerApi.Call.NetworkGet -> {
                val all = ContainerNetworks.predefined() + withContext(Dispatchers.IO) { ContainerNetworks.read(app.filesDir) }
                val network = ContainerNetworks.find(all, call.id) ?: return missing(libpod, DockerApi.Kind.NETWORK, call.id)
                val names = containerNames(runtime)
                EngineHttp.Response.json(200, if (libpod != null) LibpodApi.networkJson(network, names) else DockerApi.networkJson(network, names))
            }
            DockerApi.Call.NetworkCreate -> networksLock.withLock {
                val existing = withContext(Dispatchers.IO) { ContainerNetworks.read(app.filesDir) }
                val features = runCatching { EngineFeatureProbe.current(app) }.getOrNull()
                val (network, refused) = DockerApi.networkRequest(String(request.body, Charsets.UTF_8), existing, features)
                refused?.let { return@withLock it }
                withContext(Dispatchers.IO) { ContainerNetworks.save(app.filesDir, ContainerNetworks.with(existing, network!!)) }
                EngineHttp.Response.json(201, DockerApi.networkCreated(network!!.id))
            }
            is DockerApi.Call.NetworkRemove -> networksLock.withLock {
                if (ContainerNetworks.PREDEFINED.any { it == call.id || ContainerNetworks.predefinedId(it) == call.id }) {
                    return@withLock DockerApi.error(403, "${call.id} is a pre-defined network and cannot be removed")
                }
                val existing = withContext(Dispatchers.IO) { ContainerNetworks.read(app.filesDir) }
                val network = ContainerNetworks.find(existing, call.id) ?: return@withLock missing(libpod, DockerApi.Kind.NETWORK, call.id)
                val live = runtime?.let { runCatching { it.listContainers() }.getOrNull() }.orEmpty().map { it.container.id }.toSet()
                if (network.members.any { it in live }) {
                    return@withLock DockerApi.error(403, "error while removing network: network ${network.name} has active endpoints")
                }
                withContext(Dispatchers.IO) { ContainerNetworks.save(app.filesDir, ContainerNetworks.without(existing, network.id)) }
                EngineHttp.Response(204, "text/plain", ByteArray(0))
            }
            is DockerApi.Call.NetworkConnect -> networksLock.withLock {
                runtime ?: return@withLock DockerApi.error(500, "the desktop is not running")
                val ref = DockerApi.connectTarget(String(request.body, Charsets.UTF_8)) ?: return@withLock DockerApi.error(400, "name a Container")
                val info = find(runtime.listContainers(), ref) ?: return@withLock missing(libpod, DockerApi.Kind.CONTAINER, ref)
                val existing = withContext(Dispatchers.IO) { ContainerNetworks.read(app.filesDir) }
                if (call.id in ContainerNetworks.PREDEFINED) return@withLock EngineHttp.Response(200, "text/plain", ByteArray(0))
                val network = ContainerNetworks.find(existing, call.id) ?: return@withLock missing(libpod, DockerApi.Kind.NETWORK, call.id)
                val next = if (call.connect) ContainerNetworks.withMember(existing, network.id, info.container.id)
                else ContainerNetworks.withoutMember(existing, info.container.id, network.id)
                withContext(Dispatchers.IO) { ContainerNetworks.save(app.filesDir, next) }
                EngineHttp.Response(200, "text/plain", ByteArray(0))
            }
            is DockerApi.Call.ExecCreate -> withContainer(libpod, runtime, call.containerId) { _, info ->
                // Behind the same switch as droidtop's own docker exec: the container's "Run its commands from the
                // desktop" (docs/SPEC.md 3d).
                val allowed = withContext(Dispatchers.IO) { dev.droidtop.runtime.ContainerEnter.allowed(app.filesDir, info.container.id) }
                if (!allowed) return@withContainer DockerApi.error(409, "${info.displayName} does not run commands from the desktop (its page in Containers)")
                val (spec, refused) = DockerApi.execRequest(String(request.body, Charsets.UTF_8))
                refused?.let { return@withContainer it }
                if (execs.size >= MAX_EXECS) execs.keys.firstOrNull { execs[it]?.running == false }?.let { execs.remove(it) }
                val id = dev.droidtop.runtime.ContainerNetworks.newId()
                execs[id] = Exec(info.container.id, spec!!)
                EngineHttp.Response.json(201, DockerApi.execCreated(id))
            }
            is DockerApi.Call.ExecStart -> {
                val exec = execs[call.execId] ?: return noExec(libpod, call.execId)
                runtime ?: return DockerApi.error(500, "the desktop is not running")
                val info = runtime.listContainers().firstOrNull { it.container.id == exec.containerId }
                    ?: return missing(libpod, DockerApi.Kind.CONTAINER, exec.containerId)
                if (exec.running || exec.exitCode != null) return DockerApi.error(409, "exec ${call.execId} has already run")
                exec.running = true
                val run: suspend () -> dev.droidtop.runtime.ContainerExecResult? = {
                    try {
                        if (runtime.siblingsNeedStart && !info.running) runtime.start(info.container)
                        runCatching {
                            runtime.exec(
                                info.container,
                                dev.droidtop.runtime.ContainerEnter.directCommand(exec.spec.command),
                                exec.spec.env + dev.droidtop.runtime.ContainerEnter.execEnvironment(exec.spec.workdir),
                            )
                        }.getOrNull().also { exec.exitCode = it?.exitCode ?: 126 }
                    } finally {
                        exec.running = false
                    }
                }
                if (DockerApi.detached(String(request.body, Charsets.UTF_8))) {
                    execScope.launch { run() }
                    return EngineHttp.Response(200, "text/plain", ByteArray(0))
                }
                fun frames(result: dev.droidtop.runtime.ContainerExecResult?) =
                    DockerApi.outputFrames(result?.stdout.orEmpty(), result?.stderr ?: "the command could not be run\n")
                // Podman's client reads the output from the socket itself once it has the head, so a byte sent with the
                // head is lost in its read-ahead: the head goes first, the frames once the command has run, and the
                // connection then closes, as on Podman's own service.
                if (libpod != null) {
                    return EngineHttp.Response(200, "application/vnd.docker.raw-stream", ByteArray(0), stream = { out ->
                        out.write(frames(run()))
                        out.flush()
                    })
                }
                val output = frames(run())
                // Docker's CLI asks to take the connection over (Upgrade: tcp); a client that does not gets the frames
                // as an ordinary body.
                val upgrade = request.headers["upgrade"]?.equals("tcp", ignoreCase = true) == true
                EngineHttp.Response(if (upgrade) 101 else 200, "application/vnd.docker.multiplexed-stream", output)
            }
            is DockerApi.Call.Attach -> withContainer(libpod, runtime, call.id) { _, info ->
                val log = dev.droidtop.runtime.ContainerProgram.hostLogFile(app.filesDir, info.container.id)
                val upgrade = request.headers["upgrade"]?.equals("tcp", ignoreCase = true) == true
                EngineHttp.Response(
                    status = if (upgrade) 101 else 200,
                    contentType = "application/vnd.docker.multiplexed-stream",
                    body = ByteArray(0),
                    stream = { out -> followLog(log, info.container.id, fromStart = call.logs, live = call.stream, out) },
                )
            }
            is DockerApi.Call.ExecGet -> {
                val exec = execs[call.execId] ?: return noExec(libpod, call.execId)
                EngineHttp.Response.json(200, DockerApi.execInspect(call.execId, exec.containerId, exec.spec, exec.running, exec.exitCode))
            }
            is DockerApi.Call.ExecRemove -> {
                val exec = execs[call.execId] ?: return noExec(libpod, call.execId)
                if (exec.running) return DockerApi.error(409, "exec session ${call.execId} is running and cannot be removed")
                execs.remove(call.execId)
                EngineHttp.Response(200, "text/plain", ByteArray(0))
            }
            is DockerApi.Call.Exists -> if (exists(app, runtime, call)) EngineHttp.Response(204, "text/plain", ByteArray(0)) else missing(libpod, call.kind, call.id)
            is DockerApi.Call.Pods -> {
                runtime ?: return DockerApi.error(500, "the desktop is not running")
                LibpodApi.unknownPodFilters(call.filters).takeIf { it.isNotEmpty() }?.let { return LibpodApi.notBuilt("pod filters ${it.joinToString(", ")}") }
                EngineHttp.Response.json(200, LibpodApi.pods(podFacts(app, runtime, EngineFacts.pods(app)), call.filters))
            }
            is DockerApi.Call.PodGet -> {
                runtime ?: return DockerApi.error(500, "the desktop is not running")
                val pod = ContainerPods.find(EngineFacts.pods(app), call.id) ?: return missing(libpod, DockerApi.Kind.POD, call.id)
                EngineHttp.Response.json(200, LibpodApi.pod(podFacts(app, runtime, listOf(pod)).single()))
            }
            is DockerApi.Call.Refused -> DockerApi.error(call.status, call.message)
            is DockerApi.Call.NotBuilt -> notBuilt(libpod, call.what)
            DockerApi.Call.NotFound -> DockerApi.error(404, "page not found")
        }
    }

    /** Removes [info] through the one removal path; libpod answers with a report per container, Docker with 204. */
    private suspend fun remove(
        app: Context,
        libpod: LibpodApi.Version?,
        runtime: dev.droidtop.runtime.ContainerRuntime,
        info: ContainerInfo,
        force: Boolean,
    ): EngineHttp.Response = when (val removal = EngineActions.remove(app, runtime, info, force)) {
        EngineActions.Removal.Removed ->
            if (libpod != null) EngineHttp.Response.json(200, LibpodApi.removed(listOf(info.container.id))) else EngineHttp.Response(204, "text/plain", ByteArray(0))
        EngineActions.Removal.Primary -> DockerApi.error(409, "${info.displayName} is the desktop's own container; droidtop does not remove it")
        EngineActions.Removal.Running -> DockerApi.error(
            409,
            "You cannot remove a running container ${info.displayName}. Stop the container before attempting removal or force remove",
        )
        is EngineActions.Removal.Failed -> DockerApi.error(500, removal.message)
    }

    /**
     * A pull. Docker's answers its progress lines after the pull, or 500. libpod's takes Podman's policies (missing and
     * never take a stored image as it is; never pulls nothing) and, after its 200, says a failure in the stream, as
     * Podman's service does.
     */
    private suspend fun pull(app: Context, libpod: LibpodApi.Version?, call: DockerApi.Call.Pull): EngineHttp.Response {
        if (libpod == null) {
            return EngineActions.pull(app, call.reference).fold(
                onSuccess = { EngineHttp.Response.json(200, DockerApi.pulled(call.reference, it)) },
                onFailure = { DockerApi.error(500, it.message ?: it.javaClass.simpleName) },
            )
        }
        val stored = DockerApi.findImage(OciImageStore.of(app).entries(), call.reference)
        if (stored != null && call.policy in setOf("missing", "never")) return EngineHttp.Response.json(200, LibpodApi.present(stored.digest))
        if (call.policy == "never") return EngineHttp.Response.json(200, LibpodApi.pullFailed("${call.reference}: image not known, and the pull policy is never"))
        return EngineActions.pull(app, call.reference).fold(
            onSuccess = { EngineHttp.Response.json(200, LibpodApi.pulled(call.reference, it)) },
            onFailure = { EngineHttp.Response.json(200, LibpodApi.pullFailed(it.message ?: it.javaClass.simpleName)) },
        )
    }

    /** 404 for an exec that is not there; Podman's client knows it by "no such exec session". */
    private fun noExec(libpod: LibpodApi.Version?, id: String) =
        if (libpod != null) LibpodApi.error(404, "no exec session with ID $id found: no such exec session", "no such exec session")
        else DockerApi.error(404, "No such exec instance: $id")

    /** 404 for [ref] in the asking API's words. */
    private fun missing(libpod: LibpodApi.Version?, kind: DockerApi.Kind, ref: String) =
        if (libpod != null) LibpodApi.missing(kind, ref) else DockerApi.missing(kind, ref)

    /** 501 for [what], naming the API's own section of docs/SPEC.md. */
    private fun notBuilt(libpod: LibpodApi.Version?, what: String) = if (libpod != null) LibpodApi.notBuilt(what) else DockerApi.notBuilt(what)

    /** Whether what [call] names is there: a container, a stored image, a pod or a network. */
    private suspend fun exists(app: Context, runtime: dev.droidtop.runtime.ContainerRuntime?, call: DockerApi.Call.Exists): Boolean = when (call.kind) {
        DockerApi.Kind.CONTAINER -> runtime?.let { find(it.listContainers(), call.id) } != null
        DockerApi.Kind.IMAGE -> DockerApi.findImage(OciImageStore.of(app).entries(), call.id) != null
        DockerApi.Kind.POD -> ContainerPods.find(EngineFacts.pods(app), call.id) != null
        DockerApi.Kind.NETWORK -> ContainerNetworks.find(ContainerNetworks.predefined() + withContext(Dispatchers.IO) { ContainerNetworks.read(app.filesDir) }, call.id) != null
    }

    /** [pods] with the facts of their members that still exist. */
    private suspend fun podFacts(app: Context, runtime: dev.droidtop.runtime.ContainerRuntime, pods: List<Pod>): List<LibpodApi.PodFacts> {
        val all = EngineFacts.pods(app)
        val containers = runtime.listContainers()
        return pods.map { pod ->
            LibpodApi.PodFacts(pod, pod.members.mapNotNull { id -> containers.firstOrNull { it.container.id == id }?.let { EngineFacts.of(app, runtime, it, all) } })
        }
    }

    /** An exec made by `POST /containers/{id}/exec`, until it is replaced: Docker's clients read its exit code after. */
    private class Exec(val containerId: String, val spec: DockerApi.ExecSpec) {
        @Volatile var running = false
        @Volatile var exitCode: Int? = null
    }

    /** The execs this session made, the oldest finished one dropped past [MAX_EXECS]. */
    private val execs = java.util.concurrent.ConcurrentHashMap<String, Exec>()
    private const val MAX_EXECS = 256
    private val execScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Attach's stream (docs/SPEC.md 3d, "Attach"): a program's output goes to its log, so attach follows that file and
     * sends what is added as stdout frames, from where it ends now (or from the start with [fromStart]). With [live]
     * it goes on until the program has run and ended: attach comes before start, so it waits for the start, giving up
     * after [ATTACH_START_WAIT_MS] if none comes. The log may be trimmed under it, which starts it from the top.
     */
    private suspend fun followLog(log: File, containerId: String, fromStart: Boolean, live: Boolean, out: java.io.OutputStream) {
        var offset = if (fromStart) 0L else withContext(Dispatchers.IO) { log.length() }
        val startBy = System.currentTimeMillis() + ATTACH_START_WAIT_MS
        var started = ContainerPrograms.isRunning(containerId)
        // A program that starts and ends between two looks is seen by its state changing to over since the attach.
        val before = ContainerPrograms.state(containerId)
        while (true) {
            offset = withContext(Dispatchers.IO) {
                val size = log.length()
                val from = if (size < offset) 0L else offset
                if (size > from) {
                    val bytes = java.io.RandomAccessFile(log, "r").use { f ->
                        f.seek(from)
                        ByteArray(minOf(size - from, MAX_FRAME.toLong()).toInt()).also { f.readFully(it) }
                    }
                    out.write(DockerApi.frame(1, bytes))
                    out.flush()
                    from + bytes.size
                } else {
                    from
                }
            }
            if (!live) return
            val running = ContainerPrograms.isRunning(containerId)
            val now = ContainerPrograms.state(containerId)
            if (running || (now !== before && (now is ContainerPrograms.State.Ended || now is ContainerPrograms.State.Failed))) started = true
            if (started && !running) {
                if (withContext(Dispatchers.IO) { log.length() } <= offset) return
            } else if (!started && System.currentTimeMillis() > startBy) {
                return
            } else {
                delay(WAIT_LOOK_MS)
            }
        }
    }

    private const val ATTACH_START_WAIT_MS = 5 * 60_000L
    private const val REMOVED_WAIT_MS = 30_000L
    private const val MAX_FRAME = 64 * 1024

    /** One network change at a time: each reads, changes and writes networks.json whole. */
    private val networksLock = Mutex()

    /** Every container's name by its id, for a network's members; empty with the desktop down. */
    private suspend fun containerNames(runtime: dev.droidtop.runtime.ContainerRuntime?): Map<String, String> =
        runtime?.let { runCatching { it.listContainers() }.getOrNull() }.orEmpty().associate { it.container.id to it.displayName }

    /** [block] with the running desktop's runtime and the container [ref] names; 500 or 404 otherwise. */
    private suspend fun withContainer(
        libpod: LibpodApi.Version?,
        runtime: dev.droidtop.runtime.ContainerRuntime?,
        ref: String,
        block: suspend (dev.droidtop.runtime.ContainerRuntime, ContainerInfo) -> EngineHttp.Response,
    ): EngineHttp.Response {
        runtime ?: return DockerApi.error(500, "the desktop is not running")
        val info = find(runtime.listContainers(), ref) ?: return missing(libpod, DockerApi.Kind.CONTAINER, ref)
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
