package dev.droidtop.library.stores

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import dev.droidtop.pluginhost.WebSessionActivity
import dev.droidtop.pluginhost.WebSessionRequest
import dev.droidtop.pluginhost.WebSessions
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A store's own web pages (docs/SPEC.md 7g, "A store's own pages"): where its front page is, which sites are the
 * store's (each covers its subdomains), and its search page. Plain addresses; how they are shown is [StoreWeb]'s.
 */
class StoreWebPages(
    /** The store's front page. */
    val home: String,
    /** The sites that are the store's own: a link on one of them opens in that store's view. */
    val hosts: List<String>,
    /** The store's search page for what the person typed. */
    private val searchPage: String,
) {
    /** The store's search page for [query]. */
    fun search(query: String): String = searchPage + URLEncoder.encode(query.trim(), "UTF-8")

    /** True when [url] is an https address on one of the store's sites. */
    fun covers(url: String): Boolean {
        val host = StoreWeb.httpsHost(url) ?: return false
        return hosts.any { host == it || host.endsWith(".$it") }
    }
}

/**
 * A store's own pages in droidtop's web view, signed in as the account this device holds (docs/SPEC.md 7g, "A store's
 * own pages", Droidtop/tracker#407): browse, claim a free game, buy one. The view is the one droidtop shows every
 * signed-in web page in ([WebSessionActivity], shared with a plugin's session), in its browse mode: it reads no page
 * content, so payment details stay between the person and the store.
 *
 * What droidtop does with the visit is sync that store, so a game the person got appears in the library: at once when
 * a page the store shows after an order is reached ([isOrderDone]), and once more when the view closes, which catches
 * every store whose last page droidtop does not recognise. Each sync names the games that are new since the view
 * opened (the library diff) in one short message.
 */
object StoreWeb {
    private const val TAG = "droidtop.StoreWeb"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One sync of a store at a time, whichever page or close asked. */
    private val syncing = Mutex()

    /** The registered store whose own sites [url] is on, or null. */
    fun storeFor(url: String): StoreLibrary? = StoreLibraries.all().firstOrNull { it.webPages?.covers(url) == true }

    /**
     * Opens [store]'s pages at [url] (its front page when null) and returns at once; false when the store has no pages
     * or [url] is not on them. The account's web sign-in, when the store hands one ([StoreLibrary.webSession]), is
     * fetched off the main thread before the view opens.
     */
    fun open(context: Context, store: StoreLibrary, url: String? = null): Boolean {
        val pages = store.webPages ?: return false
        val start = url ?: pages.home
        if (!pages.covers(start)) return false
        val app = context.applicationContext
        scope.launch {
            val signedIn = runCatching { store.signedIn(app) }.getOrDefault(false)
            val known = AtomicReference(if (signedIn) gamesOf(app, store) else emptyMap())
            val cookies = if (signedIn) {
                runCatching { store.webSession(app) }
                    .onFailure { Log.w(TAG, "${store.label} gave no web sign-in; its pages open signed out", it) }
                    .getOrNull()
                    ?.filter { (site, header) -> header.isNotBlank() && pages.hosts.contains(site) }
            } else {
                null
            }
            val handled = Collections.synchronizedSet(HashSet<String>())
            val request = WebSessionRequest(
                label = store.label,
                mode = WebSessionRequest.Mode.BROWSE,
                url = start,
                domains = cookies?.keys?.toList().orEmpty(),
                session = cookies?.takeIf { it.isNotEmpty() }?.let { WebSessions.Stored(userAgent = null, cookies = it) },
                doneCookie = null,
                onPage = { page ->
                    if (signedIn && isOrderDone(page, pages) && handled.add(page)) scope.launch { syncAndSay(app, store, known) }
                },
            )
            withContext(Dispatchers.Main) {
                WebSessionActivity.open(app, request) {
                    if (signedIn) scope.launch { syncAndSay(app, store, known) }
                }
            }
        }
        return true
    }

    /** Opens [store]'s search page for [query]; false when the store has no pages or the query is blank. */
    fun search(context: Context, store: StoreLibrary, query: String): Boolean {
        val pages = store.webPages ?: return false
        if (query.isBlank()) return false
        return open(context, store, pages.search(query))
    }

    /**
     * Opens [url] in the view of the store whose page it is; false when it is no store's page droidtop runs (the
     * caller then hands it to the system's browser).
     */
    fun openLink(context: Context, url: String): Boolean {
        val store = storeFor(url) ?: return false
        return open(context, store, url)
    }

    /** Takes [store]'s web sign-in out of droidtop's web view: the person signed out of the store. */
    fun forget(store: StoreLibrary) {
        val hosts = store.webPages?.hosts ?: return
        Handler(Looper.getMainLooper()).post { runCatching { WebSessionActivity.forget(hosts) } }
    }

    /**
     * Whether [url] is a page one of the store's sites shows after an order went through: its address names an order
     * (checkout, purchase, order, claim, a free licence) and an outcome (success, thanks, receipt, complete,
     * confirmed, approved). Read from the address alone; the page is never read. A store whose finished order shows
     * at an address this does not match is still synced when the view closes.
     */
    fun isOrderDone(url: String, pages: StoreWebPages): Boolean {
        if (!pages.covers(url)) return false
        val rest = runCatching { URI(url) }.getOrNull()?.let { listOfNotNull(it.rawPath, it.rawQuery, it.rawFragment).joinToString("?") }
            ?.lowercase() ?: return false
        return ORDER.containsMatchIn(rest) && OUTCOME.containsMatchIn(rest)
    }

    /** The host of an https address, lower case, or null. */
    fun httpsHost(url: String): String? = runCatching { URI(url.trim()) }.getOrNull()
        ?.takeIf { it.scheme.equals("https", ignoreCase = true) }
        ?.host?.lowercase()?.removeSuffix(".")

    /** The games of [store] that [after] has and [before] did not, by title. */
    fun added(before: Map<String, String>, after: Map<String, String>): List<String> =
        after.filterKeys { it !in before }.values.sortedBy { it.lowercase() }

    /** One line naming what a sync added, or null when it added nothing. */
    fun addedLine(store: String, titles: List<String>): String? = when {
        titles.isEmpty() -> null
        titles.size == 1 -> "Added to your library from $store: ${titles.single()}"
        titles.size <= 3 -> "Added to your library from $store: ${titles.joinToString(", ")}"
        else -> "Added to your library from $store: ${titles.take(2).joinToString(", ")} and ${titles.size - 2} more"
    }

    private val ORDER = Regex("checkout|purchase|order|claim|freelicense")
    private val OUTCOME = Regex("success|thank|receipt|complete|confirm|approved")

    /** [store]'s rows as droidtop's own copy has them, key to title. No network. */
    private suspend fun gamesOf(context: Context, store: StoreLibrary): Map<String, String> =
        runCatching { store.games(context) }.getOrDefault(emptyList()).associate { it.key to it.title }

    /** Syncs [store] through the one sync every store has, then names the games that are new since [known]. */
    private suspend fun syncAndSay(context: Context, store: StoreLibrary, known: AtomicReference<Map<String, String>>) {
        syncing.withLock {
            StoreSyncs.run(context, store).onFailure {
                Log.w(TAG, "Syncing ${store.label} after its pages closed failed", it)
                return
            }
            StoreChanges.announce(context)
            val after = gamesOf(context, store)
            val line = addedLine(store.label, added(known.getAndSet(after), after)) ?: return
            Handler(Looper.getMainLooper()).post { Toast.makeText(context, line, Toast.LENGTH_LONG).show() }
        }
    }
}
