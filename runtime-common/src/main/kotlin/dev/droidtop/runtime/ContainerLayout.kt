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

    /**
     * The device VPN's endpoint, under [SOCKET_DIR]: a SOCKS5 proxy the
     * container's own VPN client serves on this Unix socket (wireproxy
     * for WireGuard, microsocks beside OpenVPN, a client's proxy mode).
     * droidtop relays every device connection to it (docs/SPEC.md 4a).
     */
    const val VPN_SOCKET = "vpn.sock"

    /**
     * The primary's CUPS, under [SOCKET_DIR] so every container prints
     * through it ([CompositorProvisioning.plan] with printing on; clients
     * find it through `CUPS_SERVER`, [clientEnvironment]).
     */
    const val CUPS_SOCKET = "cups.sock"

    /** Where the app's private storage root (`Context.getFilesDir()`) appears inside every container. */
    const val APP_STORAGE_DIR = "/run/droidtop-app-storage"

    /**
     * Where the device's shared storage appears inside every container,
     * one directory per mounted volume ([SharedVolume.name]), read-write,
     * the way distrobox shares the home directory (docs/SPEC.md 4b):
     * Downloads, documents and game folders are the same files inside and
     * outside the desktop.
     */
    const val SHARED_STORAGE_DIR = "/run/droidtop-shared-storage"

    /**
     * Written once the provisioning command has succeeded, holding which
     * plan it was ([planId]); provisioning runs again whenever the current
     * plan differs, so a package added to a plan reaches containers made
     * before it. Package managers skip what is already installed, so a
     * re-run costs only what changed.
     */
    const val PROVISIONED_MARKER = "/var/lib/droidtop-provisioned"

    /** A short stable identity for [provisioning]'s install command (hex SHA-256). */
    fun planId(provisioning: PrimaryProvisioning): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(provisioning.installCommand.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private val WAYLAND_SOCKET = Regex("wayland-(\\d+)")

    /**
     * The compositor's socket in the host side of [SOCKET_DIR], found
     * rather than assumed: every compositor names its own socket, and sway
     * deliberately never uses `wayland-0` (vendor/sway/sway/server.c,
     * "Avoid using wayland-0 as display socket", starting at `wayland-1`).
     * A hardcoded `wayland-0` was a socket that never appeared (found
     * running the primary boot off-device, 2026-09-24). The directory is
     * emptied before the primary starts, so the lowest-numbered socket is
     * the one compositor's. Null until it exists.
     */
    fun findWaylandSocket(hostSocketDir: File): File? =
        hostSocketDir.listFiles()
            ?.mapNotNull { file -> WAYLAND_SOCKET.matchEntire(file.name)?.let { it.groupValues[1].toInt() to file } }
            ?.minByOrNull { it.first }
            ?.second

    /**
     * Environment every process droidtop starts in a container gets, so a
     * Wayland client reaches the primary compositor: [SOCKET_DIR] as
     * `XDG_RUNTIME_DIR`, and the socket's name ([findWaylandSocket]) as
     * `WAYLAND_DISPLAY` once the compositor has created it, and CUPS's
     * shared socket as `CUPS_SERVER`.
     */
    fun clientEnvironment(waylandSocketName: String?): Map<String, String> = buildMap {
        put("XDG_RUNTIME_DIR", SOCKET_DIR)
        // CUPS clients take a socket path here. With printing off nothing
        // listens there, which to a program is the same as no CUPS.
        put("CUPS_SERVER", "$SOCKET_DIR/$CUPS_SOCKET")
        waylandSocketName?.let { put("WAYLAND_DISPLAY", it) }
    }

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
     * The primary container's boot script: provision when the marker does
     * not name this plan ([PROVISIONED_MARKER]; a failed or interrupted
     * install runs again next boot instead of being skipped), then replace
     * itself with the compositor. A failed install ends the script, which each backend
     * reports as the compositor never appearing.
     *
     * The install command is tested explicitly rather than left to
     * `set -e`: it is an `&&` chain, and `set -e` ignores a failure
     * anywhere in such a list but its last command, so a failed
     * `apt-get update` would otherwise fall through to writing the marker.
     */
    fun primaryInitScript(provisioning: PrimaryProvisioning): String = buildString {
        val plan = planId(provisioning)
        appendLine("#!/bin/sh")
        appendLine("set -e")
        appendLine("if [ \"$(cat $PROVISIONED_MARKER 2>/dev/null)\" != \"$plan\" ]; then")
        appendLine("  echo 'droidtop: provisioning the desktop (first boot)'")
        appendLine("  if ! { ${provisioning.installCommand}; }; then")
        appendLine("    echo 'droidtop: provisioning failed' >&2")
        appendLine("    exit 1")
        appendLine("  fi")
        appendLine("  mkdir -p ${PROVISIONED_MARKER.substringBeforeLast('/')}")
        appendLine("  echo $plan > $PROVISIONED_MARKER")
        appendLine("  echo 'droidtop: provisioning finished'")
        appendLine("fi")
        appendLine("mkdir -p $SOCKET_DIR")
        appendLine("chmod 700 $SOCKET_DIR")
        compositorEnvironment.forEach { (key, value) -> appendLine("export $key=$value") }
        // Each daemon backgrounds itself; one that fails to start is
        // reported and the desktop comes up without it.
        provisioning.daemons.forEach { daemon ->
            appendLine("$daemon || echo 'droidtop: $daemon did not start' >&2")
        }
        // The compositor creates the socket; a WAYLAND_DISPLAY inherited
        // from the client environment would only name the one it is about
        // to create.
        appendLine("unset WAYLAND_DISPLAY")
        appendLine("echo 'droidtop: starting ${provisioning.compositorCommand}'")
        appendLine("exec ${provisioning.compositorCommand}")
    }

    /** Host directory to in-container path, one per volume, for a backend's bind list. */
    fun sharedStorageBinds(volumes: List<SharedVolume>): List<Pair<String, String>> =
        volumes.map { it.root.absolutePath to "$SHARED_STORAGE_DIR/${it.name}" }

    /**
     * [hostPath] as seen inside a container through [sharedStorageBinds],
     * or null when it is on none of [volumes] (a file only a content
     * provider serves, or one in another app's private storage).
     */
    fun sharedStorageToContainerPath(volumes: List<SharedVolume>, hostPath: File): String? {
        val path = hostPath.absoluteFile
        for (volume in volumes) {
            val relative = path.toRelativeString(volume.root.absoluteFile)
            if (relative.startsWith("..") || File(relative).isAbsolute) continue
            return if (relative.isEmpty()) "$SHARED_STORAGE_DIR/${volume.name}" else "$SHARED_STORAGE_DIR/${volume.name}/$relative"
        }
        return null
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
