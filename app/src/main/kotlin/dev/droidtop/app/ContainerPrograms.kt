package dev.droidtop.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.droidtop.runtime.Container
import dev.droidtop.runtime.ContainerProgram
import dev.droidtop.runtime.ContainerRuntime
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The app containers' own programs that droidtop runs (docs/SPEC.md 3 "The image's own program", Droidtop/tracker#555
 * D6b): one job per container in the process's scope, so a program keeps running whatever screen is open, and ends
 * with droidtop's process (proot sessions are killed on exit). Stop cancels the job, which ends the program's session.
 */
internal object ContainerPrograms {
    private const val TAG = "droidtop.Programs"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

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

    private fun set(containerId: String, state: State) {
        states.value = states.value + (containerId to state)
    }

    fun isRunning(containerId: String): Boolean = jobs[containerId]?.isActive == true

    /**
     * Starts [container]'s program from its kept image config, unless it is running. Does nothing for a container
     * whose image names no program. Restarts after a failure when the container's setting says so, waiting longer
     * after each quick failure and giving up after [ContainerProgram.MAX_FAILURES_IN_A_ROW].
     */
    fun start(context: Context, runtime: ContainerRuntime, container: Container) {
        if (isRunning(container.id)) return
        val app = context.applicationContext
        val job = scope.launch {
            val config = runtime.imageRunConfig(container)?.takeIf { it.program().isNotEmpty() } ?: run {
                set(container.id, State.Failed("Its image names no program to run"))
                return@launch
            }
            val log = ContainerProgram.hostLogFile(app.filesDir, container.id).apply { parentFile?.mkdirs() }
            val trimmer = launch {
                while (isActive) {
                    runCatching { ContainerProgram.trim(log) }
                    delay(TRIM_EVERY_MS)
                }
            }
            var failures = 0
            try {
                while (isActive) {
                    if (runtime.siblingsNeedStart) runCatching { runtime.start(container) }
                    val startedAt = SystemClock.elapsedRealtime()
                    set(container.id, State.Running(System.currentTimeMillis()))
                    Log.i(TAG, "${container.id}: starting ${config.program().joinToString(" ")}")
                    val result = runCatching {
                        runtime.exec(container, ContainerProgram.command(config, container.id), ContainerProgram.environment(config))
                    }
                    if (!isActive) break
                    val exit = result.getOrNull()?.exitCode ?: -1
                    val ranMs = SystemClock.elapsedRealtime() - startedAt
                    failures = if (ranMs >= ContainerProgram.RAN_LONG_ENOUGH_MS) 0 else failures + 1
                    Log.i(TAG, "${container.id}: ended with $exit after $ranMs ms")
                    if (!ContainerProgram.restarts(restartOnFailure(app, container.id), exit, failures)) {
                        val gaveUp = exit != 0 && restartOnFailure(app, container.id)
                        set(container.id, result.exceptionOrNull()?.let { State.Failed(it.message ?: it.javaClass.simpleName) } ?: State.Ended(exit, gaveUp))
                        break
                    }
                    val wait = ContainerProgram.restartDelayMs(failures.coerceAtLeast(1))
                    set(container.id, State.Waiting(wait, exit))
                    delay(wait)
                }
            } finally {
                trimmer.cancel()
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

    /** Starts every container's program whose "Start with the desktop" is on: called once the desktop session is up. */
    suspend fun startWithDesktop(context: Context, runtime: ContainerRuntime) {
        val ids = settings(context).stringPropertyNames().filter { it.endsWith(KEY_AUTOSTART) }.map { it.removeSuffix(KEY_AUTOSTART) }
            .filter { autostart(context, it) }
        if (ids.isEmpty()) return
        runtime.listContainers().filter { it.container.id in ids }.forEach { start(context, runtime, it.container) }
    }

    // ---- the per-container settings ----

    private const val KEY_RESTART = ".restart"
    private const val KEY_AUTOSTART = ".autostart"
    private const val TRIM_EVERY_MS = 30_000L

    private fun settingsFile(context: Context) = File(File(context.filesDir, ContainerProgram.LOG_DIR_NAME), "programs.properties")

    private fun settings(context: Context): Properties =
        Properties().apply { runCatching { settingsFile(context).inputStream().use { load(it) } } }

    private fun saveSetting(context: Context, key: String, on: Boolean) {
        val props = settings(context)
        props[key] = on.toString()
        val file = settingsFile(context).apply { parentFile?.mkdirs() }
        file.outputStream().use { props.store(it, "droidtop app container programs") }
    }

    /** Restart when it fails: on by default, as a server is expected to stay up. */
    fun restartOnFailure(context: Context, containerId: String): Boolean =
        settings(context).getProperty(containerId + KEY_RESTART)?.toBooleanStrictOrNull() ?: true

    fun setRestartOnFailure(context: Context, containerId: String, on: Boolean) = saveSetting(context, containerId + KEY_RESTART, on)

    /** Start with the desktop: off by default; a program runs because the person started it or asked for this. */
    fun autostart(context: Context, containerId: String): Boolean =
        settings(context).getProperty(containerId + KEY_AUTOSTART)?.toBooleanStrictOrNull() ?: false

    fun setAutostart(context: Context, containerId: String, on: Boolean) = saveSetting(context, containerId + KEY_AUTOSTART, on)

    /** Forgets [containerId]: its program stopped, its settings and its log gone (the container was deleted). */
    fun forget(context: Context, containerId: String) {
        stop(containerId)
        val props = settings(context)
        props.remove(containerId + KEY_RESTART)
        props.remove(containerId + KEY_AUTOSTART)
        runCatching { settingsFile(context).outputStream().use { props.store(it, "droidtop app container programs") } }
        ContainerProgram.hostLogFile(context.filesDir, containerId).delete()
        states.value = states.value - containerId
    }
}
