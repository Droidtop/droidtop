package dev.droidtop.pluginhost

/**
 * A plugin job's failure as the person reads it (Droidtop/tracker#790): one plain sentence on the job's line, and a
 * technical message's own words kept behind it as the job's Details. On the console the Syncthing plugin's job line was
 * a class loader's exception text ("dalvik.system.InMemoryDexClassLoader[DexPathList[[dex file ..."), which the owner
 * read as "internal context data". A plugin's own plain sentence ("Sign in to F95zone first") stays the line as it is.
 * Pure.
 */
object JobFailureLine {
    data class Line(val sentence: String, val detail: String?)

    /** Longer than this, or on several lines, a message is a log, not a sentence. */
    const val MAX_PLAIN = 200

    /** The longest detail kept in a job's report. */
    const val MAX_DETAIL = 4_000

    /** Signs that a message is a runtime's words, not the plugin's: a qualified class name, an exception, a stack frame. */
    private val TECHNICAL = Regex(
        "\\b[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+\\.[A-Z][A-Za-z0-9_$]*|Exception\\b|\\bError:|\\bat [a-z][\\w.$]+\\(|" +
            "DexPathList|ClassLoader|nativeLibraryDirectories|\\bcookie=|\\berrno\\b|0x[0-9a-fA-F]{8,}",
    )

    /** A native library the plugin's own code could not load. */
    private val NATIVE = Regex("couldn't find \"lib[^\"]*\\.so\"|dlopen failed|UnsatisfiedLinkError|cannot locate symbol")

    /** The line for [error], a failed job of the plugin called [label]. */
    fun of(label: String, error: String?): Line {
        val text = error?.trim().orEmpty()
        if (text.isEmpty()) return Line("$label couldn't finish this", null)
        if (text.length <= MAX_PLAIN && '\n' !in text && !TECHNICAL.containsMatchIn(text)) return Line(text, null)
        val sentence = if (NATIVE.containsMatchIn(text)) {
            "$label couldn't load its native code on this device"
        } else {
            "$label ran into a problem it didn't explain"
        }
        return Line(sentence, text.take(MAX_DETAIL))
    }
}
