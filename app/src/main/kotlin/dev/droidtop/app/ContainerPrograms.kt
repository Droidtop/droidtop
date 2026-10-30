package dev.droidtop.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.droidtop.runtime.Container
import dev.droidtop.runtime.ContainerProgram
import dev.droidtop.runtime.ContainerRole
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.ContainerUser
import dev.droidtop.runtime.ExposedPort
import dev.droidtop.runtime.LogTimes
import dev.droidtop.runtime.nameOf
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app containers' own programs that droidtop runs (docs/SPEC.md 3 "The image's own program", Droidtop/tracker#555
 * D6b): one job per container in the process's scope, so a program keeps running whatever screen is open, and ends
 * with droidtop's process (proot sessions are killed on exit). Stop cancels the job, which ends the program's session.
 */
internal object ContainerPrograms {
    private const val TAG = "droidtop.Programs"
    private val scope = dev.droidtop.runtime.util.AppScopes.io
    private val jobs = ConcurrentHashMap<String, Job>()

    /** The ports each started program's image says it listens on, by container id, with its name: what a start is checked against. */
    private val listening = HashMap<String, Pair<String, List<ExposedPort>>>()

    /** What a container's program is doing, for its page. */
    sealed interface State {
        data object Stopped : State
        data class Running(val sinceMs: Long) : State
        data class Waiting(val restartInMs: Long, val lastExit: Int) : State
        data class Ended(val exitCode: Int, val gaveUp: Boolean) : State
        data class Failed(val message: String) : State
    }

    private val states = MutableStateFlow<Map<String, State>>(emptyMap())
    val all: StateFlow<Map<String, State>> = states

    fun state(containerId: String): State = states.value[containerId] ?: State.Stopped

    /** When a program last started and last ended, in this run of droidtop: what the engine APIs give as its times. */
    data class Times(val startedMs: Long?, val endedMs: Long?)

    private val times = java.util.concurrent.ConcurrentHashMap<String, Times>()

    fun times(containerId: String): Times = times[containerId] ?: Times(null, null)

    private fun set(containerId: String, state: State) {
        states.value = states.value + (containerId to state)
    }

    fun isRunning(containerId: String): Boolean = jobs[containerId]?.isActive == true

    /** Running, or about to run again (a restart waiting): not over yet. */
    fun busy(containerId: String): Boolean =
        isRunning(containerId) || state(containerId).let { it is State.Running || it is State.Waiting }

    /**
     * Whether [containerId]'s program has run since [before] (its state then) was read: seen running, or its state
     * turned to over since. A quick program can do both between two looks, so the state's change is what tells.
     */
    fun ranSince(containerId: String, before: State): Boolean =
        isRunning(containerId) || state(containerId).let { it !== before && (it is State.Ended || it is State.Failed) }

    /** Waits while [containerId]'s program is [busy]. */
    suspend fun awaitOver(containerId: String) {
        while (busy(containerId)) delay(LOOK_MS)
    }

    /**
     * Waits for a program that has not started yet to start, within [startWithinMs], and then to end: the one rule for
     * `wait` before a start, attach's `--rm` removal, and `--rm`. False when it never started in that time.
     */
    suspend fun awaitRun(containerId: String, before: State, startWithinMs: Long): Boolean {
        val until = System.currentTimeMillis() + startWithinMs
        while (!ranSince(containerId, before) && System.currentTimeMillis() < until) delay(LOOK_MS)
        if (!ranSince(containerId, before)) return false
        awaitOver(containerId)
        return true
    }

    private const val LOOK_MS = 250L

