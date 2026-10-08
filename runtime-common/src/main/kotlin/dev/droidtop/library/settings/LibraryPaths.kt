package dev.droidtop.library.settings

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Files and folders that were added, removed or changed, by absolute path:
 * what a host-run job or a plugin tells the library so that it looks at
 * exactly those and walks nothing else (docs/SPEC.md 7g, "Targeted
 * indexing").
 */
@Serializable
data class PathChange(
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val changed: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()

    /** Both changes in one, each path once per kind. */
    operator fun plus(other: PathChange): PathChange = PathChange(
        added = (added + other.added).distinct(),
        removed = (removed + other.removed).distinct(),
        changed = (changed + other.changed).distinct(),
    )

    companion object {
        fun added(vararg paths: File): PathChange = PathChange(added = paths.map { it.absolutePath })
        fun removed(vararg paths: File): PathChange = PathChange(removed = paths.map { it.absolutePath })
    }
}

/**
 * The reports that have not been indexed yet, kept in a file so that one
 * made by a job that finished while the process was dying, or before the
 * library was ready, is still there at the next start.
 */
class PendingPathChanges(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PathChange.serializer())

    @Synchronized
    fun all(): List<PathChange> = read()

    @Synchronized
    fun add(change: PathChange) {
        write((read() + change).takeLast(MAX_PENDING))
    }

    /** Forgets the first [count] reports: the ones a drain has just indexed. */
    @Synchronized
    fun drop(count: Int) {
        val rest = read().drop(count)
        if (rest.isEmpty()) file.delete() else write(rest)
    }

    private fun read(): List<PathChange> =
        runCatching { if (file.isFile) json.decodeFromString(serializer, file.readText()) else emptyList() }.getOrDefault(emptyList())

    private fun write(changes: List<PathChange>) {
        file.parentFile?.mkdirs()
        val staged = File(file.parentFile, file.name + ".tmp")
        staged.writeText(json.encodeToString(serializer, changes))
        if (!staged.renameTo(file)) {
            file.delete()
            staged.renameTo(file)
        }
    }

    companion object {
        /** A queue that has grown past this has a handler that never succeeds; the oldest reports go first. */
        const val MAX_PENDING = 1_000
    }
}

/**
 * Takes reports of changed paths and hands them to the library, one batch at
 * a time on [scope]. A report is written to [pending] before anything else
 * happens, and is removed only after the handler returned for it, so a
 * report is never lost to a crash, to the library not being ready yet, or to
 * a handler that failed: the next [install] or [report] tries again.
 */
class PathReporter(private val pending: PendingPathChanges, private val scope: CoroutineScope) {
    private val gate = Mutex()

    @Volatile
    private var handler: (suspend (PathChange) -> Unit)? = null

    /**
     * Writes [change] down and starts indexing it. Blocks only for the file
     * write, so it is for threads that may do IO. The returned job ends when
     * the queue is empty or the handler failed.
     */
    fun report(change: PathChange): Job? {
        if (change.isEmpty) return null
        pending.add(change)
        return drain()
    }

    /** Sets what indexes a batch and indexes whatever is already waiting: the start-up reconciliation. */
    fun install(handler: suspend (PathChange) -> Unit): Job {
        this.handler = handler
        return drain()
    }

    private fun drain(): Job = scope.launch {
        gate.withLock {
            val handle = handler ?: return@withLock
            while (true) {
                val batch = pending.all()
                if (batch.isEmpty()) return@withLock
                try {
                    handle(batch.reduce { all, next -> all + next })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Throwable) {
                    return@withLock
                }
                pending.drop(batch.size)
            }
        }
    }
}

/**
 * "These files changed": the one door every producer of a file the library
 * should know about goes through (docs/SPEC.md 7g, "Targeted indexing").
 * A download that placed a game, a store install or removal, and a plugin
 * that put a file in a game folder (`library.files_changed`,
 * docs/plugin-api.md 3 A3) all call [report]; none of them rescans the
 * library or waits for a screen to be open.
 *
 * The library lives in :app, which this module cannot depend on, so :app
 * calls [install] at process start with what indexes a batch (the library's
 * `indexPaths`), exactly as it registers [LibraryRescan.handler]. Reports
 * made before that, or left over from a process that ended before they
 * were indexed, wait in a file and are indexed by [install].
 */
object LibraryPaths {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var reporter: PathReporter? = null

    /**
     * The folders a report may name, as absolute paths: :app sets this to the
     * person's game folders. A plugin's report outside them is refused
     * ([outside]); a host-run job's own paths are inside them by
     * construction.
     */
    @Volatile
    var roots: () -> List<String> = { emptyList() }

    private fun reporterFor(context: Context): PathReporter = reporter ?: synchronized(this) {
        reporter ?: PathReporter(
            PendingPathChanges(File(context.applicationContext.filesDir, "library/pending_paths.json")),
            scope,
        ).also { reporter = it }
    }

    /** Sets the indexer and indexes everything that was reported while there was none. Called once at process start. */
    fun install(context: Context, indexer: suspend (PathChange) -> Unit): Job = reporterFor(context).install(indexer)

    private val reportedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Reports [change]; see [PathReporter.report]. Never call it from the main thread. [source] names a plugin
     * that made the report, so a caller that wants to know whether it has told the library ([reportedSince])
     * can ask.
     */
    fun report(context: Context, change: PathChange, source: String? = null): Job? {
        if (source != null && !change.isEmpty) reportedAt[source] = System.currentTimeMillis()
        return reporterFor(context).report(change)
    }

    /** Whether [source] reported something at or after [sinceMs] (epoch milliseconds) in this process. */
    fun reportedSince(source: String, sinceMs: Long): Boolean = (reportedAt[source] ?: 0L) >= sinceMs

    /**
     * The members of [paths] that are not inside any of [roots]. A path that is not absolute, or that climbs out with `..`,
     * is outside whatever the roots are. A root itself is outside too: a report names what was put in a game folder,
     * and a whole game folder is the full rescan this exists to avoid.
     */
    fun outside(paths: List<String>, roots: List<String>): List<String> {
        val bases = roots.map { it.trimEnd('/') }.filter { it.isNotEmpty() }
        return paths.filter { path ->
            val clean = path.trimEnd('/')
            val segments = clean.split('/')
            val unsafe = !clean.startsWith("/") || segments.any { it == ".." || it == "." }
            unsafe || bases.none { base -> clean.startsWith("$base/") }
        }
    }
}
