package dev.droidtop.pluginhost

import org.json.JSONArray

/**
 * The systems a download is for (docs/plugin-api.md 1.10, the `download` bundle's `systems`; Droidtop/tracker#617):
 * what the record page's system picker offers, so the person sees only the links for the system they pick. A plugin
 * names them on each entry; droidtop reads them from the entry's heading when it names none ("Win/Linux" is Windows
 * and Linux), as forum threads put their links under one heading per platform. Pure.
 */
object DownloadSystems {
    const val WINDOWS = "windows"
    const val LINUX = "linux"
    const val MAC = "mac"
    const val ANDROID = "android"

    /** The systems droidtop knows, in the order the picker offers them: the ones droidtop runs first. */
    val ALL: List<String> = listOf(WINDOWS, LINUX, MAC, ANDROID)

    val LABELS: Map<String, String> = mapOf(WINDOWS to "Windows", LINUX to "Linux", MAC to "macOS", ANDROID to "Android")

    /**
     * What droidtop says when the person picks [system], or null for none. The owner, 2026-10-23: flag Android, "this
     * will not be trackable by us. Windows or Linux is recommended".
     */
    fun notice(system: String): String? =
        if (system == ANDROID) "This will not be trackable by us. Windows or Linux is recommended." else null

    private val WORDS: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\b(win(dows|32|64)?|pc)\b""") to WINDOWS,
        Regex("""(?i)\blinux\b""") to LINUX,
        Regex("""(?i)\b(mac(os)?|osx|os x|apple)\b""") to MAC,
        Regex("""(?i)\b(android|apk)\b""") to ANDROID,
    )

    /** The systems a heading names ("Win/Linux", "MAC", "Android (APK)"); empty when it names none. */
    fun fromHeading(heading: String): List<String> {
        val spaced = heading.replace('/', ' ').replace('-', ' ').replace('&', ' ').replace(',', ' ')
        return ALL.filter { system -> WORDS.any { (words, named) -> named == system && words.containsMatchIn(spaced) } }
    }

    /** A plugin's `systems`, only droidtop's names, each once, in droidtop's order. */
    fun read(array: JSONArray?): List<String> {
        array ?: return emptyList()
        val named = (0 until array.length()).map { array.optString(it).trim().lowercase() }.toSet()
        return ALL.filter { it in named }
    }

    /** The systems of one entry: the ones it names, else the ones its heading names. */
    fun of(named: List<String>, heading: String): List<String> = named.ifEmpty { fromHeading(heading) }

    private val EXTRAS = Regex("""(?i)\b(extras?|walkthrough|guide|patch(es)?|fix(es)?|saves?|save ?files?|mods?|bonus|dlc|ost|soundtrack|art ?books?|gallery|cheats?|translation)\b""")

    /**
     * Whether a heading over links for any system names an extra (a walkthrough, a patch, a save file): those go under
     * "Extras" on the Downloads tab, any other under "Other" (the owner, 2026-10-23, Droidtop/tracker#617).
     */
    fun isExtra(heading: String): Boolean = EXTRAS.containsMatchIn(heading)
}
