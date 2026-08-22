package dev.droidtop.runtime.linux.noroot

import dev.droidtop.runtime.Container
import dev.droidtop.runtime.ContainerBackend
import dev.droidtop.runtime.ContainerExecResult
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.RootfsImage
import java.io.File

/**
 * No-root Linux container backend. No existing project is a clean fork
 * target for this — it's new code following the pattern Termux's
 * proot-distro and Box64Droid already use: ptrace-based syscall
 * interception (via proot) instead of real kernel namespaces, trading
 * isolation and some performance for not requiring root.
 *
 * Must expose the exact same primary/sibling container model as
 * runtime-linux-root's DroidSpacesRuntime — same bootstrap profile concept,
 * same Wayland/PulseAudio socket sharing for siblings — so callers above
 * runtime-common never need to know which backend is active.
 */
class ProotRuntime : ContainerRuntime {
    override val backend: ContainerBackend = ContainerBackend.PROOT

    override suspend fun createPrimary(image: RootfsImage): Container {
        TODO("proot rootfs bootstrap from $image + a compositor (see docs/SPEC.md §2/§3a) as the primary display owner")
    }

    override suspend fun createSibling(image: RootfsImage): Container {
        TODO("proot rootfs bootstrap from $image; share primary's WAYLAND_DISPLAY + PulseAudio socket via bind mount")
    }

    override suspend fun start(container: Container): Unit = TODO()
    override suspend fun stop(container: Container): Unit = TODO()
    override suspend fun destroy(container: Container): Unit = TODO()

    override suspend fun exec(container: Container, command: List<String>, env: Map<String, String>): ContainerExecResult =
        TODO("re-enter the proot rootfs and run $command (env=$env) inside it")

    override fun primaryWaylandSocketPath(): String = TODO()

    // proot's own -b/--bind is the equivalent primitive DroidSpacesRuntime's
    // bind-mount-the-whole-app-storage-dir approach relies on; same TODO as
    // the rest of this class until proot bootstrap itself is real.
    override fun hostStorageToContainerPath(hostPath: File): String = TODO()
}
