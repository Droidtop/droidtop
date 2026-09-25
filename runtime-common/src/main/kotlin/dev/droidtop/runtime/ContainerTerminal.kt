package dev.droidtop.runtime

/**
 * A real shell inside a container, reachable from desktop mode
 * (docs/SPEC.md §3d: "a computer the user can't open a shell on isn't a
 * computer").
 *
 * ### Why a terminal *in* the container rather than a terminal view in Android
 *
 * The two honest options were an Android-side terminal view driving
 * [ContainerRuntime.exec], and a real terminal application launched into the
 * compositor the desktop session is already running. This is the second, and
 * the argument is that the first is a much larger project pretending to be a
 * smaller one:
 *
 *  - [ContainerRuntime.exec] is run-to-completion — it returns a
 *    [ContainerExecResult] with captured stdout/stderr. An interactive shell
 *    needs a pty and a live stream, so the Android-side option starts by
 *    adding a pty/streaming primitive to [ContainerRuntime], which every
 *    backend then owes an implementation of — including the one that is
 *    still `TODO()`. That deepens the interface for a feature that does not
 *    need it.
 *  - Above that pty it still needs a VT parser and renderer, i.e. a terminal
 *    emulator, forked from Termux or Jackpal (§3d names both as candidates).
 *    A real terminal already exists in every distro's own package repository
 *    and is maintained by someone else.
 *  - droidtop already forked a keyboard *specifically* so that a terminal is
 *    drivable — Ctrl, Alt, Esc, Tab, arrows, function row (§6a). Those keys
 *    reach the container today through `:input-seat` → `:host-bridge`'s
 *    virtual keyboard. An Android-side terminal view would have to grow its
 *    own key handling and would not use that path at all.
 *  - It is also how the reference implementations work: distrobox (and
 *    BoxBuddy/DistroShelf over it, §7c) open a terminal by running one
 *    inside the container against the host's own display server.
 *
 * So this is one exec of one package, and everything a shell actually needs
 * — a pty, curses, colour, resize, scrollback — comes from a program written
 * to provide them.
 *
 * ### The limitation, stated plainly
 *
 * A terminal that lives in the compositor is unreachable when the compositor
 * is not running, which is exactly when a shell would be most useful for
 * debugging. That is a real cost of this choice. It is not paid for with a
 * second, non-interactive fallback path here, because two mechanisms for one
 * job is how a feature rots; a container that will not boot is the container
 * manager's problem (§3d), not the terminal's.
 *
 * ### Scope
 *
 * Any container. Siblings share the primary's Wayland socket, so a terminal
 * launched in one appears on the same desktop as a window of its own; the
 * primary gets [PACKAGE] with its compositor, and any other container gets
 * it the first time a terminal is asked for ([ENSURE_COMMAND], run from the
 * container manager's Terminal action before [open]).
 */
object ContainerTerminal {

    /**
     * foot: a Wayland-native terminal, packaged by both distros droidtop
     * provisions ([CompositorProvisioning]), small, and with no toolkit
     * dependency to drag in. The same constant names the package to install
     * and the binary to run, so the two cannot drift apart.
     */
    const val PACKAGE: String = "foot"

    /**
     * No `-e`: foot starts the user's own login shell (`$SHELL`, else the
     * passwd entry), which is what a terminal is expected to do and what
     * keeps this from hardcoding a shell the image may not have.
     */
    val LAUNCH_COMMAND: List<String> = listOf(PACKAGE)

    /**
     * Each package manager's command for [PACKAGE] and a font, by the
     * binary that identifies it. Declared before [ENSURE_COMMAND], which is
     * built from it when the object initialises.
     */
    private val TERMINAL_INSTALLS: Map<String, String> = linkedMapOf(
        "apk" to "apk add --no-cache $PACKAGE font-dejavu",
        "apt-get" to "export DEBIAN_FRONTEND=noninteractive && apt-get update && " +
            "apt-get install -y --no-install-recommends $PACKAGE fonts-dejavu-core",
        "dnf" to "dnf install -y $PACKAGE dejavu-sans-mono-fonts",
        "zypper" to "zypper --non-interactive install $PACKAGE dejavu-fonts",
        "pacman" to "pacman -Sy --noconfirm $PACKAGE ttf-dejavu",
        "xbps-install" to "xbps-install -Sy $PACKAGE dejavu-fonts-ttf",
    )

