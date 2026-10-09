package dev.droidtop.library

/**
 * The text a person types for one game's launch options and environment, and
 * the rules both are read by (docs/SPEC.md 7i, "Game properties"). Pure.
 *
 * Launch options are cut into arguments here and handed to the program one
 * by one, never through a shell, so a quote or a `;` in them is only text.
 * Environment variables are the tuning variables droidtop already imports
 * from a Lutris script (docs/SPEC.md 7e3, threat model decision 5): the same
 * names and the same plain values, because a game's properties and an import
 * write the same setting and must refuse the same things. Loader and path
 * variables (`LD_PRELOAD`, `WINEPREFIX`, `PATH`) are never accepted.
 */
object GameLaunchOptions {
    /** The most characters of launch options kept; a command line longer than this is not a setting. */
    const val MAX_OPTIONS_LENGTH = 1024

    /**
     * [text] as arguments: split at spaces, with "double" or 'single' quotes
     * keeping a space inside one argument and a backslash making the next
     * character plain (not inside single quotes). A quote left open runs to
     * the end. Empty arguments written as "" are kept.
     */
    fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inToken = false
        var quote: Char? = null
        var index = 0
        val source = text.take(MAX_OPTIONS_LENGTH)
        while (index < source.length) {
            val c = source[index]
            when {
                quote == '\'' -> if (c == '\'') quote = null else current.append(c)
                quote == '"' -> when {
                    c == '"' -> quote = null
                    c == '\\' && index + 1 < source.length && source[index + 1] in "\"\\" -> {
                        index++
                        current.append(source[index])
                    }
                    else -> current.append(c)
                }
                c == '"' || c == '\'' -> {
                    quote = c
                    inToken = true
                }
                c == '\\' && index + 1 < source.length -> {
                    index++
                    current.append(source[index])
                    inToken = true
                }
                c.isWhitespace() -> if (inToken) {
                    tokens += current.toString()
                    current.setLength(0)
                    inToken = false
                }
                else -> {
                    current.append(c)
                    inToken = true
                }
            }
            index++
        }
        if (inToken) tokens += current.toString()
        return tokens
    }

    /** Tuning variables only, never loader or path variables (docs/SPEC.md 7e3, threat model decision 5). */
    private val ENV_NAMES: List<(String) -> Boolean> = listOf(
        { it.startsWith("DXVK_") },
        { it.startsWith("VKD3D_") },
        { it.startsWith("MESA_") },
        { it.startsWith("mesa_") },
        { it.startsWith("__GL_") },
        { it == "WINE_LARGE_ADDRESS_AWARE" },
        { it == "STAGING_SHARED_MEMORY" },
        { it == "PULSE_LATENCY_MSEC" },
    )

    private val ENV_VALUE = Regex("[A-Za-z0-9_.,:=+-]{0,256}")

    /** Whether [name] is one of the variables droidtop accepts for a game. */
    fun isAllowedEnvName(name: String): Boolean = ENV_NAMES.any { it(name) }

    /** Whether [value] is a plain value: no paths, no variables, no spaces. */
    fun isAllowedEnvValue(value: String): Boolean = ENV_VALUE.matches(value)

    /** What [parseEnvironment] made of a text: the variables it kept and, for each it refused, the reason. */
    data class Environment(val variables: Map<String, String>, val refused: List<String>)

    /**
     * `NAME=value` pairs separated by spaces or new lines. A pair with no `=`,
     * a name or value outside the rules, or a repeated name is refused with a
     * line saying why; the rest is kept, in the order written.
     */
    fun parseEnvironment(text: String): Environment {
        val kept = linkedMapOf<String, String>()
        val refused = mutableListOf<String>()
        for (pair in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', missingDelimiterValue = "\u0000")
            when {
                value == "\u0000" -> refused += "$pair: write it as NAME=value"
                !isAllowedEnvName(name) -> refused += "$name: not one of the variables droidtop sets for a game"
                !isAllowedEnvValue(value) -> refused += "$name: a value is letters, digits and _ . , : = + - only"
                name in kept -> refused += "$name: written twice, the first is kept"
                else -> kept[name] = value
            }
        }
        return Environment(kept, refused)
    }

    /** The variables as the text [parseEnvironment] reads and the launch carries: `A=1 B=2`. */
    fun formatEnvironment(variables: Map<String, String>): String =
        variables.entries.joinToString(" ") { (name, value) -> "$name=$value" }

    /**
     * What a launch carries of [stored] variables: the rules are asked again
     * here, so a setting that was edited outside droidtop, or saved under older
     * rules, still cannot put a loader or path variable into Wine.
     */
    fun launchEnvironment(stored: Map<String, String>): String =
        formatEnvironment(stored.filter { (name, value) -> isAllowedEnvName(name) && isAllowedEnvValue(value) })
}
