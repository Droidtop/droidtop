package dev.droidtop.library.stores

import android.content.Context
import android.util.Log
import dev.droidtop.pluginhost.PluginJobsCenter
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Cloud saves of a store's games (docs/SPEC.md 7g, "Stores", Droidtop/tracker#313).
 * A store that keeps a game's saves in its own cloud (Steam Cloud) syncs them
 * into the game's Wine prefix before the game starts and back after it ends.
 * The store knows the cloud; where the prefix is comes from the Windows
 * runtime through [WinePrefixLocator], an interface so the store never
 * depends on a runtime's classes (the runtime is being moved out of
 * GameNative); the questions to the person go through [SaveConflictResolver],
 * which the Gaming shell fills with its dialog.
 */

/** Where a game's Wine prefix is on this device: [prefixDir] is the folder that holds `drive_c`, [user] the Windows user name inside it. */
data class WinePrefixLocation(val prefixDir: File, val user: String)

/** The Windows runtime's answer to "where is the prefix this game runs in". Disk work; never on the main thread. */
fun interface WinePrefixLocator {
    fun locate(context: Context, entryId: String): WinePrefixLocation?
}

/** When a sync runs: before a game starts, after it ends, or because the person asked. */
enum class SaveSyncPhase { BEFORE_LAUNCH, AFTER_EXIT, MANUAL }

/** One side of a conflict: when it last changed, and how much is on it. */
data class SaveSide(val timestampMs: Long, val files: Int, val bytes: Long)

/** Both sides changed since they last matched; the person picks which one wins. */
data class SaveConflict(val local: SaveSide, val cloud: SaveSide, val cloudLabel: String = "Cloud")

enum class SaveChoice { LOCAL, CLOUD }

/** Asks the person which side wins; null means "not now" and nothing changes. */
fun interface SaveConflictResolver {
    suspend fun resolve(gameTitle: String, conflict: SaveConflict): SaveChoice?
}

/** What a sync did, as the one line the screens show. */
data class SaveSyncResult(
    val line: String,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val removed: Int = 0,
    val failed: Boolean = false,
    /** A conflict nobody settled: nothing was changed. */
    val unresolved: Boolean = false,
)

/**
 * The entry point the runtime and the screens use: it finds the store that
 * owns a game and the prefix the game runs in, and calls the store. Nothing
 * here is Steam-specific.
 */
object StoreSaves {
    /** The Windows runtime's locator; set once at process start. */
    @Volatile
    var locator: WinePrefixLocator? = null

    /**
     * The Gaming shell's dialog. Without one (another shell launched the game)
     * a conflict is left as it is and the game starts on its local files.
     */
    @Volatile
    var resolver: SaveConflictResolver? = null

    /** How long a sync may hold a launch back before the game starts anyway. */
    const val LAUNCH_WAIT_MS = 45_000L

    const val JOB_KIND = "store_saves"
    private const val ARG_KEY = "key"
    private const val ARG_TITLE = "title"
    private const val TAG = "droidtop.StoreSaves"

    /** The names of the games launched this run, so the job after one ends can be titled. */
    private val titles = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Brings the game's saves up to date before it starts. The line to show
     * when something happened or went wrong, else null. Never throws. Gives up
     * after [LAUNCH_WAIT_MS]: a game is not held back by the network. Unless a
     * person is answering a question by then ([SaveConflictPrompts]): they are
     * waited for.
     */
    suspend fun beforeLaunch(context: Context, entryId: String, title: String): String? = coroutineScope {
        titles[entryId] = title
        val work = async { sync(context, entryId, title, SaveSyncPhase.BEFORE_LAUNCH) }
        val finished = withTimeoutOrNull(LAUNCH_WAIT_MS) { work.join(); true } ?: false
        if (!finished) {
            if (SaveConflictPrompts.pending.value != null) {
                work.join()
            } else {
                work.cancel()
                return@coroutineScope "Cloud saves took too long, so the game started on this device's files"
            }
        }
        val done = work.await() ?: return@coroutineScope null
        done.line.takeIf { done.failed || done.unresolved || done.downloaded + done.uploaded + done.removed > 0 }
    }

    /**
     * Runs one sync for [entryId]: null when its store keeps no cloud saves or
     * the game's prefix is not there. Disk and network work, dispatched off the
     * caller's thread.
     */
    suspend fun sync(context: Context, entryId: String, title: String, phase: SaveSyncPhase): SaveSyncResult? = withContext(Dispatchers.IO) {
        val store = StoreLibraries.forKey(entryId)?.takeIf { it.hasCloudSaves } ?: return@withContext null
        val prefix = locator?.locate(context, entryId) ?: return@withContext null
        runCatching { store.syncSaves(context, entryId.substringAfter(':'), phase, prefix, title, resolver) }
            .onFailure { Log.w(TAG, "Cloud save sync failed for $entryId", it) }
            .getOrElse { SaveSyncResult("Cloud saves: ${it.message ?: it.javaClass.simpleName}", failed = true) }
    }

    /**
     * After the game ended: uploads what changed, as a job in the Downloads
     * place so it survives the game's screen closing and shows what it did.
     */
    fun afterExit(context: Context, entryId: String, title: String = titles[entryId] ?: entryId.substringAfter(':')) {
        val store = StoreLibraries.forKey(entryId)?.takeIf { it.hasCloudSaves } ?: return
        PluginJobsCenter.startNative(context, JOB_KIND, "Saves: $title", mapOf(ARG_KEY to entryId, ARG_TITLE to title), owner = store.label, pausable = false)
    }

    /** Registers the job runner; called once at process start with the store jobs. */
    fun register(context: Context) {
        val app = context.applicationContext
        PluginJobsCenter.registerNative(JOB_KIND) { args, _, report ->
            val key = args[ARG_KEY] ?: error("This save sync names no game")
            report(-1, "Syncing saves", key)
            sync(app, key, args[ARG_TITLE] ?: key.substringAfter(':'), SaveSyncPhase.AFTER_EXIT)?.line ?: "No cloud saves"
        }
    }
}

/**
 * The question on screen: one conflict at a time, asked through the shell's
 * dialog. The shell shows [pending] and answers it; a launch that asks waits
 * on [ask] until it is answered.
 */
object SaveConflictPrompts : SaveConflictResolver {
    class Prompt(val title: String, val conflict: SaveConflict, internal val answer: CompletableDeferred<SaveChoice?>) {
        fun settle(choice: SaveChoice?) {
            answer.complete(choice)
        }
    }

    private val pendingPrompt = MutableStateFlow<Prompt?>(null)

    /** The question the shell should show, or null. */
    val pending: StateFlow<Prompt?> get() = pendingPrompt

    override suspend fun resolve(gameTitle: String, conflict: SaveConflict): SaveChoice? {
        val prompt = Prompt(gameTitle, conflict, CompletableDeferred())
        pendingPrompt.update { prompt }
        try {
            return prompt.answer.await()
        } finally {
            pendingPrompt.update { if (it === prompt) null else it }
        }
    }
}
