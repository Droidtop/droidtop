package dev.droidtop.runtime

/**
 * A real shell inside a container (docs/SPEC.md §3d: "a computer the user can't open a shell on isn't a computer"),
 * two ways.
 *
 * ### On the desktop: foot in the container
 *
 * With the desktop up, Settings > Containers > Terminal runs a real terminal application ([PACKAGE]) inside the
 * container, through [ContainerRuntime.exec], as a window on the shared desktop. Everything a shell needs (a pty,
 * curses, colour, resize, scrollback) comes from a program written to provide them, the keys reach it through
 * `:input-seat` and `:host-bridge`'s virtual keyboard, and it is what distrobox (and BoxBuddy/DistroShelf over it,
 * §7c) does. Siblings share the primary's Wayland socket, so a terminal launched in one appears on the same desktop
 * as a window of its own; the primary gets [PACKAGE] with its compositor, and any other container gets it the first
 * time a terminal is asked for ([ENSURE_COMMAND], run from the container manager's Terminal action before [open]).
 *
 * ### On Android: droidtop's own terminal (Droidtop/tracker#909)
 *
 * A terminal that lives in the compositor is unreachable when the compositor is not running, which is when a shell
 * would help most. So droidtop also draws a terminal itself, on Android, desktop up or not: Termux's
 * terminal-emulator and terminal-view (vendor/termux-app) over a real pseudo-terminal, running the backend's
 * own enter command ([ContainerRuntime.interactiveShell], [InteractiveShell]) with [LOGIN_SHELL_COMMAND] and
 * [SHELL_ENVIRONMENT]. The pieces that made this "a larger project pretending to be a smaller one" when foot was
 * chosen are what Termux already wrote: the VT parser, the renderer and the pty. [ContainerRuntime.exec] stays
 * run-to-completion; the interactive path is a separate method that returns how to start the shell rather than
 * running it, so no backend owes a streaming primitive.
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
     * What droidtop's own terminal runs in a container (Droidtop/tracker#909): the login shell of the user it runs as,
     * from the container's /etc/passwd (`/bin/sh` when that names none or nothing executable), started as a login
     * shell (`-l`, which every common shell takes) in that user's home. Read in the container, so a Custom image gets
     * its own shell; nothing here is shell text from outside.
     */
    val LOGIN_SHELL_COMMAND: List<String> = listOf("/bin/sh", "-c", loginScript())

    private fun loginScript(): String =
        "u=\$(id -un 2>/dev/null || echo root); s=; h=; " +
            "while IFS=: read -r n _ _ _ _ d sh; do if [ \"\$n\" = \"\$u\" ]; then s=\$sh; h=\$d; break; fi; done < /etc/passwd; " +
            "{ [ -n \"\$s\" ] && [ -x \"\$s\" ]; } || s=/bin/sh; " +
            "{ [ -n \"\$h\" ] && [ -d \"\$h\" ]; } && export HOME=\"\$h\"; " +
            "cd \"\${HOME:-/}\" 2>/dev/null || cd /; export SHELL=\"\$s\"; exec \"\$s\" -l"

    /**
     * What droidtop's own terminal adds to the environment the backend gives a command: the terminal it emulates
     * (xterm with 256 colours, which Termux's emulator answers to) and that it draws 24-bit colour.
     */
    val SHELL_ENVIRONMENT: Map<String, String> = linkedMapOf("TERM" to "xterm-256color", "COLORTERM" to "truecolor")

    /**
     * [LOGIN_SHELL_COMMAND] on a terminal made inside the container, for a backend whose command reaches the
     * container over pipes ([InteractiveShell.Elevated]): `script(1)` (util-linux's or busybox's) makes the pty and
     * runs the shell on it, sized [rows] by [columns] with `stty` first. A container without `script` gets the shell
     * on the pipes, saying so: it runs commands, but an editor or `top` cannot draw.
     */
    fun onContainerTerminal(rows: Int, columns: Int): List<String> {
        val inner = "stty rows $rows cols $columns 2>/dev/null; " + LOGIN_SHELL_COMMAND[2]
        return listOf(
            "/bin/sh",
            "-c",
            "if command -v script >/dev/null 2>&1; then exec script -qfc \"\$1\" /dev/null; fi; " +
                "echo 'This container has no script program, so the shell has no terminal: editors and top will not " +
                "draw. Installing util-linux adds one.' >&2; exec /bin/sh -c \"\$1\"",
            "sh",
            inner,
        )
    }

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
