package dev.droidtop.stores.legacy

import android.content.Context
import dev.droidtop.library.credentials.CredentialStore
import dev.droidtop.library.credentials.CredentialVault
import dev.droidtop.library.stores.AccountSignInStep
import dev.droidtop.library.stores.StoreAccountSignIn
import dev.droidtop.library.stores.StoreGame
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.stores.StoreProgress
import dev.droidtop.library.stores.StoreSignIn
import dev.droidtop.library.stores.StoreSignInKind
import dev.droidtop.library.stores.StoreSyncs
import dev.droidtop.net.AtomicWrite
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.runtime.SafeDelete
import dev.droidtop.runtime.util.runCatchingCancellable
import dev.droidtop.stores.util.SingleFileInstall
import dev.droidtop.stores.util.StoreFiles
import dev.droidtop.stores.util.newDownloadInfo
import dev.droidtop.stores.util.runJobDownload
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Legacy Games as a [StoreLibrary] (docs/SPEC.md 7g, "Stores", Droidtop/tracker#335; the owner, 2026-11-05: "include
 * Legacy Games, with the password in the encrypted vault"): the account's purchases and giveaways, each installed as
 * one file the way itch.io's and Humble's are ([SingleFileInstall]; Legacy's are Windows installers, kept in the game's
 * folder for Wine to run). Its API asks for the account's e-mail and password with every request, so droidtop keeps
 * both, sealed in a vault of the store's own ([CredentialStore.vaultFor]), until the person signs out.
 */
class LegacyStore : StoreLibrary {
    override val id = "legacy"
    override val label = "Legacy Games"
    override val siteUrl = "https://legacygames.com/"

    override val webPages = dev.droidtop.library.stores.StoreWebPages(
        home = "https://legacygames.com/",
        hosts = listOf("legacygames.com"),
        searchPage = "https://legacygames.com/?s=",
    )

    override val signInKind = StoreSignInKind.ACCOUNT

    private fun vault(context: Context): CredentialVault = CredentialStore.vaultFor(context, "droidtop_legacy_games", "droidtop_legacy_games_v1")
    private fun dir(context: Context) = File(context.filesDir, "legacy")
    private fun libraryFile(context: Context) = File(dir(context), "library.json")
    private fun accountFile(context: Context) = File(dir(context), "account.json")

    /** The kept account: e-mail, password (both from the vault) and Legacy's user id. */
    internal data class Account(val email: String, val password: String, val userId: String)

    private fun account(context: Context): Account? {
        val vault = vault(context)
        val email = vault.get(EMAIL).takeIf { it.isNotBlank() } ?: return null
        val password = vault.get(PASSWORD).takeIf { it.isNotBlank() } ?: return null
        val userId = accountFile(context).takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()).optString("userId") }.getOrNull() }
            ?.takeIf { it.isNotBlank() } ?: return null
        return Account(email, password, userId)
    }

    override fun signedIn(context: Context): Boolean = accountFile(context).isFile

    override fun accountName(context: Context): String? =
        accountFile(context).takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()).optString("email") }.getOrNull() }?.takeIf { it.isNotBlank() }

    override fun signIn(context: Context): StoreSignIn = StoreSignIn.Account(LegacySignIn(context.applicationContext, this))

    override suspend fun completeSignIn(context: Context, secret: String): Result<String?> =
        Result.failure(UnsupportedOperationException("Legacy Games signs in on its own screen"))

    /** Checks [email] and [password] with Legacy Games and keeps them sealed when they work; the account's name. */
    internal suspend fun keep(context: Context, email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val userId = LegacyApi.userId(email, password)
            vault(context).put(EMAIL, email)
            vault(context).put(PASSWORD, password)
            AtomicWrite.text(accountFile(context), JSONObject().put("userId", userId).put("email", email).toString())
            email
        }.also { result ->
            if (result.isSuccess) StoreSyncs.run(context, this@LegacyStore).onFailure { Timber.tag(TAG).w(it, "First Legacy Games library read failed") }
        }
    }

    override suspend fun signOut(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            vault(context).remove(EMAIL)
            vault(context).remove(PASSWORD)
            accountFile(context).delete()
            write(context, read(context).filter { it.installed })
        }
    }

    /** One row of the kept library: the game, and where and whether it is installed. */
    internal data class Row(val game: LegacyApi.Game, val installPath: String = "", val installed: Boolean = false)

    override suspend fun sync(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val account = account(context) ?: error("Not signed in to Legacy Games")
            val games = LegacyApi.games(account.email, account.password, account.userId)
            val known = read(context).associateBy { it.game.gameId }
            val rows = games.map { g -> known[g.gameId]?.copy(game = g) ?: Row(g) }
            write(context, rows + known.values.filter { it.installed && games.none { g -> g.gameId == it.game.gameId } })
            rows.size
        }.also { result -> if (result.exceptionOrNull() is LegacyApi.SignedOut) accountFile(context).delete() }
    }

    override suspend fun games(context: Context): List<StoreGame> = withContext(Dispatchers.IO) {
        read(context).map { row ->
            StoreGame(
                store = id,
                gameId = row.game.gameId,
                title = row.game.title,
                installed = row.installed,
                installPath = row.installPath.takeIf { row.installed && it.isNotBlank() },
                sizeBytes = LegacyApi.bytesOf(row.game.sizeText),
                artUrl = row.game.coverUrl,
            )
        }
    }

    override suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress): String {
        val account = withContext(Dispatchers.IO) { account(context) } ?: error("Sign in to Legacy Games under Stores first")
        val row = withContext(Dispatchers.IO) { read(context).firstOrNull { it.game.gameId == gameId } } ?: error("Legacy Games no longer lists this game")
        progress.report(-1f, "Asking Legacy Games for the installer")
        val url = withContext(Dispatchers.IO) { LegacyApi.installerUrl(account.email, account.password, row.game) }
        val folder = File(row.installPath.ifBlank { StoreFiles.freshFolder(root, row.game.title, "legacy-$gameId").absolutePath })
        update(context, gameId) { it.copy(installPath = folder.absolutePath) }
        val name = url.substringBefore('?').substringAfterLast('/').ifBlank { "setup.exe" }
        val info = newDownloadInfo()
        runJobDownload(info, progress) {
            SingleFileInstall.run(context, SingleFileInstall.areaName(id, gameId), url, name, LegacyApi.bytesOf(row.game.sizeText), folder, info)
        }.getOrElse { throw it }
        update(context, gameId) { it.copy(installed = true, installPath = folder.absolutePath) }
        return "Installed ${row.game.title}"
    }

    override suspend fun discardPartial(context: Context, gameId: String) {
        withContext(Dispatchers.IO) {
            val row = read(context).firstOrNull { it.game.gameId == gameId } ?: return@withContext
            DownloadJobs.discardForJob(context, SingleFileInstall.areaName(id, gameId))
            if (row.installed || row.installPath.isBlank()) return@withContext
            val folder = File(row.installPath)
            folder.parentFile?.let { SafeDelete.deleteWithin(it, folder) }
            update(context, gameId) { it.copy(installPath = "") }
        }
    }

    override suspend fun uninstall(context: Context, gameId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val row = read(context).firstOrNull { it.game.gameId == gameId } ?: return@withContext Result.failure(IllegalStateException("Legacy Games no longer lists this game"))
        runCatchingCancellable {
            if (row.installPath.isNotBlank()) {
                val folder = File(row.installPath)
                val parent = folder.parentFile ?: error("${row.installPath} is not a game folder")
                check(SafeDelete.deleteWithin(parent, folder)) { "Could not remove ${row.installPath}" }
            }
            update(context, gameId) { it.copy(installed = false, installPath = "") }
        }
    }

    override fun changeStamp(context: Context): Long = StoreFiles.stamp(listOf(accountFile(context), libraryFile(context)))

    @Synchronized
    private fun update(context: Context, gameId: String, change: (Row) -> Row) {
        write(context, read(context).map { if (it.game.gameId == gameId) change(it) else it })
    }

    private fun read(context: Context): List<Row> = libraryFile(context).takeIf { it.isFile }?.let { decode(it.readText()) }.orEmpty()

    private fun write(context: Context, rows: List<Row>) {
        AtomicWrite.text(libraryFile(context), encode(rows))
    }

    internal companion object {
        private const val TAG = "LegacyStore"
        private const val EMAIL = "legacy_email"
        private const val PASSWORD = "legacy_password"

        fun encode(rows: List<Row>): String = JSONArray(rows.map { row ->
            val g = row.game
            JSONObject().put("gameId", g.gameId).put("title", g.title).put("productId", g.productId ?: "").put("installerUuid", g.installerUuid ?: "")
                .put("giveaway", g.giveaway).put("cover", g.coverUrl ?: "").put("size", g.sizeText)
                .put("installPath", row.installPath).put("installed", row.installed)
        }).toString()

        fun decode(text: String): List<Row> = runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Row(
                    LegacyApi.Game(
                        gameId = o.getString("gameId"),
                        title = o.optString("title", o.getString("gameId")),
                        productId = o.optString("productId", "").ifBlank { null },
                        installerUuid = o.optString("installerUuid", "").ifBlank { null },
                        giveaway = o.optBoolean("giveaway", false),
                        coverUrl = o.optString("cover", "").ifBlank { null },
                        sizeText = o.optString("size", ""),
                    ),
                    installPath = o.optString("installPath", ""),
                    installed = o.optBoolean("installed", false),
                )
            }
        }.getOrDefault(emptyList())
    }
}

