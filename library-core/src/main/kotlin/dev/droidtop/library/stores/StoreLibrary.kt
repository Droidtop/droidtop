package dev.droidtop.library.stores

import android.content.Context
import android.util.Log
import dev.droidtop.library.StoreUpdate
import dev.droidtop.library.settings.LibraryRescan
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One game a store says the person owns: Playnite's `GameMetadata`, a row
 * of Lutris' `service_games` table (docs/SPEC.md 7g, "Stores").
 *
 * Its identity is the pair ([store], [gameId]) and nothing else: two stores
 * never share an id space, and a store's own id is the only handle back to
 * its row, its install and its sign-in. Which rows of different stores are
 * one game is not this row's question; [dev.droidtop.library.StoreIdentity]
 * answers it over every row at once.
 */
data class StoreGame(
    /** The store's short id ([StoreLibrary.id]), the half before the colon of [key]. */
    val store: String,
    /** The store's own id for the game, unprefixed. */
    val gameId: String,
    val title: String,
    val installed: Boolean,
    /** Where the game is (installed) or would go (not installed), when the store says. */
    val installPath: String?,
    /** On-disk size when installed, download size when not, 0 when the store does not say. */
    val sizeBytes: Long,
    /** The store's own cover or icon URL, when it has one. */
    val artUrl: String?,
    /** The installed build as the store words it; null when the store names none. */
    val installedVersion: String? = null,
    /**
     * Other stores' ids for this same game that the store's own data names,
     * keyed by store id ("steam" to "440"). The strongest evidence that two
     * rows are one game ([dev.droidtop.library.StoreIdentity]); empty when
     * the store names none, which is the case for every store droidtop reads
     * today.
     */
    val externalIds: Map<String, String> = emptyMap(),
) {
    /** The id the library knows this row by: `"gog:1207658691"`. */
    val key: String get() = "$store:$gameId"
}

/**
 * How a store signs a person in. droidtop never sees a password: either the
 * store's own page is shown and droidtop reads the one-time code the store
 * hands back on the page it returns to, or the person makes a key on the
 * store's site and pastes it.
 */
sealed interface StoreSignIn {
    /**
     * The store's own sign-in page at [url]. [isReturnPage] says whether a
     * page the web view reached is the one the store returns to after a
     * sign-in; [codeInUrl] reads the code off that page's address. A store
     * that writes the code into the page's body instead ([codeInBody]) has
     * it read from the body's JSON field of that name.
     */
    class WebPage(
        val url: String,
        val isReturnPage: (String) -> Boolean,
        val codeInUrl: (String) -> String?,
        val codeInBody: String? = null,
    ) : StoreSignIn

    /** A personal key the person makes at [keyPage] and pastes into droidtop. */
    class ApiKey(val keyPage: String) : StoreSignIn
}

/** Which of the two [StoreSignIn] forms a store uses, known without starting a sign-in. */
enum class StoreSignInKind { WEB_PAGE, API_KEY }

/** What a store answers about a newer build of one installed game. */
data class StoreUpdateCheck(val update: StoreUpdate, val latest: String? = null)

/** How a store itself starts one of its games, when its own data says. */
data class StoreLaunch(val executable: File, val workingDir: File, val arguments: List<String> = emptyList())

/** Progress of a store job, as the Downloads place shows it. */
fun interface StoreProgress {
    /** [fraction] 0 to 1, or a negative number while the size is not known yet. */
    fun report(fraction: Float, line: String)
}

/**
 * A store droidtop reads a library from and installs games out of: the one
 * interface for every store, built in or plugged in (Playnite's
 * `LibraryPlugin`, Lutris' `OnlineService`; docs/SPEC.md 7g, "Stores").
 *
 * Every call that touches the network or the disk suspends and does its own
 * IO dispatching; none of it may run on the main thread.
 *
 * What it does NOT do, on purpose: draw anything. Sign-in, the store page,
 * install, verify and remove are droidtop's own screens (the Stores place,
 * the game page and menu, the Downloads place), which call this. A store
 * that cannot do something says so through the defaults below instead of a
 * screen of its own.
 */
interface StoreLibrary {
    /** The store's short id, the store half of an entry id ("gog"). */
    val id: String

    /** The store's name as the library shows it ([dev.droidtop.library.PcStoreNames]). */
    val label: String