    /**
     * Starts [container]'s program from its kept image config, unless it is running. Does nothing for a container
     * whose image names no program. Restarts after a failure when the container's setting says so, waiting longer
     * after each quick failure and giving up after [ContainerProgram.MAX_FAILURES_IN_A_ROW].
     */
    fun start(context: Context, runtime: ContainerRuntime, container: Container) {
        if (isRunning(container.id)) return
        val app = context.applicationContext
        val job = scope.launch {
            val config = runtime.imageRunConfig(container)?.let { ContainerProgram.effectiveConfig(app.filesDir, container.id, it) }
                ?.takeIf { it.program().isNotEmpty() } ?: run {
                set(container.id, State.Failed("Its image names no program to run"))
                return@launch
            }
            // No network namespaces: every program listens on the device's own ports, so a second program on a port
            // one already holds does not start (docs/SPEC.md 3 "Ports").
            val name = runCatching { runtime.nameOf(container) }.getOrDefault(container.id)
            val clash = synchronized(listening) {
                val others = listening.filterKeys { it != container.id }.values.toMap()
                ExposedPort.clash(name, config.ports, others).also { if (it == null) listening[container.id] = name to config.ports }
            }
            if (clash != null) {
                set(container.id, State.Failed(clash))
                return@launch
            }
            val log = ContainerProgram.hostLogFile(app.filesDir, container.id).apply { parentFile?.mkdirs() }
            // As its image's user, or -u's (Droidtop/tracker#715). The script opens the log before it switches.
            val user = ContainerUser.parse(config.user)
            // Each second the log grew, its length and the time (`logs --since`, Droidtop/tracker#715); every
            // TRIM_EVERY_MS it is trimmed, and the times move with it. One loop, so the two never cross.
            val trimmer = launch {
                val times = LogTimes.fileFor(log)
                var marked = -1L
                var trimmedAt = 0L
                while (isActive) {
                    runCatching {
                        val now = System.currentTimeMillis()
                        if (now - trimmedAt >= TRIM_EVERY_MS) {
                            trimmedAt = now
                            val removed = ContainerProgram.trim(log)
                            if (removed > 0 || times.length() > LogTimes.MAX_FILE_BYTES) LogTimes.shift(times, removed)
                            marked -= removed
                        }
                        val length = log.length()
                        if (length != marked) {
                            LogTimes.append(times, LogTimes.Mark(length, now))
                            marked = length
                        }
                    }
                    delay(LogTimes.MARK_EVERY_MS)
                }
            }
            var failures = 0
            try {
                while (isActive) {
                    if (runtime.siblingsNeedStart) runCatching { runtime.start(container) }
                    val startedAt = SystemClock.elapsedRealtime()
                    val now = System.currentTimeMillis()
                    times[container.id] = Times(now, null)
                    set(container.id, State.Running(now))
                    Log.i(TAG, "${container.id}: starting ${config.program().joinToString(" ")}")
                    val result = runCatching {
                        runtime.exec(container, ContainerProgram.command(config, container.id), ContainerProgram.environment(config), user)
                    }
                    if (!isActive) break
                    val exit = result.getOrNull()?.exitCode ?: -1
                    val ranMs = SystemClock.elapsedRealtime() - startedAt
                    failures = if (ranMs >= ContainerProgram.RAN_LONG_ENOUGH_MS) 0 else failures + 1
                    Log.i(TAG, "${container.id}: ended with $exit after $ranMs ms")
                    val policy = restartPolicy(app, container.id)
                    if (!ContainerProgram.restarts(policy, exit, failures)) {
                        val gaveUp = policy != ContainerProgram.RestartPolicy.No && (exit != 0 || policy.withDesktop)
                        set(container.id, result.exceptionOrNull()?.let { State.Failed(it.message ?: it.javaClass.simpleName) } ?: State.Ended(exit, gaveUp))
                        break
                    }
                    val wait = ContainerProgram.restartDelayMs(failures.coerceAtLeast(1))
                    set(container.id, State.Waiting(wait, exit))
                    delay(wait)
                }
            } finally {
                times[container.id]?.let { times[container.id] = it.copy(endedMs = System.currentTimeMillis()) }
                trimmer.cancel()
                synchronized(listening) { listening.remove(container.id) }
                jobs.remove(container.id)
            }
        }
        jobs[container.id] = job
    }

    /** Stops [containerId]'s program: its session ends with the job. */
    fun stop(containerId: String) {
        jobs.remove(containerId)?.cancel()
        set(containerId, State.Stopped)
    }

    /**
     * Starts every container whose "Start with the desktop" is on, once the desktop session is up: its program when its
     * image names one, else the container itself, a sibling with nothing of its own to run but its init
     * (Droidtop/tracker#808, docs/SPEC.md 3d "Start with the desktop").
     */
    suspend fun startWithDesktop(context: Context, runtime: ContainerRuntime) {
        val ids = settings(context).stringPropertyNames().filter { it.endsWith(KEY_AUTOSTART) }.map { it.removeSuffix(KEY_AUTOSTART) }
            .filter { autostart(context, it) }
        if (ids.isEmpty()) return
        runtime.listContainers().filter { it.container.id in ids }.forEach { info ->
            val program = runCatching { runtime.imageRunConfig(info.container) }.getOrNull()?.program().orEmpty()
            when {
                program.isNotEmpty() -> start(context, runtime, info.container)
                info.container.role == ContainerRole.SIBLING && runtime.siblingsNeedStart && !info.running ->
                    runCatching { runtime.start(info.container) }.onFailure { Log.w(TAG, "Couldn't start ${info.container.id} with the desktop", it) }
            }
        }
    }

