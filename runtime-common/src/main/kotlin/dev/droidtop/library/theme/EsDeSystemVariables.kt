package dev.droidtop.library.theme

/**
 * The `${system.*}` variables a theme sees, exactly as ES-DE builds them
 * in `SystemData::loadTheme` (SystemData.cpp:1959-2032).
 *
 * Three names -- `system.name`, `system.fullName`, `system.theme` -- each
 * exist a second time with one of three mutually exclusive suffixes:
 * `.autoCollections`, `.customCollections` and `.noCollections`. Exactly
 * one of the three applies to any given system, and ES-DE assigns the
 * other two a single backspace character (SystemData.cpp:1985-2032) as a
 * flag. A property whose resolved text is that backspace is then skipped
 * outright (ThemeData.cpp:2249-2256), which is how a theme writes one
 * element that shows a system name only when the system is NOT a
 * collection -- modern-es-de's `${system.fullName.noCollections}` -- or
 * only when it is a custom one. Eight of the fifteen themes collected for
 * this pass use the mechanism.
 *
 * droidtop has no separate short-internal-name / display-name split, so
 * `system.name` and `system.fullName` resolve to the same string, as they
 * already did before the suffixes existed.
 */
object EsDeSystemVariables {
    /** ES-DE's own flag for a variable that does not apply to this system. */
    const val NOT_APPLICABLE = "\b"

    /**
     * The suffixed variable names, in ES-DE's own order.
     */
    private val SUFFIXES = listOf("autoCollections", "customCollections", "noCollections")

    fun forSystem(
        fullName: String?,
        themeFolder: String?,
        kind: EsDeCollectionKind,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (themeFolder != null) out["system.theme"] = themeFolder
        if (fullName != null) {
            out["system.name"] = fullName
            out["system.fullName"] = fullName
        }
        val applicable = when (kind) {
            EsDeCollectionKind.CUSTOM -> "customCollections"
            EsDeCollectionKind.AUTO -> "autoCollections"
            EsDeCollectionKind.NONE -> "noCollections"
        }
        for (suffix in SUFFIXES) {
            val applies = suffix == applicable
            out["system.name." + suffix] =
                if (applies) fullName ?: NOT_APPLICABLE else NOT_APPLICABLE
            out["system.fullName." + suffix] =
                if (applies) fullName ?: NOT_APPLICABLE else NOT_APPLICABLE
            out["system.theme." + suffix] =
                if (applies) themeFolder ?: NOT_APPLICABLE else NOT_APPLICABLE
        }
        return out
    }
}