    /** Whether this device holds a sign-in for the store. A file check, cheap enough for a row. */
    fun signedIn(context: Context): Boolean

    /** Who is signed in, where the store keeps that in the open; null otherwise. */
    fun accountName(context: Context): String? = null

    /** How the store signs in; a row's label and which screen opens. Starts nothing. */
    val signInKind: StoreSignInKind

    /**
     * Starts a sign-in: what to show the person. A web sign-in may make
     * one-time state here (a PKCE verifier), so this is called once per
     * sign-in screen, never to draw a row ([signInKind] is for that).
     */
    fun signIn(context: Context): StoreSignIn

    /**
     * Finishes a sign-in with what the person's sign-in produced (the code
     * the store's page handed back, or the pasted key). Success carries the
     * account's name when the store says it. Reads the library afterwards.
     */
    suspend fun completeSignIn(context: Context, secret: String): Result<String?>

    /** Forgets the sign-in and the games of this store that are not installed. Installed games stay. */
    suspend fun signOut(context: Context): Result<Unit>

    /** Reads the store's library again. Success carries how many games it holds. */
    suspend fun sync(context: Context): Result<Int>

    /** Every game of this store the device knows about, from droidtop's own copy. No network. */
    suspend fun games(context: Context): List<StoreGame>

    /**
     * Downloads and installs [gameId] (or updates it in place, when it is
     * installed) under [root], the folder the person picked for this store,
     * reporting through [progress]. Returns the one line the Downloads
     * place shows when it ends; throws with the reason when it fails. A
     * cancelled coroutine (Pause, Cancel) stops it at the next file and
     * keeps what is downloaded, so the next run continues from there.
     */
    suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress): String

    /** After a Cancel: removes what a download that never finished left behind. An installed game is never touched. */
    suspend fun discardPartial(context: Context, gameId: String) {}

    /** Removes an installed game's files and marks it not installed. */
    suspend fun uninstall(context: Context, gameId: String): Result<Unit>

    /** Whether [verify] can check an install against the store's own file list. */
    val canVerify: Boolean get() = false

    /** Checks the installed files against the store's list; the outcome line. */
    suspend fun verify(context: Context, gameId: String): Result<String> =
        Result.failure(UnsupportedOperationException("$label keeps no file list to check against"))

    /** The store's answer about a newer build, or null when it cannot be asked right now or has no check. */
    suspend fun checkUpdate(context: Context, gameId: String): StoreUpdateCheck? = null

    /** How the store itself starts [gameId], or null to let droidtop find the executable in the folder. */
    suspend fun launch(context: Context, gameId: String): StoreLaunch? = null

    /**
     * A number that changes whenever [games] would answer differently: the
     * PC library's store part is walked again only when it moves
     * (docs/SPEC.md 7g). One stat per file, never a query.
     */
    fun changeStamp(context: Context): Long
}

/**
 * The stores this build has, in the order the library lists them. Built-in
 * stores register at process start; a plugin store would register the same
 * way. Read by the PC library, the Stores place, the game page and the
 * install job, so each of those has one way to reach a store and no store
 * is special-cased by name.
 */
object StoreLibraries {
    @Volatile
    private var stores: List<StoreLibrary> = emptyList()

    /** Adds [store], replacing one with the same id. */
    @Synchronized
    fun register(store: StoreLibrary) {
        stores = stores.filterNot { it.id == store.id } + store
    }

    fun all(): List<StoreLibrary> = stores

    fun byId(id: String?): StoreLibrary? = id?.let { wanted -> stores.firstOrNull { it.id == wanted } }

    /** The store a library id ("gog:1207658691") belongs to, or null when no registered store owns it. */
    fun forKey(key: String?): StoreLibrary? = key?.substringBefore(':', "")?.takeIf { it.isNotEmpty() }?.let(::byId)
}

/**
 * Says that a store's rows changed (a sign-in, a sync, a sign-out, an
 * install, a removal): the library walks again in the background so the
 * change shows, the same "Rescan library" every other source change runs
 * ([LibraryRescan]). Returns at once; never on the caller's thread.
 */
object StoreChanges {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun announce(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { LibraryRescan.run(app) {} }
                .onFailure { Log.w("droidtop.Stores", "Walking the library after a store change failed", it) }
        }
    }
}