    // ---- the per-container settings ----

    private const val KEY_RESTART = ".restart"
    private const val KEY_AUTOSTART = ".autostart"
    private const val KEY_CREATED = ".created"
    private const val TRIM_EVERY_MS = 30_000L

    private fun settingsFile(context: Context) = File(File(context.filesDir, ContainerProgram.LOG_DIR_NAME), "programs.properties")

    private fun settings(context: Context): Properties =
        Properties().apply { runCatching { settingsFile(context).inputStream().use { load(it) } } }

    private fun saveSetting(context: Context, key: String, on: Boolean) {
        val props = settings(context)
        props[key] = on.toString()
        store(context, props)
    }

    private fun store(context: Context, props: Properties) {
        val file = settingsFile(context).apply { parentFile?.mkdirs() }
        file.outputStream().use { props.store(it, "droidtop app container programs") }
    }

    /**
     * When the program is started again (docs/SPEC.md 3d, "Restart policies"): droidtop's default, restart when it
     * fails, for a container made on its page; the one `--restart` gave (`no` by default) for one `docker run` or
     * `podman run` made. The page's old true and false read as on-failure and no.
     */
    fun restartPolicy(context: Context, containerId: String): ContainerProgram.RestartPolicy =
        ContainerProgram.RestartPolicy.stored(settings(context).getProperty(containerId + KEY_RESTART))

    /** Sets the policy; `always` and `unless-stopped` start the program with the desktop, as Docker's daemon does. */
    fun setRestartPolicy(context: Context, containerId: String, policy: ContainerProgram.RestartPolicy) {
        val props = settings(context)
        props[containerId + KEY_RESTART] = policy.words()
        if (policy.withDesktop) props[containerId + KEY_AUTOSTART] = "true"
        store(context, props)
    }

    /**
     * The person stopped the program (its page's Stop it, `docker stop`): under `unless-stopped` it then stays stopped
     * when the desktop starts again, until it is started.
     */
    suspend fun stoppedByPerson(context: Context, containerId: String) {
        stop(containerId)
        withContext(Dispatchers.IO) {
            if (restartPolicy(context, containerId) == ContainerProgram.RestartPolicy.UnlessStopped) setAutostart(context, containerId, false)
        }
    }

    /** The person started the program: under `unless-stopped` it starts with the desktop again. */
    suspend fun startedByPerson(context: Context, runtime: ContainerRuntime, container: Container) {
        withContext(Dispatchers.IO) {
            if (restartPolicy(context, container.id) == ContainerProgram.RestartPolicy.UnlessStopped) setAutostart(context, container.id, true)
        }
        start(context, runtime, container)
    }

    /** Start with the desktop: off by default; a program runs because the person started it or asked for this. */
    fun autostart(context: Context, containerId: String): Boolean =
        settings(context).getProperty(containerId + KEY_AUTOSTART)?.toBooleanStrictOrNull() ?: false

    fun setAutostart(context: Context, containerId: String, on: Boolean) = saveSetting(context, containerId + KEY_AUTOSTART, on)

    /**
     * Notes that droidtop made [containerId] now, for `ps`'s CREATED (docs/SPEC.md 3d, "Each tool's output"): every
     * place droidtop creates a container calls it. A container made before has none.
     */
    suspend fun recordCreated(context: Context, containerId: String) = withContext(Dispatchers.IO) {
        val props = settings(context)
        props[containerId + KEY_CREATED] = System.currentTimeMillis().toString()
        store(context, props)
    }

    /** When droidtop made [containerId] (epoch ms), or null when it did not note it. */
    fun created(context: Context, containerId: String): Long? = settings(context).getProperty(containerId + KEY_CREATED)?.toLongOrNull()

    /** Forgets [containerId]: its program stopped, its settings and its log gone (the container was deleted). */
    fun forget(context: Context, containerId: String) {
        stop(containerId)
        val props = settings(context)
        props.remove(containerId + KEY_RESTART)
        props.remove(containerId + KEY_AUTOSTART)
        props.remove(containerId + KEY_CREATED)
        runCatching { settingsFile(context).outputStream().use { props.store(it, "droidtop app container programs") } }
        ContainerProgram.hostLogFile(context.filesDir, containerId).let { log ->
            log.delete()
            LogTimes.fileFor(log).delete()
        }
        ContainerProgram.overridesFile(context.filesDir, containerId).delete()
        states.value = states.value - containerId
    }
}
