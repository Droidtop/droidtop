package org.pocketworkstation.pckeyboard

/**
 * Reads a `.combined` wordlist, the text form AOSP's dictionaries and their open successors are published in
 * (Droidtop/tracker#339): a header line, then ` word=<word>,f=<0..255>,...` lines, each followed by its
 * `  bigram=<next word>,f=<0..255>` lines. Shortcuts, words marked `not_a_word=true` and unknown keys are skipped.
 * Bigram frequencies are halved to the engine's 7 bits. Pure.
 */
object CombinedWordlist {
    data class Parsed(val header: Map<String, String>, val words: Map<String, Int>, val bigrams: Map<String, List<Pair<String, Int>>>)

    fun parse(lines: Sequence<String>): Parsed {
        var header = emptyMap<String, String>()
        val words = LinkedHashMap<String, Int>()
        val bigrams = LinkedHashMap<String, MutableList<Pair<String, Int>>>()
        var current: String? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val fields = fields(line)
            when {
                "dictionary" in fields -> header = fields
                "word" in fields -> {
                    val word = fields.getValue("word")
                    val freq = fields["f"]?.toIntOrNull()
                    current = null
                    if (word.isEmpty() || freq == null || fields["not_a_word"] == "true") continue
                    words[word] = freq.coerceIn(0, 255)
                    current = word
                }
                "bigram" in fields -> {
                    val word = current ?: continue
                    val next = fields.getValue("bigram")
                    val freq = fields["f"]?.toIntOrNull() ?: continue
                    if (next.isNotEmpty()) bigrams.getOrPut(word) { mutableListOf() } += next to (freq / 2).coerceIn(1, 127)
                }
            }
        }
        return Parsed(header, words, bigrams)
    }

    /** `key=value,key=value`; the first key's value runs to the next `,<key>=` so a word may itself hold a comma. */
    private fun fields(line: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val parts = Regex(""",(?=[A-Za-z_]+=)""").split(line)
        for (part in parts) {
            val eq = part.indexOf('=')
            if (eq > 0) result[part.substring(0, eq)] = part.substring(eq + 1)
        }
        return result
    }
}
