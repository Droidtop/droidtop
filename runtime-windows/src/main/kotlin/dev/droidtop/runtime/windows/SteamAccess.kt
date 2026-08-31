package dev.droidtop.runtime.windows

import android.content.Context
import android.content.Intent
import app.gamenative.PluviaApp
import app.gamenative.enums.LoginResult
import app.gamenative.events.SteamEvent
import app.gamenative.service.SteamService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator

/**
 * droidtop's OWN surface over the vendored gamenative Steam backbone
 * (per direction: gamenative's UI stays out of the way entirely; its
 * SERVICES are the integration). One facade, so droidtop UI code never
 * touches [SteamService]'s wide companion API or the event dispatcher
 * directly.
 *
 * Login is the user's act in every path: QR (scanned or handed to the
 * Steam app on this device via its own s.team challenge URL) or a
 * credentials form the USER types into. droidtop stores nothing itself;
 * session persistence is gamenative's own remembered-session machinery.
 */
object SteamAccess {

    sealed interface Phase {
        data object Idle : Phase
        data object Connecting : Phase
        data object Connected : Phase
        data class QrReady(val challengeUrl: String) : Phase
        data class AwaitingCode(val viaEmail: Boolean, val previousIncorrect: Boolean) : Phase
        data object AwaitingDeviceConfirm : Phase
        data class LoggedIn(val username: String?) : Phase
        data class Failed(val message: String?) : Phase
    }

    private val mutablePhase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = mutablePhase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val codeChannel = Channel<String>(Channel.CONFLATED)

    @Volatile
    private var listenersBound = false

    /** A minimal owned-library row for droidtop's own UI. */
    data class OwnedGame(val appId: Int, val name: String, val installed: Boolean)

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SteamDbEntryPoint {
        fun steamAppDao(): app.gamenative.db.dao.SteamAppDao
    }

    /**
     * Starts (or reuses) the real [SteamService] foreground service and
     * binds the event listeners exactly once. Call from a foreground
     * surface only; the service connects to Steam on its own and a
     * [SteamEvent.Connected] moves [phase] forward.
     */
    fun ensureRunning(context: Context) {
        bindListeners()
        if (mutablePhase.value == Phase.Idle) mutablePhase.value = Phase.Connecting
        context.startForegroundService(Intent(context, SteamService::class.java))
    }

    fun isLoggedIn(): Boolean = SteamService.isLoggedIn

    fun startQrLogin() {
        scope.launch { SteamService.startLoginWithQr() }
    }

    fun cancelQrLogin() {
        SteamService.stopLoginWithQr()
    }

    /**
     * Credentials the USER just typed in droidtop's own login form.
     * Steam Guard steps surface as [Phase.AwaitingCode] (answer with
     * [submitTwoFactorCode]) or [Phase.AwaitingDeviceConfirm] (approved
     * in the Steam mobile app, no code at all).
     */
    fun loginWithCredentials(username: String, password: String) {
        mutablePhase.value = Phase.Connecting
        scope.launch {
            SteamService.startLoginWithCredentials(
                username = username,
                password = password,
                rememberSession = true,
                authenticator = authenticator,
            )
        }
    }

    fun submitTwoFactorCode(code: String) {
        mutablePhase.value = Phase.Connecting
        codeChannel.trySend(code.trim())
    }

    fun logOut() {
        SteamService.logOut()
    }

    suspend fun ownedGames(context: Context): List<OwnedGame> {
        val dao = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SteamDbEntryPoint::class.java,
        ).steamAppDao()
        return dao.getAllOwnedAppsAsList().map { steamApp ->
            OwnedGame(
                appId = steamApp.id,
                name = steamApp.name,
                installed = SteamService.isAppInstalled(steamApp.id),
            )
        }
    }

    /** Fire-and-track: returns false when the service refused (not logged in, already downloading). */
    fun startDownload(appId: Int, onProgress: (Float) -> Unit): Boolean {
        val info = SteamService.getAppDownloadInfo(appId) ?: SteamService.downloadApp(appId) ?: return false
        info.addProgressListener(onProgress)
        return true
    }

    fun installedDirFor(appId: Int): File? =
        runCatching { File(SteamService.getAppDirPath(appId)) }.getOrNull()?.takeIf { it.isDirectory }

    /**
     * Every Steam `steamapps/common` directory games can be installed
     * under, existing ones only. This is what droidtop's engine-game
     * scan consumes so a Steam-installed Ren'Py/RPG Maker/KiriKiri game
     * flows through the SAME detection, grouping, and launch-strategy
     * resolution as one in a games folder, enginehost included.
     */
    fun installRoots(): List<File> =
        runCatching { SteamService.allInstallPaths }.getOrDefault(emptyList())
            .map(::File)
            .filter { it.isDirectory }

    private fun bindListeners() {
        if (listenersBound) return
        listenersBound = true
        PluviaApp.events.on<SteamEvent.Connected, Unit> {
            if (mutablePhase.value is Phase.Connecting || mutablePhase.value is Phase.Idle) {
                mutablePhase.value = if (SteamService.isLoggedIn) Phase.LoggedIn(null) else Phase.Connected
            }
        }
        PluviaApp.events.on<SteamEvent.QrChallengeReceived, Unit> { event ->
            mutablePhase.value = Phase.QrReady(event.challengeUrl)
        }
        PluviaApp.events.on<SteamEvent.QrAuthEnded, Unit> { event ->
            if (!event.success && mutablePhase.value is Phase.QrReady) {
                mutablePhase.value = Phase.Failed(event.message ?: "QR sign-in ended")
            }
        }
        PluviaApp.events.on<SteamEvent.LogonEnded, Unit> { event ->
            mutablePhase.value = when (event.loginResult) {
                LoginResult.Success -> Phase.LoggedIn(event.username)
                LoginResult.InProgress -> mutablePhase.value
                else -> Phase.Failed(event.message ?: event.loginResult.name)
            }
        }
        PluviaApp.events.on<SteamEvent.LoggedOut, Unit> {
            mutablePhase.value = Phase.Connected
        }
        PluviaApp.events.on<SteamEvent.Disconnected, Unit> {
            mutablePhase.value = Phase.Idle
        }
    }

    private val authenticator = object : IAuthenticator {
        override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
            mutablePhase.value = Phase.AwaitingDeviceConfirm
            return CompletableFuture.completedFuture(true)
        }

        override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
            mutablePhase.value = Phase.AwaitingCode(viaEmail = false, previousIncorrect = previousCodeWasIncorrect)
            return awaitCode()
        }

        override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
            mutablePhase.value = Phase.AwaitingCode(viaEmail = true, previousIncorrect = previousCodeWasIncorrect)
            return awaitCode()
        }

        private fun awaitCode(): CompletableFuture<String> {
            val future = CompletableFuture<String>()
            scope.launch { future.complete(codeChannel.receive()) }
            return future
        }
    }
}