    /**
     * Installs [PACKAGE] and a font into a container that has no terminal
     * yet, through whichever package manager the image ships, and does
     * nothing when [PACKAGE] is already there. The package manager is
     * found in the container rather than inferred from the image's name,
     * so a Custom reference gets a terminal too. A font is named for the
     * same reason as in [CompositorProvisioning]: without one foot refuses
     * to start ("failed to match font"). Exits 2, saying so, when the image
     * has none of the package managers listed.
     */
    val ENSURE_COMMAND: List<String> = listOf(
        "/bin/sh",
        "-c",
        buildString {
            append("command -v $PACKAGE >/dev/null 2>&1 && exit 0; ")
            TERMINAL_INSTALLS.entries.forEachIndexed { index, (manager, install) ->
                append(if (index == 0) "if" else "elif")
                append(" command -v $manager >/dev/null 2>&1; then $install; ")
            }
            append("else echo 'No package manager droidtop knows (")
            append(TERMINAL_INSTALLS.keys.joinToString(", "))
            append(") is in this container, so a terminal cannot be installed in it.' >&2; exit 2; fi")
        },
    )

    /**
     * Makes sure [container] has a terminal ([ENSURE_COMMAND]); null when it
     * has one, else why not, with the package manager's last words.
     */
    suspend fun ensureInstalled(runtime: ContainerRuntime, container: Container): String? {
        val result = runtime.exec(container, ENSURE_COMMAND)
        if (result.succeeded) return null
        val detail = result.stderr.ifBlank { result.stdout }.trim().lines().takeLast(6).joinToString("\n")
        return "Couldn't install a terminal in ${container.id} (code ${result.exitCode})" +
            if (detail.isEmpty()) "." else ":\n$detail"
    }

    /**
     * Runs a terminal in [container] and suspends until it exits — the
     * terminal is a foreground GUI process on the shared desktop, so
     * "finished" means the user closed the window.
     *
     * [container] must be running, and its compositor must be up; the
     * environment it needs (`WAYLAND_DISPLAY`, `XDG_RUNTIME_DIR`) is already
     * on every container droidtop creates, injected at container-config time
     * rather than per-exec.
     */
    suspend fun open(runtime: ContainerRuntime, container: Container, title: String? = null): ContainerExecResult =
        runtime.exec(container, launchCommand(title))

    /**
     * [LAUNCH_COMMAND], with the window titled [title] (the container's
     * name) so two terminals on one desktop say which container each is
     * in. foot's `--title` is only the initial title; neither Alpine's
     * shell nor Debian's bashrc (which retitles only `xterm*` and `rxvt*`
     * terminals) replaces it.
     */
    fun launchCommand(title: String?): List<String> =
        LAUNCH_COMMAND + listOfNotNull(title?.let { "--title=$it" })

    /**
     * A human-readable failure for [result], or null if the terminal ran and
     * exited normally. Separate from [open] so the message is testable and
     * so the caller does not have to guess at what a non-zero exit means.
     */
    fun failureMessage(result: ContainerExecResult): String? {
        if (result.succeeded) return null
        val detail = result.stderr.ifBlank { result.stdout }.trim()
        if (detail.contains("not found", ignoreCase = true) ||
            detail.contains("No such file", ignoreCase = true)
        ) {
            return "No terminal in this container: '$PACKAGE' isn't installed. The primary " +
                "gets it when the desktop is provisioned; for any container, Containers > " +
                "Terminal installs it."
        }
        return "The terminal exited with code ${result.exitCode}" +
            if (detail.isEmpty()) "." else ": $detail"
    }
}
