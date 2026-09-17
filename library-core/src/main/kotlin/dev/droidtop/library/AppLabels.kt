package dev.droidtop.library

/**
 * What an installed Android app is CALLED in the library, when the system
 * will not say.
 *
 * The rig's Apps tab listed `com.bluestacks.bsxlauncher.Main`: an
 * activity whose label resolves to its own class name, which is what an
 * app that declares no `android:label` on its launcher activity gives
 * back. A fully-qualified class name is not a name a person recognises,
 * and it is also the longest possible string to put under a tile.
 *
 * The order (research/ui-polish item 17) is: the application's own label,
 * then the package humanised, then the package itself -- never nothing,
 * because an app with no name is still an app and hiding it would be
 * worse than naming it badly.
 *
 * Pure, so the rule is unit-tested rather than only visible on a device.
 */
object AppLabels {

    /**
     * Package segments that name a platform or a layer rather than an
     * app, so the segment before them is the one that means something:
     * `com.example.coolGame.android` is "Cool Game", not "Android".
     */
    private val GENERIC_SEGMENTS = setOf(
        "android", "app", "apps", "application", "client", "main", "mobile",
        "ui", "launcher", "free", "pro", "lite", "beta", "release", "demo",
    )

    /**
     * [label] if it names the app, else the package humanised, else
     * [packageName].
     */
    fun labelFor(label: CharSequence?, packageName: String): String {
        val given = label?.toString()?.trim().orEmpty()
        if (given.isNotEmpty() && !isCode(given, packageName)) return given
        return humanisePackage(packageName) ?: packageName
    }

    /**
     * Whether [label] is a class or package name rather than a name. Two
     * things make it one: it IS the package (or starts with it, which is
     * what an unlabelled activity's class name looks like), or it reads
     * like dotted code -- no whitespace, at least one dot, every segment a
     * Java identifier. A real title with a dot in it ("Mr. Driller",
     * "S.T.A.L.K.E.R.") has a space or an empty segment and fails that
     * test.
     */
    private fun isCode(label: String, packageName: String): Boolean {
        if (label == packageName || label.startsWith(packageName + ".")) return true
        if (label.any { it.isWhitespace() } || !label.contains('.')) return false
        return label.split('.').all { segment ->
            segment.isNotEmpty() &&
                (segment.first().isLetter() || segment.first() == '_') &&
                segment.all { it.isLetterOrDigit() || it == '_' }
        }
    }

    /**
     * `com.bluestacks.bsxlauncher` -> "Bsxlauncher";
     * `org.videolan.vlc` -> "Vlc"; `com.my_studio.coolGame.android` ->
     * "Cool Game". Null when nothing readable is left, which is when the
     * caller falls back to the package itself.
     */
    fun humanisePackage(packageName: String): String? {
        val segments = packageName.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        // The last segment that says something. A package that is nothing
        // but generic words keeps its last one rather than nothing.
        val chosen = segments.lastOrNull { it.lowercase() !in GENERIC_SEGMENTS } ?: segments.last()
        return humanise(chosen)
    }

    /** `coolGame` / `cool_game` / `cool-game` -> "Cool Game". */
    private fun humanise(segment: String): String? {
        val spaced = StringBuilder()
        for ((index, character) in segment.withIndex()) {
            val previous = segment.getOrNull(index - 1)
            when {
                character == '_' || character == '-' -> spaced.append(' ')
                character.isUpperCase() && previous != null && (previous.isLowerCase() || previous.isDigit()) -> {
                    spaced.append(' ').append(character)
                }
                else -> spaced.append(character)
            }
        }
        val words = spaced.toString().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        return words.joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
    }
}
