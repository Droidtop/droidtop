package dev.droidtop.net.sitecheck

import dev.droidtop.shared.sitecheck.Clearance
import dev.droidtop.shared.sitecheck.SiteCheck
import java.io.File

/**
 * What passing browser checks earned, one store for all of droidtop (docs/SPEC.md 12a, Droidtop/tracker#751): per site
 * (a site covers its subdomains) the clearance cookies and the date they were earned, and the user agent of the web
 * view that earned them, which the clearance is bound to. A plugin's account cookies are not here; they stay in its
 * vault. Kept in [storage] as plain text so that a restart does not ask again.
 */
class SiteClearanceStore(private val storage: Storage, private val now: () -> Long = System::currentTimeMillis) {
    /** Where the text is kept: a file in the app ([FileStorage]), or memory in a test. */
    interface Storage {
        fun read(): String?
        fun write(text: String)
    }

    /** A site passed, with the date, for Settings > Privacy. */
    data class Entry(val site: String, val cookies: String, val passedAtMs: Long)

    /** The headers droidtop's requests to a host carry once it has been passed: the clearance cookies and the user agent. */
    data class Headers(val cookie: String, val userAgent: String?)

    private val lock = Any()
    private var clearance: Clearance? = null
    private val passedAt = LinkedHashMap<String, Long>()

    init {
        parse(storage.read())
    }

    /** The clearance headers for [host], or null when its site has not been passed (or was forgotten). */
    fun headersFor(host: String): Headers? = synchronized(lock) {
        val c = clearance ?: return null
        c.forHost(host)?.let { Headers(it, c.userAgent) }
    }

    /** Everything kept, for a caller that reads it per site ([Clearance.forHost]); null when nothing is. */
    fun snapshot(): Clearance? = synchronized(lock) { clearance }

    /** Keeps what a pass earned over what was kept: a newer pass of a site replaces its older one. */
    fun keep(earned: Clearance) = synchronized(lock) {
        clearance = clearance?.plus(earned) ?: earned
        val at = now()
        earned.cookies.keys.forEach { passedAt[it] = at }
        save()
    }

    /** Forgets the clearance that covers [host] (a pass the site stopped taking), and returns whether there was one. */
    fun forgetCovering(host: String): Boolean = synchronized(lock) {
        val site = clearance?.cookies?.keys?.filter { SiteCheck.covers(it, host) }?.maxByOrNull { it.length } ?: return false
        forgetLocked(site)
        true
    }

    /** Forgets [site]'s clearance (Settings > Privacy > Forget). */
    fun forget(site: String) = synchronized(lock) {
        if (clearance?.cookies?.containsKey(site) == true) forgetLocked(site)
    }

    /** Every site passed, most recent first. */
    fun sites(): List<Entry> = synchronized(lock) {
        clearance?.cookies.orEmpty().map { (site, cookies) -> Entry(site, cookies, passedAt[site] ?: 0L) }.sortedByDescending { it.passedAtMs }
    }

    private fun forgetLocked(site: String) {
        clearance = clearance?.without(site)?.takeIf { it.cookies.isNotEmpty() }
        passedAt.remove(site)
        save()
    }

    private fun save() {
        val c = clearance
        val text = buildString {
            appendLine(HEADER)
            if (c != null) {
                appendLine("ua\t${c.userAgent.orEmpty()}")
                c.cookies.forEach { (site, cookies) -> appendLine("site\t${passedAt[site] ?: 0L}\t$site\t$cookies") }
            }
        }
        runCatching { storage.write(text) }
    }

    private fun parse(text: String?) {
        if (text == null) return
        val lines = text.lines()
        if (lines.firstOrNull() != HEADER) return
        var ua: String? = null
        val cookies = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val parts = line.split('\t')
            when {
                parts.size >= 2 && parts[0] == "ua" -> ua = parts[1].ifEmpty { null }
                parts.size >= 4 && parts[0] == "site" -> {
                    cookies[parts[2]] = parts.drop(3).joinToString("\t")
                    passedAt[parts[2]] = parts[1].toLongOrNull() ?: 0L
                }
            }
        }
        clearance = Clearance(ua, cookies).takeIf { cookies.isNotEmpty() }
    }

    /** [file], replaced whole through a temporary file beside it. */
    class FileStorage(private val file: File) : Storage {
        override fun read(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

        override fun write(text: String) {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.writeText(text)
                temp.delete()
            }
        }
    }

    private companion object {
        const val HEADER = "droidtop-site-clearance 1"
    }
}
