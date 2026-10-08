package dev.droidtop.library.stores

import android.content.Context
import dev.droidtop.library.StoreUpdate
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.LibraryRescan
import dev.droidtop.library.social.SocialProvider
import java.io.File
import kotlinx.coroutines.flow.StateFlow

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
    /**
     * Playable through another account's licence (Steam Families), not owned:
     * listed apart, as the store's family ([PcStoreNames.family]).
     */
    val familyShared: Boolean = false,
) {
    /** The id the library knows this row by: `"gog:1207658691"`. */
    val key: String get() = "$store:$gameId"
}

/**
 * How a store signs a person in: the store's own page is shown and droidtop
 * reads the one-time code the store hands back on the page it returns to, or
 * the person makes a key on the store's site and pastes it, or (Steam)
 * droidtop's own screen walks the store's sign-in step by step. droidtop
 * keeps no password in any of them.
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

    /**
     * A sign-in droidtop's own screen walks through with the store, step by
     * step ([StoreAccountSignIn]): Steam, whose client signs in over its own
     * connection, by a QR code the store's phone app approves or by account
     * name and password with a Steam Guard code. The password goes from the
     * screen to the store's connection and is never kept.
     */
    class Account(val session: StoreAccountSignIn) : StoreSignIn
}

/** Which of the [StoreSignIn] forms a store uses, known without starting a sign-in. */
enum class StoreSignInKind { WEB_PAGE, API_KEY, ACCOUNT }

/** Where a step-by-step sign-in ([StoreAccountSignIn]) stands; the screen draws exactly this. */
sealed interface AccountSignInStep {
    /** Reaching the store. */
    data object Connecting : AccountSignInStep

    /** Connected: QR code, or account name and password. [failure] says why the last try did not work. */
    data class Choose(val failure: String? = null) : AccountSignInStep

    /** A QR code of [url] for the store's phone app to approve; the store may replace it while it waits. */
    data class QrCode(val url: String) : AccountSignInStep

    /** A code from the store's phone app, or sent by e-mail; [wrongBefore] when the last one was refused. */
    data class Code(val sentByEmail: Boolean, val wrongBefore: Boolean) : AccountSignInStep

    /** The store asked its phone app to approve this sign-in; nothing to type. */
    data object ApproveOnPhone : AccountSignInStep

    /** The store is checking what was given. */
    data object Working : AccountSignInStep

    /** Signed in as [account]. */
    data class Done(val account: String?) : AccountSignInStep
}

/**
 * One step-by-step sign-in ([StoreSignIn.Account]), made fresh for each
 * sign-in screen. Every call returns at once; what follows is reported
 * through [step]. None of the work runs on the caller's thread.
 */
interface StoreAccountSignIn {
    val step: StateFlow<AccountSignInStep>

    /** Connects to the store; [step] moves from [AccountSignInStep.Connecting] to [AccountSignInStep.Choose]. */
    fun start()

    /** Asks the store for a QR code to approve. */
    fun showQrCode()

    /** Signs in with what the person typed; the password is handed to the store and not kept. */
    fun signInWithPassword(account: String, password: String)

    /** Answers [AccountSignInStep.Code]. */
    fun submitCode(code: String)

    /** Back to [AccountSignInStep.Choose], dropping a QR code or a code request under way. */
    fun backToChoices()

    /** The screen is closing: stops whatever is under way. */
    fun close()
}

/** What a store answers about a newer build of one installed game. */
data class StoreUpdateCheck(val update: StoreUpdate, val latest: String? = null)

/** How a store itself starts one of its games, when its own data says. */
data class StoreLaunch(val executable: File, val workingDir: File, val arguments: List<String> = emptyList())

/** The account a store game is played as ([StoreLibrary.player]): its 64-bit id, its name, and the DLC ids of the game it has. */
data class StorePlayer(val steamId64: Long, val name: String, val dlc: Set<String>)

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
     * Where [gameId] is installed, or null when it is not. Reads the whole
     * library unless the store has a cheaper answer, so a caller asking for
     * one game on a launch path wants a store that overrides this.
     */
    suspend fun installedPath(context: Context, gameId: String): String? =
        games(context).firstOrNull { it.gameId == gameId && it.installed }?.installPath

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
     * What the person can choose about [gameId]'s content: which extras (DLC)
     * to have and which branch to follow, with sizes; null when the store has
     * nothing to choose (the default). Reads droidtop's own copy; no network.
     */
    suspend fun contentOptions(context: Context, gameId: String): StoreContentOptions? = null

    /**
     * Remembers [choice] for [gameId]; an install or update follows it from
     * then on. Success carries whether the installed files now differ from the
     * choice, so the caller starts the install job for the difference.
     */
    suspend fun chooseContent(context: Context, gameId: String, choice: StoreContentChoice): Result<Boolean> =
        Result.failure(UnsupportedOperationException("$label has no content to choose"))

    /** Checks [password] for the locked branch [branchId] of [gameId] with the store and keeps it when accepted. */
    suspend fun unlockBranch(context: Context, gameId: String, branchId: String, password: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("$label has no locked branches"))

    /** The store's friends and chat as a social provider (docs/SPEC.md "Social"), when it has them and stays connected while signed in (Steam); null otherwise. */
    val social: SocialProvider? get() = null

    /**
     * Rows the store adds to its page in the Stores place: its own settings
     * (the connection, cloud saves). Read off the main thread; empty for a
     * store with none (the default).
     */
    fun settingsItems(context: Context): List<CatalogItem> = emptyList()

    /**
     * Who plays [gameId] when it starts inside a Wine prefix with a stand-in
     * for the store's client (docs/SPEC.md 5b, "Steamworks in the prefix"):
     * the signed-in account and the DLC of the game it has installed. Null
     * when nobody is signed in or the store has no such client (the default).
     */
    suspend fun player(context: Context, gameId: String): StorePlayer? = null

    /** Whether the store keeps its games' saves in a cloud of its own that [syncSaves] reaches (Steam Cloud). */
    val hasCloudSaves: Boolean get() = false

    /**
     * Brings [gameId]'s saves in the Wine prefix at [prefix] and the store's
     * cloud to the same state, the way the store's own client does: this
     * device's newer files go up, the cloud's newer files come down, and when
     * both changed [onConflict] asks the person (null: leave both as they are).
     * Null when the store keeps no cloud saves for the game. Network and disk
     * work; the caller dispatches off the main thread.
     */
    suspend fun syncSaves(
        context: Context,
        gameId: String,
        phase: SaveSyncPhase,
        prefix: WinePrefixLocation,
        title: String,
        onConflict: SaveConflictResolver?,
    ): SaveSyncResult? = null

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
 * Says that a store's rows changed with no file to name (a sign-in, a sync, a
 * sign-out): the library walks again in the background so the change shows,
 * the same "Rescan library" every other source change runs ([LibraryRescan]).
 * Returns at once; never on the caller's thread. An install or a removal is
 * not this: its files are known, and it reports them
 * ([dev.droidtop.library.settings.LibraryPaths]) so nothing is walked.
 */
object StoreChanges {
    fun announce(context: Context) = LibraryRescan.requestInBackground(context)
}
