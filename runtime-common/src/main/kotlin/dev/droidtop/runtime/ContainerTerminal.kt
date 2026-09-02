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
 * The PRIMARY container only, today. Sibling containers share the primary's
 * Wayland socket, so a terminal launched in one would appear on the same
 * desktop — but siblings are not compositor-provisioned and so do not have
 * [PACKAGE] installed. Per-container terminal provisioning is the follow-up
 * that makes §3d's "a terminal into ANY container" true.
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
     * Runs a terminal in [container] and suspends until it exits — the
     * terminal is a foreground GUI process on the shared desktop, so
     * "finished" means the user closed the window.
     *
     * [container] must be running, and its compositor must be up; the
     * environment it needs (`WAYLAND_DISPLAY`, `XDG_RUNTIME_DIR`) is already
     * on every container droidtop creates, injected at container-config time
     * rather than per-exec.
     */
    suspend fun open(runtime: ContainerRuntime, container: Container): ContainerExecResult =
        runtime.exec(container, LAUNCH_COMMAND)

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
            return "No terminal in this container: '$PACKAGE' isn't installed. It is " +
                "provisioned on a primary container's first boot, so a container created " +
                "before that provisioning existed needs it installed by hand."
        }
        return "The terminal exited with code ${result.exitCode}" +
            if (detail.isEmpty()) "." else ": $detail"
    }
}
