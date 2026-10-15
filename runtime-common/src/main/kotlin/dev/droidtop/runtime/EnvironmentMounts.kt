package dev.droidtop.runtime

import java.io.File

/**
 * Which home a container's programs see at `/root` (docs/SPEC.md 3d "Home and folders", Droidtop/tracker#555 D3).
 * Distrobox's model: by default a container shares the person's home; `--home` gives it one of its own.
 */
sealed interface HomeChoice {
    /** droidtop's one shared home folder ([ContainerLayout.sharedHomeDir]), the same in every container that shares it. */
    data object Shared : HomeChoice

    /** The image's own `/root`, inside the container's tree, seen by no other container. */
    data object Private : HomeChoice

    /** A folder on this device the person picked, as an absolute host path. */
    data class Chosen(val hostPath: String) : HomeChoice
}

/**
 * The one description of what an environment sees of the device: its home and the extra folders shared into it
 * (docs/SPEC.md 3d "Home and folders"). Both container backends read it through [binds], so proot's `--bind`
 * arguments and droidspaces' `bind_mounts` line come from the same list.
 */
data class EnvironmentMounts(
    val home: HomeChoice = HomeChoice.Private,
    val folders: List<ExtraMount> = emptyList(),
) {
    /**
     * Host-to-guest binds, home first. A folder that no longer exists on the host is left out (a card taken out,
     * a folder deleted), the same "don't fail a start over one missing bind" rule shared storage follows; the
     * shared home is made when it is first needed.
     */
    fun binds(sharedHome: File, isDirectory: (String) -> Boolean = { File(it).isDirectory }): List<Pair<String, String>> = buildList {
        when (val h = home) {
            HomeChoice.Shared -> {
                sharedHome.mkdirs()
                add(sharedHome.absolutePath to HOME_GUEST_PATH)
            }
            HomeChoice.Private -> Unit
            is HomeChoice.Chosen -> if (isDirectory(h.hostPath)) add(h.hostPath to HOME_GUEST_PATH)
        }
        folders.filter { isDirectory(it.hostPath) }.forEach { add(it.hostPath to it.containerPath) }
    }

    /**
     * The same folders as a Wine prefix sees them: one drive letter each, in order from D (docs/SPEC.md 3d "Home and
     * folders"). A path with a colon is left out rather than encoded, because the container's drive string finds each
     * letter by the character before a `:` and one such path would corrupt every drive after it. Letters stop at Y:
     * C: is the prefix and Z: is gamenative's own link to the image root (WineUtils.createDosdevicesSymlinks).
     */
    fun wineDrives(): List<Pair<Char, String>> =
        folders.map { it.hostPath }.filterNot { it.contains(':') }.distinct()
            .zip(WINE_DRIVE_LETTERS.toList())
            .map { (path, letter) -> letter to path }

    companion object {
        /** The guest's home: every container runs as root, so HOME is `/root` (ProotRuntime's guest environment). */
        const val HOME_GUEST_PATH = "/root"

        /** The letters a prefix's folders are reached at: after C: (the prefix), before Z: (the image root). */
        val WINE_DRIVE_LETTERS: CharRange = 'D'..'Y'

        /** An environment that sees [paths] as its extra folders, each named by its last segment (a Wine prefix's games roots). */
        fun ofFolders(paths: List<String>): EnvironmentMounts =
            EnvironmentMounts(folders = paths.map { ExtraMount(it, File(it).name.ifBlank { "folder" }) })

        /** What a new container starts with: the shared home, as distrobox does. */
        val NEW_CONTAINER = EnvironmentMounts(home = HomeChoice.Shared)

        private const val HOME_KEY = "@home"

        /**
         * Reads the mounts file both backends keep per container. Folder lines are `hostPath<TAB>name` (the format
         * the Mounts row always wrote); an `@home<TAB>shared|private|chosen[<TAB>path]` line names the home. A file
         * without one, every container made before homes existed, keeps its own `/root`, so nothing it holds is
         * hidden behind a shared home.
         */
        fun decode(text: String): EnvironmentMounts {
            var home: HomeChoice = HomeChoice.Private
            val folders = mutableListOf<ExtraMount>()
            text.lineSequence().forEach { line ->
                val parts = line.split("\t")
                if (parts.firstOrNull() == HOME_KEY) {
                    home = when (parts.getOrNull(1)) {
                        "shared" -> HomeChoice.Shared
                        "chosen" -> parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { HomeChoice.Chosen(it) } ?: HomeChoice.Private
                        else -> HomeChoice.Private
                    }
                } else {
                    val folder = line.split("\t", limit = 2)
                    if (folder.size == 2 && folder[0].isNotBlank() && folder[1].isNotBlank()) folders += ExtraMount(folder[0], folder[1])
                }
            }
            return EnvironmentMounts(home, folders)
        }

        fun encode(mounts: EnvironmentMounts): String = buildString {
            when (val h = mounts.home) {
                HomeChoice.Shared -> append("$HOME_KEY\tshared\n")
                HomeChoice.Private -> append("$HOME_KEY\tprivate\n")
                is HomeChoice.Chosen -> append("$HOME_KEY\tchosen\t${h.hostPath}\n")
            }
            mounts.folders.forEach { append("${it.hostPath}\t${it.name}\n") }
        }
    }
}
