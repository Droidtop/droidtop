package dev.droidtop.pluginhost

/**
 * Host op names (docs/plugin-api.md 3 "Naming", Droidtop/tracker#459; owner, 2026-10-10: "make API call names simple
 * and descriptive ... the name should match it"). A call is `<area>.<verb>` or `<area>.<verb>_<object>`; a read-only
 * call starts with a read verb ([READ_VERBS]). The names droidtop served before contract 2.1 are deprecated aliases
 * for one release ([DEPRECATED]): a plugin calling one is answered as if it had used the new name. [check] is the
 * rule as code, for the approval screen and the bundle CI's warning.
 */
object HostOpNames {
    /** Contract 2.1 renamed the host ops below; `host.get_info` reports it. */
    const val CONTRACT_MINOR = 1

    /** Old `<api>.<op>` to new, kept until contract 2.2 (docs/plugin-api.md 7). */
    val DEPRECATED: Map<String, String> = linkedMapOf(
        "net.state" to "net.get_state",
        "net.http" to "net.request",
        "host.info" to "host.get_info",
        "plugins.available" to "plugins.list_available",
        "plugins.job_status" to "job.get_status",
        "plugins.job_cancel" to "job.cancel",
        "plugins.report_level" to "provider.report_level",
        "data.usage" to "data.get_usage",
        "data.path" to "data.get_path",
        "storage.volumes" to "storage.list_volumes",
        "vault.keys" to "vault.list_keys",
        "web.session.status" to "web.session.get_status",
        "apps.check" to "apps.check_installed",
        "apps.intent" to "apps.send_intent",
        "apps.view" to "link.open",
        "social.changed" to "social.report_changed",
        "library.files.changed" to "library.files.report_changed",
        "library.read.systems" to "library.read.list_systems",
        "companion.recording" to "companion.set_recording",
        "retroarch.status" to "retroarch.get_status",
        "retroarch.command" to "retroarch.send_command",
    )

    /** The current (api, op) for a name a caller used, renamed when it is a deprecated one. */
    fun current(api: String, op: String): Pair<String, String> {
        val renamed = DEPRECATED["$api.$op"] ?: return api to op
        return renamed.substringBeforeLast('.') to renamed.substringAfterLast('.')
    }

    /** Verbs a read-only call starts with. */
    val READ_VERBS = setOf("get", "list", "check", "read", "find", "search", "count")

    /**
     * Why op [op] breaks the naming rule, or null when it follows it: lower case words joined by `_`, starting with a
     * verb, no bare nouns or past tenses, and a call declared read-only starting with a read verb. Plugins are not
     * held to it; droidtop warns (docs/plugin-api.md 3 "Naming").
     */
    fun check(op: String, readOnly: Boolean? = null): String? {
        if (!op.matches(Regex("[a-z][a-z0-9]*(_[a-z0-9]+)*"))) return "\"$op\" is not lower-case words joined by _"
        val verb = op.substringBefore('_')
        if (verb.endsWith("ed") && verb != "seed") return "\"$op\" reads as an event; name what the call does"
        if (verb in NOUNS) return "\"$op\" is a noun; name what the call does (get_$op, list_$op)"
        if (readOnly == true && verb !in READ_VERBS) return "\"$op\" only reads, so it should start with ${READ_VERBS.joinToString("/")}"
        return null
    }

    /** Bare nouns that have been used as op names and say nothing of what happens. */
    private val NOUNS = setOf("status", "state", "info", "keys", "usage", "path", "volumes", "systems", "available", "recording", "http")
}
