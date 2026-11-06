package dev.droidtop.runtime

import java.util.zip.CRC32

/**
 * X11 programs in a sibling container (docs/SPEC.md 3d "Clipboard per container", the Xwayland bullet;
 * Droidtop/tracker#910). Each sibling runs an X server of its own, xwayland-satellite: rootless Xwayland with
 * satellite as its window manager, so every X window is an ordinary xdg toplevel on the desktop. It runs inside the
 * sibling as a client of the sibling's own `WAYLAND_DISPLAY`, which with the clipboard off is the filtering socket
 * ([ClipboardFilters]), so X selections reach no further than that container's Wayland clipboard does. The primary's
 * X socket is never handed to a sibling: X selections there are the desktop's own clipboard.
 *
 * The package is installed on request with the container's own package manager, found as "Update its software"
 * finds it ([PackageUpgrade.FIND_SCRIPT]). The server starts lazily, the first time droidtop runs a program in the
 * sibling while the desktop is up and the package is there; each backend keeps it running in its own way (proot as a
 * session of its own, droidspaces detached inside the container) from the same [serverScript]. Everything here is
 * pure.
 */
object SiblingX11 {
    /** The package, and the program it installs. */
    const val PACKAGE: String = "xwayland-satellite"

    /** Where a backend that can look into the tree from outside (proot) finds [PACKAGE] installed. */
    val BINARY_PATHS: List<String> = listOf("usr/bin/$PACKAGE", "usr/local/bin/$PACKAGE")

    /**
     * Set in an exec's environment (any value) by a caller that only asks something of the container, such as the
     * container page's [STATUS_SCRIPT], so that asking does not start an X server.
     */
    const val NO_SERVER_ENV: String = "DROIDTOP_NO_X11"

    /** The lowest display number a sibling's server takes, clear of the primary's own (sway's Xwayland starts at :0). */
    const val FIRST_DISPLAY: Int = 100

    /** How many display numbers siblings share, from [FIRST_DISPLAY]. */
    const val DISPLAYS: Int = 900

    /** How long a backend waits for a server it started to make its socket. */
    const val READY_TIMEOUT_MS: Long = 10_000

    /**
     * The Xwayland package beside [PACKAGE], by package manager: satellite runs the `Xwayland` binary, and Alpine's
     * package does not depend on it (community/xwayland-satellite/APKBUILD has no `depends`).
     */
    private val XWAYLAND: Map<String, String> = mapOf(
        "apk" to "xwayland",
        "apt-get" to "xwayland",
        "dnf" to "xorg-x11-server-Xwayland",
        "pacman" to "xorg-xwayland",
        "zypper" to "xwayland",
    )

    /**
     * [containerId]'s display number, stable while the set of containers stays the same. Abstract Unix sockets are
     * shared by every process in a network namespace, and every proot container (and a droidspaces container with
     * the network on) is in the device's, so two siblings on one number would reach the same server. Each id hashes
     * into [FIRST_DISPLAY]..[FIRST_DISPLAY]+[DISPLAYS]-1, and a number already taken by an id sorting earlier moves to
     * the next free one, so no two of [containerIds] share a number.
     */
    fun displayNumber(containerId: String, containerIds: Collection<String>): Int {
        val taken = HashSet<Int>()
        for (id in (containerIds + containerId).toSortedSet()) {
            var slot = (crc(id) % DISPLAYS).toInt()
            while (FIRST_DISPLAY + slot in taken) slot = (slot + 1) % DISPLAYS
            val number = FIRST_DISPLAY + slot
            if (id == containerId) return number
            taken += number
        }
        error("unreachable: $containerId is in the set")
    }

    private fun crc(id: String): Long = CRC32().apply { update(id.toByteArray(Charsets.UTF_8)) }.value

    /** The display's socket inside the container. */
    fun socketPath(display: Int): String = "/tmp/.X11-unix/X$display"

    /** What a program in the sibling is given once its server is running or startable. */
    fun environment(display: Int): Map<String, String> = mapOf("DISPLAY" to ":$display")

    /**
     * Runs the server for [display] in the foreground, or exits 0 at once when it is already running or starting
     * ([runningTest]). It needs `WAYLAND_DISPLAY` and `XDG_RUNTIME_DIR` from the
     * client environment ([ContainerLayout.clientEnvironment]).
     *
     *  - satellite makes a Wayland socket of its own for Xwayland (`xwls-N`) in `XDG_RUNTIME_DIR`. That is the
     *    shared [ContainerLayout.SOCKET_DIR], which every container sees, and a socket there would let another
     *    container in through this one's clipboard. So satellite gets a private runtime folder in the sibling's own
     *    `/tmp`, and the compositor's socket as a full path.
     *  - `-nolisten local`: no abstract socket, which any process in the same network namespace could reach. The X
     *    socket is only the file in the sibling's own `/tmp`.
     *  - A stale lock or socket from a server that is gone is removed first; Xwayland refuses a display whose lock
     *    names a live process, and under proot that pid may be anyone's.
     *  - It says which it did on one line starting [STATE_LINE] (`running` or `starting`), after the stale socket is
     *    gone, so a backend watching its output waits for the new socket only then.
     */
    fun serverScript(display: Int): String = buildString {
        val runtimeDir = "/tmp/.droidtop-x11-$display"
        append("if ${runningTest(display)}; then echo '$STATE_LINE running'; exit 0; fi; ")
        append("${cleanup(display)}; ")
        append("mkdir -p /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix; ")
        append("mkdir -p $runtimeDir && chmod 700 $runtimeDir; ")
        append("case \"\$WAYLAND_DISPLAY\" in /*) ;; *) WAYLAND_DISPLAY=\"\$XDG_RUNTIME_DIR/\$WAYLAND_DISPLAY\" ;; esac; ")
        append("export WAYLAND_DISPLAY XDG_RUNTIME_DIR=$runtimeDir; ")
        append("echo \$\$ > ${pidFile(display)}; ")
        append("echo '$STATE_LINE starting'; ")
        append("exec $PACKAGE :$display -nolisten local")
    }

