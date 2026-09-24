package dev.droidtop.runtime

/**
 * The kinds of file "Open with droidtop" takes (docs/SPEC.md 4b), and
 * what runs each one: Windows programs in a Wine prefix, Linux packages
 * and AppImages in a container. Pure, so the choices and the commands are
 * tested without a device.
 */
enum class OpenWithKind(val extension: String, val runsInWine: Boolean) {
    EXE("exe", true),
    MSI("msi", true),
    DEB("deb", false),
    RPM("rpm", false),
    APPIMAGE("AppImage", false),
    ;

    companion object {
        /** By file name, ignoring case; null for anything else. */
        fun of(fileName: String): OpenWithKind? {
            val ext = fileName.substringAfterLast('.', "")
            return entries.firstOrNull { it.extension.equals(ext, ignoreCase = true) }
        }
    }
}

/** The package managers whose install command takes a local package file. */
enum class ContainerPackageManager(val binary: String, val installs: OpenWithKind) {
    APT("apt-get", OpenWithKind.DEB),
    DNF("dnf", OpenWithKind.RPM),
    ZYPPER("zypper", OpenWithKind.RPM),
}

object ContainerOpenWith {
    /**
     * Run in a container, prints the first package manager it has from
     * [ContainerPackageManager] and exits 0, or exits 1 with none.
     */
    val PROBE_COMMAND: List<String> = listOf(
        "/bin/sh",
        "-c",
        "for m in ${ContainerPackageManager.entries.joinToString(" ") { it.binary }}; do " +
            "command -v \$m >/dev/null 2>&1 && { echo \$m; exit 0; }; done; exit 1",
    )

    /** What [PROBE_COMMAND] printed, as a manager. */
    fun packageManagerFrom(result: ContainerExecResult): ContainerPackageManager? {
        if (!result.succeeded) return null
        val printed = result.stdout.trim().lines().lastOrNull()?.trim()
        return ContainerPackageManager.entries.firstOrNull { it.binary == printed }
    }

    /**
     * Whether a container with [manager] (null: none of ours) can take
     * [kind]. An AppImage needs no package manager.
     */
    fun accepts(kind: OpenWithKind, manager: ContainerPackageManager?): Boolean = when (kind) {
        OpenWithKind.APPIMAGE -> true
        OpenWithKind.DEB, OpenWithKind.RPM -> manager?.installs == kind
        OpenWithKind.EXE, OpenWithKind.MSI -> false
    }

    /**
     * The command that installs or runs [containerPath] (the file as a
     * container sees it, [ContainerLayout.sharedStorageToContainerPath]),
     * from where it is. Installs answer their own prompts, because the
     * output is shown only once the install has finished.
     */
    fun command(kind: OpenWithKind, manager: ContainerPackageManager?, containerPath: String): List<String> = when (kind) {
        OpenWithKind.DEB -> listOf("apt-get", "install", "-y", containerPath)
        OpenWithKind.RPM -> when (manager) {
            ContainerPackageManager.ZYPPER -> listOf("zypper", "--non-interactive", "install", containerPath)
            else -> listOf("dnf", "install", "-y", containerPath)
        }
        OpenWithKind.APPIMAGE -> listOf(containerPath)
        OpenWithKind.EXE, OpenWithKind.MSI -> error("${kind.extension} runs in Wine, not in a container")
    }

    /**
     * The environment [command] runs with. An AppImage normally mounts
     * itself with FUSE, which no container droidtop runs has; its own
     * runtime's documented fallback is extracting to a temporary directory
     * and running from there.
     */
    fun environment(kind: OpenWithKind): Map<String, String> = when (kind) {
        OpenWithKind.DEB -> mapOf("DEBIAN_FRONTEND" to "noninteractive")
        OpenWithKind.APPIMAGE -> mapOf("APPIMAGE_EXTRACT_AND_RUN" to "1")
        else -> emptyMap()
    }
}
