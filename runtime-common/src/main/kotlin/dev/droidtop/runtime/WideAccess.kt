package dev.droidtop.runtime

/**
 * Which folders are "wide" (docs/SPEC.md 5a, "What a game can reach", 4; Droidtop/tracker#584): a folder that, mapped
 * into a game or container, lets it see far more than its own files. The owner, 2026-10-17: "Let's gate the root and
 * internal storage maps behind a permission toggle". A wide folder is reachable only through the matching toggle,
 * never as an ordinary share, so "my games folder is /storage/emulated/0" asks for the toggle instead of slipping past
 * it. The one place this is decided. Pure.
 */
object WideAccess {
    enum class Kind {
        /** An ordinary folder: a games root, a save folder, the game's own folder. */
        NARROW,

        /** A storage volume's root or an ancestor of one: the "All internal storage" toggle. */
        ALL_STORAGE,

        /** `/`, or an ancestor of droidtop's private folder: the "Device system folders" toggle. */
        DEVICE_SYSTEM,
    }

    /**
     * What [folder] is, given the device's [volumeRoots] (`SharedVolume.mounted`, plus `/sdcard`) and droidtop's
     * [privateDir] (its data directory). Paths are compared as written, after removing trailing slashes; callers pass
     * canonical paths.
     */
    fun classify(folder: String, volumeRoots: Collection<String>, privateDir: String): Kind {
        val path = normalize(folder)
        if (path == "/" || isAncestorOrSelf(path, normalize(privateDir))) return Kind.DEVICE_SYSTEM
        val roots = volumeRoots.map(::normalize) + listOf("/sdcard", "/storage/emulated/0")
        if (roots.any { isAncestorOrSelf(path, it) } || EMULATED_ROOT.matches(path)) return Kind.ALL_STORAGE
        return Kind.NARROW
    }

    private val EMULATED_ROOT = Regex("^/storage/emulated(/[0-9]+)?$")

    private fun normalize(path: String): String = path.trimEnd('/').ifEmpty { "/" }

    /** Whether [ancestor] is [path] itself or one of its parent folders. */
    private fun isAncestorOrSelf(ancestor: String, path: String): Boolean =
        path == ancestor || path.startsWith(if (ancestor == "/") "/" else "$ancestor/")
}
