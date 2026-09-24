package dev.droidtop.runtime

/**
 * The one rule for what an archive member's name may be, used by
 * [OciFlattener] on every layer and again by the in-process extractor that
 * writes its output: a path relative to the image root, or nothing.
 */
object TarPaths {
    /**
     * [name] as `a/b/c` relative to the image root, `""` for the root
     * itself, or null when it would leave the root. Leading `/` and `./`
     * are dropped; a `..` component anywhere rejects the name, whatever it
     * would resolve to, and so does a NUL.
     */
    fun relative(name: String): String? {
        if ('\u0000' in name) return null
        val components = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (components.any { it == ".." }) return null
        return components.joinToString("/")
    }

    /** Every proper ancestor of a [relative] path, outermost first: `a/b/c` gives `a`, `a/b`. */
    fun ancestors(path: String): List<String> {
        val result = mutableListOf<String>()
        var index = path.indexOf('/')
        while (index >= 0) {
            result += path.substring(0, index)
            index = path.indexOf('/', index + 1)
        }
        return result
    }

    /** The parent of a [relative] path, `""` at the top. */
    fun parent(path: String): String = path.substringBeforeLast('/', "")

    /** [name] under [parent], either of which may be the root `""`. */
    fun child(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"
}
