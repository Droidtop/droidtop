package dev.droidtop.hostbridge

import android.util.Log
import dev.droidtop.runtime.ContainerLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Makes, sizes, places and removes sway's headless outputs for the desktop session (Droidtop/tracker#908, step D2),
 * over sway's IPC ([SwayIpc], the socket in [hostSocketDir]) for what only sway can do, and host-bridge ([bridge])
 * for the output's name and size, which it already has from the compositor.
 *
 * Every call blocks on the socket and waits on the compositor, so each is a suspend function on [Dispatchers.IO].
 */
class SwayOutputs(private val bridge: HostBridge, private val hostSocketDir: File) {
    private fun ipc(): SwayIpc? = ContainerLayout.findSwayIpcSocket(hostSocketDir)?.let { SwayIpc(it.absolutePath) }

    /**
     * Asks sway for a new headless output and returns its name once host-bridge has it, so it can be sized and
     * presented by name; null when sway refused, or the output did not reach host-bridge in time.
     */
    suspend fun create(): String? = withContext(Dispatchers.IO) {
        val ipc = ipc() ?: return@withContext null.also { Log.w(TAG, "No sway IPC socket: cannot add an output") }
        val before = bridge.outputNames()
        if (!runCatching { ipc.run(DesktopOutputs.CREATE_COMMAND) }.onFailure { Log.w(TAG, "create_output failed", it) }.getOrDefault(false)) {
            Log.w(TAG, "sway refused create_output")
            return@withContext null
        }
        val name = waitFor { DesktopOutputs.newOutputName(before, bridge.outputNames()) }
        if (name == null) Log.w(TAG, "sway made an output, but it did not reach host-bridge")
        name
    }

    /**
     * Sizes the output [name] to [width] x [height] pixels at [scale] (as the primary is sized), then puts it right
     * of the others ([DesktopOutputs.positionX]). The position is sent once the compositor reports the new size: a
     * size configuration still in flight carries the output's old position and would undo it. True when sway took
     * the position.
     */
    suspend fun place(name: String, width: Int, height: Int, scale: Double): Boolean = withContext(Dispatchers.IO) {
        if (!bridge.setOutputSize(name, width, height, scale)) Log.w(TAG, "Could not ask for $name at ${width}x$height")
        if (waitFor { DesktopOutputs.sized(bridge.outputPlacements(), name, width, height).takeIf { it } } == null) {
            Log.w(TAG, "$name did not report ${width}x$height in time; placing it anyway")
        }
        val x = DesktopOutputs.positionX(bridge.outputPlacements(), name)
        val ipc = ipc() ?: return@withContext false
        runCatching { ipc.run(DesktopOutputs.positionCommand(name, x)) }
            .onFailure { Log.w(TAG, "Placing $name failed", it) }.getOrDefault(false)
            .also { Log.i(TAG, "Output $name ${width}x$height scale $scale at $x,0: ${if (it) "placed" else "not placed"}") }
    }

    /** Stops presenting the output [name] and has sway remove it. True when sway did. */
    suspend fun unplug(name: String): Boolean = withContext(Dispatchers.IO) {
        bridge.stopPresenting(name)
        val ipc = ipc() ?: return@withContext false
        runCatching { ipc.run(DesktopOutputs.unplugCommand(name)) }
            .onFailure { Log.w(TAG, "Unplugging $name failed", it) }.getOrDefault(false)
            .also { Log.i(TAG, "Output $name ${if (it) "unplugged" else "not unplugged"}") }
    }

    private suspend fun <T : Any> waitFor(read: () -> T?): T? {
        repeat(WAIT_STEPS) {
            read()?.let { return it }
            delay(WAIT_STEP_MS)
        }
        return read()
    }

    private companion object {
        const val TAG = "hostbridge.outputs"
        const val WAIT_STEP_MS = 50L

        /** 5 seconds: the compositor answers in milliseconds, but a busy first boot can be slow. */
        const val WAIT_STEPS = 100
    }
}
