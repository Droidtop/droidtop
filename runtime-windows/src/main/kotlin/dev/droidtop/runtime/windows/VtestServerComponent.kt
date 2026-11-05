package dev.droidtop.runtime.windows

import com.winlator.core.ProcessHelper
import com.winlator.xenvironment.EnvironmentComponent
import com.winlator.xenvironment.XEnvironment
import dev.droidtop.runtime.windows.utils.X86_64Graphics
import java.io.File
import timber.log.Timber

/**
 * The vtest server of a Venus session (docs/SPEC.md 10b, "A real GPU on x86_64: Venus"; Droidtop/tracker#608).
 *
 * The guest's Venus driver (`libvulkan_virtio.so` in the x86_64 graphics archive) serialises its Vulkan calls onto
 * [socket]; this process, `libvirgl_vtest.so` in the APK (virglrenderer 1.3.0 with Termux's Android patches, built by
 * .github/workflows/x86_64-lavapipe.yml), replays them on Android's own Vulkan. One per session, started before the
 * guest and stopped with it, as the audio servers are.
 */
class VtestServerComponent(private val socket: File) : EnvironmentComponent() {
    @Volatile private var pid = -1

    override fun start() {
        val context = environment.context
        val server = X86_64Graphics.vtestServer(context)
        if (!server.isFile) {
            Timber.w("No vtest server in the APK (%s); Venus has nothing to talk to", server)
            return
        }
        socket.delete()
        val env = arrayOf("TMPDIR=${XEnvironment.getTmpDir(context)}", "LD_LIBRARY_PATH=/system/lib64")
        pid = ProcessHelper.exec(
            "${server.path} --venus --multi-clients --socket-path ${socket.path}",
            env,
            context.filesDir,
        ) { status -> Timber.i("vtest server exited with %d", status) }
        // The guest connects when it creates its Vulkan instance; give the server a moment to listen first.
        var waited = 0
        while (!socket.exists() && waited < 2000 && pid > 0) {
            Thread.sleep(50)
            waited += 50
        }
        Timber.i("vtest server pid %d, socket %s after %d ms", pid, if (socket.exists()) "up" else "missing", waited)
    }

    override fun stop() {
        if (pid > 0) ProcessHelper.terminateProcess(pid)
        pid = -1
        socket.delete()
    }
}
