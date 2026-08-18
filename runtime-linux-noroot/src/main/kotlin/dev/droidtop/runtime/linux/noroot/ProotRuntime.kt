package dev.droidtop.runtime.linux.noroot

import dev.droidtop.runtime.Container
import dev.droidtop.runtime.ContainerBackend
import dev.droidtop.runtime.ContainerRuntime

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

    override suspend fun createPrimary(): Container {
        TODO("proot rootfs bootstrap + vendor/sway (headless) as the primary display owner")
    }

    override suspend fun createSibling(): Container {
        TODO("proot rootfs bootstrap; share primary's WAYLAND_DISPLAY + PulseAudio socket via bind mount")
    }

    override suspend fun start(container: Container): Unit = TODO()
    override suspend fun stop(container: Container): Unit = TODO()
    override suspend fun destroy(container: Container): Unit = TODO()
}
