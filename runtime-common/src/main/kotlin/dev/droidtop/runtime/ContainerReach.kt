package dev.droidtop.runtime

/**
 * What a container can reach of the device, in plain words (docs/SPEC.md 3d, "What it can reach";
 * Droidtop/tracker#787 slice 1): one line per kind of access, read from the same settings the container page's rows
 * write, so the summary cannot say anything the rows do not. Pure.
 */
object ContainerReach {
    /** One kind of access: what it is ("Files") and what this container has of it. */
    data class Line(val kind: String, val what: String)

    /** The settings the summary reads; each mirrors a row on the container page. */
    data class Settings(
        val mounts: EnvironmentMounts,
        val sockets: ContainerSockets,
        /** Whether the device microphone is shared (the desktop's one microphone switch, with the permission). */
        val microphone: Boolean,
        /** How many USB devices are shared with it. */
        val devices: Int,
        /** Whether it carries droidtop's VPN for Android apps. */
        val servesVpn: Boolean,
        /** Whether it has the device's network at all (slice 3). */
        val network: Boolean = true,
    )

    fun lines(s: Settings): List<Line> = listOf(
        Line("Files", files(s.mounts)),
        Line("Screen", if (s.sockets.waylandShared) "Shows its windows on the desktop" else "No windows on the desktop"),
        Line(
            "Clipboard",
            when {
                !s.sockets.waylandShared -> "None"
                s.sockets.clipboardShared -> "Reads and sets the clipboard"
                else -> "None: its windows can't copy or paste"
            },
        ),
        Line(
            "Sound",
            when {
                !s.sockets.audioShared -> "No sound"
                s.microphone -> "Plays sound and hears the microphone"
                else -> "Plays sound; no microphone"
            },
        ),
        Line(
            "USB devices",
            when (s.devices) {
                0 -> "None"
                1 -> "1 device"
                else -> "${s.devices} devices"
            },
        ),
        Line(
            "Network",
            if (!s.network) {
                "None at all"
            } else {
                "This device's network, as any app has" + if (s.servesVpn) "; it carries the VPN for the apps chosen" else ""
            },
        ),
    )

    /** Its home, the device's shared storage (always there), and each extra folder by name. */
    private fun files(mounts: EnvironmentMounts): String {
        val home = when (val h = mounts.home) {
            HomeChoice.Private -> "its own home"
            HomeChoice.Shared -> "the shared home folder"
            is HomeChoice.Chosen -> "home in ${h.hostPath.trimEnd('/').substringAfterLast('/').ifEmpty { h.hostPath }}"
        }
        val folders = mounts.folders.map { it.name }
        val extra = when (folders.size) {
            0 -> ""
            1 -> ", and ${folders[0]}"
            else -> ", and ${folders.size} folders (${folders.joinToString(", ")})"
        }
        return "${home.replaceFirstChar { it.uppercase() }}, the device's shared storage$extra"
    }
}
