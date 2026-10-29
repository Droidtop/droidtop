package dev.droidtop.net.sitecheck

import dev.droidtop.net.Http
import dev.droidtop.shared.sitecheck.CheckOutcome
import dev.droidtop.shared.sitecheck.Clearance
import dev.droidtop.shared.sitecheck.SiteAsker
import dev.droidtop.shared.sitecheck.SiteCheck
import dev.droidtop.shared.sitecheck.SiteSolver
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A request met a site's browser check that was not passed (yet): the site checks the browser first. */
class SiteCheckRequired(val host: String) : IOException("$host checks the browser first; open it in droidtop to continue")

/**
 * The one place a browser check is dealt with for the requests droidtop makes (docs/SPEC.md 12a, Droidtop/tracker#751):
 * [run] makes a request with the site's clearance on it, and when the answer is a check ([SiteCheck.isChallenge]) lets
 * the browser try first ([solver]: a web view nobody sees, [solveMs] at most, never a click), asks the person only if
 * that does not pass ([asker]), and makes the request once more. Requests to one host queue behind one check: the ones
 * that waited find the clearance the first earned and do not ask again. A check nobody passes is remembered for
 * [pendingMs], so a burst of requests raises one notice and not many; a request the person started asks again
 * ([asUser]).
 *
 * Blocking, as [Http] is: call it from an IO dispatcher.
 */
class SiteGate(
    private val store: SiteClearanceStore,
    private val solver: SiteSolver?,
    private val asker: SiteAsker?,
    private val isForeground: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val solveMs: Long = SOLVE_MS,
    private val pendingMs: Long = PENDING_MS,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val pendingUntil = ConcurrentHashMap<String, Long>()

    /**
     * [perform] makes the request with the headers it is given. It is run with [headers] plus the host's clearance, and
     * once more after a check is passed. Throws [SiteCheckRequired] when the check is not passed, or when a pass is
     * refused again (the clearance is then forgotten, and the request is not made a third time).
     */
    fun run(url: String, headers: Map<String, String>, perform: (Map<String, String>) -> Http.Response): Http.Response {
        val host = hostOf(url) ?: return perform(headers)
        val sent = store.headersFor(host)
        val response = perform(withClearance(headers, sent))
        if (!isChallenge(response)) return response
        if (!settle(host, url, sent)) throw SiteCheckRequired(host)
        val again = perform(withClearance(headers, store.headersFor(host)))
        if (isChallenge(again)) {
            // The pass did not take: forget it, so that it is not sent again, and do not ask in a loop.
            store.forgetCovering(host)
            throw SiteCheckRequired(host)
        }
        return again
    }

    /** True when [host] can be requested again: a clearance is there that this request did not have. */
    private fun settle(host: String, url: String, sent: SiteClearanceStore.Headers?): Boolean {
        val mutex = locks.computeIfAbsent(host) { Mutex() }
        return runBlocking {
            mutex.withLock {
                val current = store.headersFor(host)
                // Another request passed it while this one waited: nothing to ask.
                if (current != null && current != sent) return@withLock true
                // The clearance this request sent was refused: forget it so it is not sent again.
                if (current != null) store.forgetCovering(host)
                val user = userInitiated.get() == true
                if (!user && (pendingUntil[host] ?: 0L) > now()) return@withLock false
                val earned = attempt(url, inPlace = user && isForeground())
                if (earned == null) {
                    pendingUntil[host] = now() + pendingMs
                    false
                } else {
                    pendingUntil.remove(host)
                    store.keep(earned)
                    true
                }
            }
        }
    }

    /** The browser's own try, then the person; the clearance a pass earned, or null. */
    private suspend fun attempt(url: String, inPlace: Boolean): Clearance? {
        val first = solver?.let { runCatching { it.solve(url, solveMs) }.getOrElse { e -> CheckOutcome.Failed(e.message ?: "the browser could not try") } }
        if (first is CheckOutcome.Passed) return first.clearance
        val asked = asker?.let { runCatching { it.ask(url, inPlace) }.getOrElse { e -> CheckOutcome.Failed(e.message ?: "the person could not be asked") } }
        return (asked as? CheckOutcome.Passed)?.clearance
    }

    private fun isChallenge(response: Http.Response): Boolean =
        SiteCheck.isChallenge(response.status, response.headers.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(), response.body)

    /** [headers] with the clearance cookies merged into any cookie the request has, and the web view's user agent. */
    private fun withClearance(headers: Map<String, String>, clearance: SiteClearanceStore.Headers?): Map<String, String> {
        if (clearance == null) return headers
        val cookieKey = headers.keys.firstOrNull { it.equals("Cookie", ignoreCase = true) } ?: "Cookie"
        val merged = SiteCheck.mergeCookies(headers[cookieKey], clearance.cookie) ?: return headers
        var out = headers + (cookieKey to merged)
        // The clearance is bound to the user agent of the view that earned it.
        clearance.userAgent?.let { ua ->
            val uaKey = out.keys.firstOrNull { it.equals("User-Agent", ignoreCase = true) } ?: "User-Agent"
            out = out + (uaKey to ua)
        }
        return out
    }

    companion object {
        /** How long the browser is given to pass a check by itself: a JavaScript challenge, or a Turnstile that wants no input. */
        const val SOLVE_MS = 15_000L

        /** How long a check nobody passed is remembered, so that a burst of requests asks once. */
        const val PENDING_MS = 5 * 60_000L

        private val userInitiated = ThreadLocal<Boolean>()

        /** Runs [block] as something the person started: a check it meets is asked in place, and asked again even if one is pending. */
        fun <T> asUser(block: () -> T): T {
            val before = userInitiated.get()
            userInitiated.set(true)
            try {
                return block()
            } finally {
                userInitiated.set(before)
            }
        }

        fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()?.takeIf { it.isNotEmpty() }
    }
}

/** The gate droidtop's requests go through, set when the app starts; none until then (and every request is made as before). */
object SiteGates {
    @Volatile
    var current: SiteGate? = null
}

/**
 * The one clearance store of this process (docs/SPEC.md 12a): the gate, a plugin's requests and the web view's results
 * all keep to it and read from it. Installed when the app starts; it is made on first use, so the file is not read on
 * the main thread. None until then, and the code that reads it carries on as it did without one.
 */
object SiteClearances {
    private val lock = Any()
    private var make: (() -> SiteClearanceStore)? = null
    private var made: SiteClearanceStore? = null

    fun install(make: () -> SiteClearanceStore) = synchronized(lock) {
        this.make = make
        made = null
    }

    val current: SiteClearanceStore?
        get() = synchronized(lock) { made ?: make?.invoke()?.also { made = it } }
}
