package dev.droidtop.runtime.linux.root

import dev.droidtop.runtime.Container
import dev.droidtop.runtime.ContainerBackend
import dev.droidtop.runtime.ContainerRuntime

/**
 * Root-path Linux container backend, forked from vendor/droidspaces.
 *
 * Two changes from upstream DroidSpaces, both required by the shared-desktop
 * design in docs/SPEC.md:
 *
 *  1. [createPrimary] boots a container from a bootstrap profile that installs
 *     vendor/sway (built with the headless backend) plus a minimal desktop
 *     environment, and starts it as a supervised service — this is the one
 *     container host-bridge captures frames from / injects input into.
 *
 *  2. [createSibling] must NOT auto-launch a private Termux:X11 instance the
 *     way upstream DroidSpaces does by default. Instead it bind-mounts the
 *     primary container's Wayland socket (WAYLAND_DISPLAY) and PulseAudio
 *     socket in, so GUI apps launched inside just become windows on the
 *     shared desktop — this is the actual distrobox mechanism, applied here.
 */
class DroidSpacesRuntime : ContainerRuntime {
    override val backend: ContainerBackend = ContainerBackend.DROIDSPACES

    override suspend fun createPrimary(): Container {
        TODO("Port vendor/droidspaces container creation; apply primary bootstrap profile")
    }

    override suspend fun createSibling(): Container {
        TODO("Port vendor/droidspaces container creation; bind-mount primary's WAYLAND_DISPLAY + PulseAudio socket")
    }

    override suspend fun start(container: Container): Unit = TODO()
    override suspend fun stop(container: Container): Unit = TODO()
    override suspend fun destroy(container: Container): Unit = TODO()
}
