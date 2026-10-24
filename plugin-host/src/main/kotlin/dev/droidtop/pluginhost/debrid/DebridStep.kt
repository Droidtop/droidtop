package dev.droidtop.pluginhost.debrid

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The debrid step of a torrent or magnet job (docs/SPEC.md "Download rules", Droidtop/tracker#638): before any peer is
 * contacted, the person's debrid accounts are asked in their order, and the first that has the torrent whole serves
 * it: its files come down as direct links into the job's own folder, so placing, updates and saves are as for any
 * torrent, and no peer is contacted and nothing is seeded. When none has it, the job either waits for the first
 * service that took it ([waits]) or falls back to the torrent. With no account connected nothing changes.
 */
object DebridStep {
    /** :app sets this: the person's connected accounts in their order, each a provider and its key. */
    @Volatile var accounts: (Context) -> List<Pair<DebridProvider, String>> = { emptyList() }

    /** :app sets this: whether a torrent no account has yet waits for the service (true) or falls back to peers. */
    @Volatile var waitsForService: (Context) -> Boolean = { false }

    /** How often a service fetching a torrent is asked again, and how long a job waits for it at most. */
    const val POLL_MS = 15_000L
    const val MAX_WAIT_MS = 6L * 60 * 60 * 1000

    /** One file of the served torrent fetched to [into]; the job's own fetch, with the job's progress. */
    fun interface Fetch {
        suspend fun file(file: DebridFile, into: File, line: String)
    }

    /** Serves [torrent] into [folder] from the person's accounts. Returns the service's name when one did, else null. */
    suspend fun serve(context: Context, torrent: TorrentRef, folder: File, status: (Int, String) -> Unit, fetch: Fetch): String? =
        serve(accounts(context), waitsForService(context), torrent, folder, status, fetch)

    /** [serve] with the accounts and the choice given, for the tests. */
    suspend fun serve(
        accounts: List<Pair<DebridProvider, String>>,
        waits: Boolean,
        torrent: TorrentRef,
        folder: File,
        status: (Int, String) -> Unit,
        fetch: Fetch,
        pollMs: Long = POLL_MS,
        maxWaitMs: Long = MAX_WAIT_MS,
    ): String? {
        if (accounts.isEmpty()) return null
        status(-1, "Asking ${accounts.joinToString(" and ") { it.first.name }}…")
        // Every service asked by adding the torrent; the ones that did not serve it take it back off the list after.
        val asked = mutableSetOf<Pair<DebridProvider, String>>()
        var server: DebridProvider? = null
        try {
            val chosen = choose(accounts, waits, torrent, status, pollMs, maxWaitMs, asked) ?: return null
            // Chosen before its files come: a download that fails part way keeps it on the list for the retry.
            server = chosen.first
            download(chosen.first, chosen.second, folder, status, fetch)
            return chosen.first.name
        } finally {
            // Also when the job is cancelled: asking leaves nothing on a list droidtop did not use.
            withContext(NonCancellable) {
                for ((provider, key) in asked) if (provider != server) ask { provider.release(key, torrent) }
            }
        }
    }

    private suspend fun choose(
        accounts: List<Pair<DebridProvider, String>>,
        waits: Boolean,
        torrent: TorrentRef,
        status: (Int, String) -> Unit,
        pollMs: Long,
        maxWaitMs: Long,
        asked: MutableSet<Pair<DebridProvider, String>>,
    ): Pair<DebridProvider, DebridState.Ready>? {
        // 1. A service that says it has the torrent whole serves it.
        val unknown = mutableListOf<Pair<DebridProvider, String>>()
        for ((provider, key) in accounts) {
            when (ask { provider.cached(key, torrent) }) {
                true -> {
                    asked += provider to key
                    settle(provider, key, torrent, status, pollMs, CACHED_WAIT_MS)?.let { return provider to it }
                }
                null -> unknown += provider to key
                false -> Unit
            }
        }
        // 2. A service that cannot say is asked by adding it; one that has it whole serves it.
        var taker: Pair<DebridProvider, String>? = null
        for ((provider, key) in unknown) {
            asked += provider to key
            when (val state = ask { provider.request(key, torrent) }) {
                is DebridState.Ready -> return provider to state
                is DebridState.Caching -> if (taker == null) taker = provider to key
                else -> Unit
            }
        }
        if (!waits) return null
        // 3. Waiting for the service: the first that took it, else the first account, fetches it.
        val (provider, key) = taker ?: accounts.first()
        asked += provider to key
        return settle(provider, key, torrent, status, pollMs, maxWaitMs)?.let { provider to it }
    }

    /** Asks [provider] until the torrent is ready, for at most [maxWaitMs]; null when it fails, is not cached or runs out. */
    private suspend fun settle(provider: DebridProvider, key: String, torrent: TorrentRef, status: (Int, String) -> Unit, pollMs: Long, maxWaitMs: Long): DebridState.Ready? {
        var waited = 0L
        while (true) {
            when (val state = ask { provider.request(key, torrent) }) {
                is DebridState.Ready -> return state
                is DebridState.Caching -> {
                    if (waited >= maxWaitMs) return null
                    val percent = state.progress?.let { (it.coerceIn(0.0, 1.0) * 100).toInt() }
                    status(percent ?: -1, "Waiting for ${provider.name}" + (percent?.let { ": $it%" } ?: "…"))
                    delay(pollMs)
                    waited += pollMs
                }
                is DebridState.Failed, DebridState.NotCached, null -> return null
            }
        }
    }

    private suspend fun download(provider: DebridProvider, ready: DebridState.Ready, folder: File, status: (Int, String) -> Unit, fetch: Fetch): String {
        val files = ready.files
        files.forEachIndexed { index, file ->
            val into = childOf(folder, file.path) ?: throw IllegalStateException("${provider.name} named a file outside the download")
            withContext(Dispatchers.IO) { into.parentFile?.mkdirs() }
            fetch.file(file, into, "via ${provider.name} (cached): file ${index + 1} of ${files.size}")
        }
        status(100, "Downloaded via ${provider.name}")
        return provider.name
    }

    /** [path] inside [folder], or null when it would leave it (an absolute path, "..", an empty name). Pure. */
    fun childOf(folder: File, path: String): File? {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.fold(folder) { dir, part -> File(dir, part) }
    }

    /** A provider's answer, a network failure being a failed state and never an exception across the job. */
    private suspend fun <T> ask(block: () -> T): T? = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: IOException) {
            null
        } catch (e: org.json.JSONException) {
            null
        }
    }

    /** How long a service that said "cached" may take to hand the links over. */
    private const val CACHED_WAIT_MS = 2L * 60 * 1000
}
