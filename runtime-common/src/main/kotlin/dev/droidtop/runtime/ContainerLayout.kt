package dev.droidtop.runtime

import java.io.File

/**
 * What every droidtop container looks like from the inside, whichever
 * backend runs it. Both [ContainerRuntime] implementations map the same
 * host directories to the same in-container paths and hand processes the
 * same environment, so a program started in the primary container, in a
 * sibling, or through either backend finds the primary compositor the same
 * way (docs/SPEC.md 2, the distrobox-style shared socket).
 *
 * The primary container's first-boot script lives here too, for the same
 * reason: droidspaces writes it to `/sbin/init`, proot runs it as the
 * primary's long-lived process, and it is one script.
 */
object ContainerLayout {
    /**
     * In-container `XDG_RUNTIME_DIR`. Each backend maps ONE host directory
     * here in every container it creates, so the socket the primary's
     * compositor creates is the same file for every sibling and for
     * `:host-bridge`, which connects to the host side directly.
     */
    const val SOCKET_DIR = "/run/droidtop-sockets"

    /** Where the app's private storage root (`Context.getFilesDir()`) appears inside every container. */
    const val APP_STORAGE_DIR = "/run/droidtop-app-storage"

    /**
     * The compositor's socket name. The socket directory is emptied before
     * the primary starts, so a wlroots compositor's automatic naming picks
     * the first free name, which is this one.
     */
    const val WAYLAND_SOCKET_NAME = "wayland-0"

    /** Written once the provisioning command has succeeded; its presence skips provisioning on later boots. */
    const val PROVISIONED_MARKER = "/var/lib/droidtop-provisioned"

    /** Environment every process droidtop starts in a container gets, so a Wayland client reaches the primary compositor. */
    val clientEnvironment: Map<String, String> = mapOf(
        "XDG_RUNTIME_DIR" to SOCKET_DIR,
        "WAYLAND_DISPLAY" to WAYLAND_SOCKET_NAME,
    )

    /**
     * The compositor's environment. Everything here is wlroots' own, read
     * by any wlroots compositor (sway and labwc alike):
     *
     *  - `WLR_BACKENDS=headless`: outputs are virtual, which is what makes
     *    them capturable by wlr-screencopy (docs/SPEC.md 2). wlroots'
     *    backend/backend.c creates a session (seatd/logind) only for the
     *    drm and libinput backends, so a headless compositor needs no seat
     *    daemon at all.
     *  - `WLR_HEADLESS_OUTPUTS=1`: the headless backend starts with no
     *    output unless asked for one (same file, attempt_headless_backend);
     *    without it the only output would be sway's unadvertised FALLBACK.
     *  - `WLR_RENDERER=pixman`: software rendering into shared memory. A
     *    container on Android has no DRM render node to hand an EGL or
     *    Vulkan renderer, and wlr-screencopy's shm path is what
     *    `:host-bridge` reads.
     */
    val compositorEnvironment: Map<String, String> = mapOf(
        "XDG_RUNTIME_DIR" to SOCKET_DIR,
        "WLR_BACKENDS" to "headless",
        "WLR_HEADLESS_OUTPUTS" to "1",
        "WLR_RENDERER" to "pixman",
    )

    /**
     * The primary container's boot script: provision once (guarded by
     * [PROVISIONED_MARKER], so a failed or interrupted install runs again
     * next boot instead of being skipped), then replace itself with the
     * compositor. A failed install ends the script, which each backend
     * reports as the compositor never appearing.
     *
     * The install command is tested explicitly rather than left to
     * `set -e`: it is an `&&` chain, and `set -e` ignores a failure
     * anywhere in such a list but its last command, so a failed
     * `apt-get update` would otherwise fall through to writing the marker.
     */
    fun primaryInitScript(provisioning: PrimaryProvisioning): String = buildString {
        appendLine("#!/bin/sh")
        appendLine("set -e")
        appendLine("if [ ! -f $PROVISIONED_MARKER ]; then")
        appendLine("  echo 'droidtop: provisioning the desktop (first boot)'")
        appendLine("  if ! { ${provisioning.installCommand}; }; then")
        appendLine("    echo 'droidtop: provisioning failed' >&2")
        appendLine("    exit 1")
        appendLine("  fi")
        appendLine("  mkdir -p ${PROVISIONED_MARKER.substringBeforeLast('/')}")
        appendLine("  touch $PROVISIONED_MARKER")
        appendLine("  echo 'droidtop: provisioning finished'")
        appendLine("fi")
        appendLine("mkdir -p $SOCKET_DIR")
        appendLine("chmod 700 $SOCKET_DIR")
        compositorEnvironment.forEach { (key, value) -> appendLine("export $key=$value") }
        // The compositor creates the socket; a WAYLAND_DISPLAY inherited
        // from the client environment would only name the one it is about
        // to create.
        appendLine("unset WAYLAND_DISPLAY")
        appendLine("echo 'droidtop: starting ${provisioning.compositorCommand}'")
        appendLine("exec ${provisioning.compositorCommand}")
    }

    /**
     * [hostPath] (under [appStorageDir], the app's `getFilesDir()`) as seen
     * inside a container, where [appStorageDir] is mapped to
     * [APP_STORAGE_DIR]. Anything outside [appStorageDir] is a caller bug.
     */
    fun hostStorageToContainerPath(appStorageDir: File, hostPath: File): String {
        val relative = hostPath.absoluteFile.toRelativeString(appStorageDir.absoluteFile)
        require(!relative.startsWith("..")) { "$hostPath isn't under the app storage root $appStorageDir" }
        return if (relative.isEmpty()) APP_STORAGE_DIR else "$APP_STORAGE_DIR/$relative"
    }
}
