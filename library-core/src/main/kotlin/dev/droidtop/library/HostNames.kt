package dev.droidtop.library

/**
 * One name per file host (Droidtop/tracker#859): a host a thread names by another of its domains is that host.
 * BZZHR (bzzhr.co) is BuzzHeavier's short domain, so a build that lists both offered one host twice under two names.
 * Matching ignores case and a ".co"/".com"-style ending, as threads write hosts either way. Pure.
 */
object HostNames {
    /** Another name of a host, by its key, to the host it is. */
    private val ALIASES = mapOf(
        "bzzhr" to "buzzheavier",
        "pixeldra" to "pixeldrain",
        // mega.co.nz loses one ending (".nz") before the dots go.
        "megaco" to "mega",
    )

    /** How a host is shown when droidtop folded another name into it. */
    private val DISPLAY = mapOf(
        "buzzheavier" to "BuzzHeavier",
        "pixeldrain" to "Pixeldrain",
        "mega" to "MEGA",
    )

    /** [host]'s one key: lower case, letters and digits only, a domain ending dropped, another name folded in. */
    fun key(host: String): String {
        val bare = host.trim().lowercase().removePrefix("www.")
            .replace(Regex("\\.(co|com|net|org|io|to|in|nz|cc|me)$"), "")
            .filter { it.isLetterOrDigit() }
        return ALIASES[bare] ?: bare
    }

    /**
     * [host] as a row shows it: as the thread wrote it, unless it is another name of a host, which is then shown by
     * the host's own name (the name the thread used for it when [seen] holds one, as "BUZZHEAVIER" beside "BZZHR").
     */
    fun display(host: String, seen: Collection<String> = emptyList()): String {
        val key = key(host)
        if (key == host.trim().lowercase().filter { it.isLetterOrDigit() }) return host
        return seen.firstOrNull { key(it) == key && it.trim().lowercase().filter(Char::isLetterOrDigit) == key }
            ?: DISPLAY[key] ?: host
    }

    /** [hosts] once each, by [key], each shown by [display]. */
    fun distinct(hosts: List<String>): List<String> = hosts.distinctBy(::key).map { display(it, hosts) }
}