    /** What [serverScript]'s line about itself starts with. */
    const val STATE_LINE: String = "droidtop-x11:"

    private fun pidFile(display: Int) = "/tmp/.droidtop-x11-$display.pid"

    /**
     * True in `sh` when [display]'s server is running or on its way: its pid file names a process whose command line
     * is satellite's, or [serverScript] about to become it (proot keeps a program's own argv, so this holds there).
     */
    private fun runningTest(display: Int): String =
        "{ pid=\$(cat ${pidFile(display)} 2>/dev/null); [ -n \"\$pid\" ] && tr '\\0' ' ' </proc/\$pid/cmdline 2>/dev/null | grep -q $PACKAGE; }"

    private fun cleanup(display: Int): String = "rm -f /tmp/.X$display-lock ${socketPath(display)}"

    /**
     * For a backend whose exec leaves a detached process running (droidspaces): the text put before the program's own
     * command. When [PACKAGE] is installed and its server is not running, it removes what a server that is gone left
     * and starts [serverScript] detached, with [assignments] (the exec's environment as `K='v'` words) exported for
     * it; then it waits up to [READY_TIMEOUT_MS] for the socket, and gives the program `DISPLAY` when the socket is
     * there. Without the package it does nothing, so the program gets no `DISPLAY`.
     */
    fun detachedPrefix(display: Int, assignments: String): String = buildString {
        val socket = socketPath(display)
        val tries = READY_TIMEOUT_MS / 100
        append("if command -v $PACKAGE >/dev/null 2>&1; then ( ")
        if (assignments.isNotEmpty()) append("export $assignments; ")
        append("if ! ${runningTest(display)}; then ${cleanup(display)}; ")
        append("if command -v setsid >/dev/null 2>&1; then s=setsid; else s=; fi; ")
        append("\$s /bin/sh -c ${shellQuote(serverScript(display))} >/tmp/.droidtop-x11-$display.log 2>&1 </dev/null & fi; ")
        append("i=0; while [ ! -S $socket ] && [ \$i -lt $tries ]; do sleep 0.1; i=\$((i+1)); done ")
        append(") >/dev/null 2>&1 </dev/null; ")
        append("if [ -S $socket ]; then export DISPLAY=:$display; fi; fi; ")
    }

    /** One word for `sh`, whatever [value] holds. */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** What the container page needs to know: `ready` when [PACKAGE] is installed, else its package manager's id. */
    val STATUS_SCRIPT: String = "command -v $PACKAGE >/dev/null 2>&1 && { echo ready; exit 0; }; ${PackageUpgrade.FIND_SCRIPT}"

    /** Where a sibling stands for X11 programs. */
    sealed interface Status {
        object Ready : Status
        data class Installable(val manager: PackageUpgrade.Manager) : Status
        /** No package manager droidtop knows, so nothing can be installed. */
        object NoManager : Status
    }

    /** [STATUS_SCRIPT]'s answer. */
    fun status(stdout: String): Status {
        val word = stdout.trim().lines().lastOrNull()?.trim().orEmpty()
        if (word == "ready") return Status.Ready
        return PackageUpgrade.byId(word)?.let { Status.Installable(it) } ?: Status.NoManager
    }

    /**
     * The steps that install [PACKAGE] and Xwayland with [manager], each as root: the package list refreshed, then
     * the install, without recommended or weak extras. pacman refreshes in its install step, as the terminal's
     * install does ([ContainerTerminal]).
     */
    fun installSteps(manager: PackageUpgrade.Manager): List<PackageUpgrade.Step> {
        val packages = listOf(PACKAGE, XWAYLAND.getValue(manager.id))
        val refresh = PackageUpgrade.Step("Refreshing the package list", emptyList())
        fun install(command: List<String>, env: Map<String, String> = emptyMap()) =
            PackageUpgrade.Step("Installing the X server", command + packages, env)
        return when (manager.id) {
            "apk" -> listOf(refresh.copy(command = listOf("apk", "update")), install(listOf("apk", "add")))
            "apt-get" -> {
                val env = mapOf("DEBIAN_FRONTEND" to "noninteractive")
                listOf(
                    refresh.copy(command = listOf("apt-get", "update"), env = env),
                    install(listOf("apt-get", "install", "-y", "--no-install-recommends"), env),
                )
            }
            "dnf" -> listOf(install(listOf("dnf", "install", "-y", "--setopt=install_weak_deps=False")))
            "pacman" -> listOf(install(listOf("pacman", "-Sy", "--needed", "--noconfirm")))
            "zypper" -> listOf(
                refresh.copy(command = listOf("zypper", "--non-interactive", "refresh")),
                install(listOf("zypper", "--non-interactive", "install", "--no-recommends")),
            )
            else -> error("no install for ${manager.id}")
        }
    }

    /** Whether a failed install's [output] says the distro's repositories have no such package. */
    fun notPackaged(manager: PackageUpgrade.Manager, output: String): Boolean {
        val words = when (manager.id) {
            "apk" -> listOf("unable to select packages", "no such package")
            "apt-get" -> listOf("Unable to locate package", "has no installation candidate")
            "dnf" -> listOf("No match for argument", "Unable to find a match")
            "pacman" -> listOf("target not found")
            "zypper" -> listOf("not found in package names", "No provider of")
            else -> emptyList()
        }
        return words.any { output.contains(it, ignoreCase = true) }
    }
}
