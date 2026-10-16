package dev.droidtop.runtime.launcherimport

/**
 * Reads a SharedPreferences file's `<map>` XML, as Nova and Apex put it in their backups (Droidtop/tracker#564),
 * without installing it as a preferences file. A small strict reader of that one format (`string`, `int`, `long`,
 * `float`, `boolean`, `set` of strings, `null`) rather than an XML library, so no entity or DTD is ever expanded and it
 * reads the same on the JVM and on Android. Anything it does not know is skipped. Pure.
 */
object PrefsXml {
    private val ELEMENT = Regex(
        "<(string|int|long|float|boolean|set|null)\\s+name=\"([^\"]*)\"(?:\\s+value=\"([^\"]*)\")?\\s*(/>|>(.*?)</\\1>)",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val SET_ITEM = Regex("<string>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

    fun parse(xml: String): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (match in ELEMENT.findAll(xml)) {
            val (type, rawName, value, _, body) = match.destructured
            val name = unescape(rawName)
            val parsed: Any? = when (type) {
                "string" -> unescape(body)
                "int" -> value.toIntOrNull()
                "long" -> value.toLongOrNull()
                "float" -> value.toFloatOrNull()
                "boolean" -> value.toBooleanStrictOrNull()
                "set" -> SET_ITEM.findAll(body).map { unescape(it.groupValues[1]) }.toSet()
                else -> null
            }
            if (parsed != null) out[name] = parsed
        }
        return out
    }

    /** XML's five named entities and numeric character references. */
    fun unescape(text: String): String {
        if ('&' !in text) return text
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|lt|gt|amp|quot|apos);").replace(text) { m ->
            when (val e = m.groupValues[1]) {
                "lt" -> "<"
                "gt" -> ">"
                "amp" -> "&"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code = if (e.startsWith("#x")) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                    code?.let { String(Character.toChars(it)) } ?: m.value
                }
            }
        }
    }
}

/** Typed reads over a parsed preferences map, tolerant of a value stored as another type by another version. */
internal class Prefs(private val map: Map<String, Any>) {
    fun has(key: String) = key in map
    fun string(key: String): String? = map[key]?.toString()
    fun int(key: String): Int? = when (val v = map[key]) {
        is Int -> v
        is Long -> v.toInt()
        is Float -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }
    fun bool(key: String): Boolean? = when (val v = map[key]) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull()
        else -> null
    }
    @Suppress("UNCHECKED_CAST")
    fun stringSet(key: String): Set<String>? = when (val v = map[key]) {
        is Set<*> -> v as Set<String>
        is String -> v.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        else -> null
    }
    fun keys(): Set<String> = map.keys
}