/** Legacy Games' sign-in on droidtop's own screen: an e-mail and password, checked with Legacy Games and kept sealed. */
internal class LegacySignIn(private val context: Context, private val store: LegacyStore) : StoreAccountSignIn {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStep = MutableStateFlow<AccountSignInStep>(AccountSignInStep.Connecting)
    private var attempt: Job? = null

    override val step: StateFlow<AccountSignInStep> get() = mutableStep
    override val offersQrCode: Boolean get() = false
    override val keepsPassword: Boolean get() = true
    override val accountLabel: String get() = "E-mail"

    override fun start() {
        mutableStep.value = AccountSignInStep.Choose()
    }

    override fun showQrCode() = Unit

    override fun signInWithPassword(account: String, password: String) {
        attempt?.cancel()
        mutableStep.value = AccountSignInStep.Working
        attempt = scope.launch {
            store.keep(context, account.trim(), password).fold(
                onSuccess = { mutableStep.value = AccountSignInStep.Done(it) },
                onFailure = { e ->
                    val why = if (e is LegacyApi.SignedOut) "Legacy Games did not accept that e-mail and password" else e.message ?: "Legacy Games could not be reached"
                    mutableStep.value = AccountSignInStep.Choose(why)
                },
            )
        }
    }

    override fun submitCode(code: String) = Unit

    override fun backToChoices() {
        attempt?.cancel()
        mutableStep.value = AccountSignInStep.Choose()
    }

    override fun close() {
        attempt?.cancel()
    }
}
