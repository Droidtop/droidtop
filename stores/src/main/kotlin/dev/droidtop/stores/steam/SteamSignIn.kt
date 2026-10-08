package dev.droidtop.stores.steam

import android.content.Context
import dev.droidtop.library.stores.AccountSignInStep
import dev.droidtop.library.stores.StoreAccountSignIn
import dev.droidtop.library.stores.StoreChanges
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthPollResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.authentication.IChallengeUrlChanged
import `in`.dragonbra.javasteam.steam.authentication.QrAuthSession
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Signing in to Steam on droidtop's own screen (docs/SPEC.md 7g, "Stores"):
 * GameNative's SteamService.startLoginWithQr and startLoginWithCredentials
 * with the Steam Guard steps of its authenticator (GPL-3.0), reporting each
 * step for the screen to draw instead of posting events. Either way Steam
 * hands back an account name and a refresh token; droidtop keeps those
 * ([SteamCredentials]), logs on with them, and reads the library. The
 * password goes to Steam and is not kept.
 */
internal class SteamSignIn(context: Context, private val store: SteamStore) : StoreAccountSignIn {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStep = MutableStateFlow<AccountSignInStep>(AccountSignInStep.Connecting)
    override val step: StateFlow<AccountSignInStep> = mutableStep

    private var attempt: Job? = null
    private val codes = Channel<String>(Channel.CONFLATED)
    private var holding = false

    override fun start() {
        if (holding) return
        holding = true
        SteamSession.acquire(app)
        attempt = scope.launch {
            mutableStep.value = AccountSignInStep.Connecting
            mutableStep.value = runCatching { SteamSession.connectedClient(app) }.fold(
                onSuccess = { AccountSignInStep.Choose() },
                onFailure = { AccountSignInStep.Choose(failure = it.message ?: "Steam could not be reached") },
            )
        }
    }

    override fun showQrCode() {
        replaceAttempt {
            val client = SteamSession.connectedClient(app)
            val details = AuthSessionDetails().apply {
                deviceFriendlyName = SteamSession.machineName(app)
                clientOSType = EOSType.WinUnknown
                persistentSession = true
            }
            val session = client.authentication.beginAuthSessionViaQR(details).await()
            // Steam replaces the code every so often; the screen draws the new one.
            session.challengeUrlChanged = object : IChallengeUrlChanged {
                override fun onChanged(qrAuthSession: QrAuthSession?) {
                    qrAuthSession?.let { mutableStep.value = AccountSignInStep.QrCode(it.challengeUrl) }
                }
            }
            mutableStep.value = AccountSignInStep.QrCode(session.challengeUrl)
            var result: AuthPollResult? = null
            val wait = (session.pollingInterval * 1000).toLong().coerceAtLeast(1000L)
            while (isActive && result == null) {
                result = session.pollAuthSessionStatus().await()
                if (result == null) delay(wait)
            }
            val answer = result ?: return@replaceAttempt
            finish(answer.accountName, answer.refreshToken, session.clientID)
        }
    }

    override fun signInWithPassword(account: String, password: String) {
        replaceAttempt {
            mutableStep.value = AccountSignInStep.Working
            val client = SteamSession.connectedClient(app)
            val details = AuthSessionDetails().apply {
                username = account.trim()
                // Not trimmed: a password may start or end with a space.
                this.password = password
                persistentSession = true
                authenticator = guard
                deviceFriendlyName = SteamSession.machineName(app)
                clientOSType = EOSType.WinUnknown
            }
            val session = client.authentication.beginAuthSessionViaCredentials(details).await()
            val answer = session.pollingWaitForResult().await()
            finish(answer.accountName, answer.refreshToken, session.clientID)
        }
    }

    override fun submitCode(code: String) {
        if (code.isBlank()) return
        mutableStep.value = AccountSignInStep.Working
        codes.trySend(code.trim())
    }

    override fun backToChoices() {
        attempt?.cancel()
        mutableStep.value = AccountSignInStep.Choose()
    }

    override fun close() {
        attempt?.cancel()
        scope.cancel()
        if (holding) {
            holding = false
            SteamSession.release()
        }
    }

    /** Ends whatever try is under way and starts [block]; a failure goes back to the choices with the reason. */
    private fun replaceAttempt(block: suspend CoroutineScope.() -> Unit) {
        attempt?.cancel()
        attempt = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Steam sign-in failed")
                mutableStep.value = AccountSignInStep.Choose(failure = reason(e))
            }
        }
    }

    /** Keeps the sign-in Steam handed back, logs on with it and reads the library. */
    private suspend fun finish(account: String, refreshToken: String, clientId: Long?) {
        if (account.isBlank() || refreshToken.isBlank()) error("Steam sent no sign-in. Try again")
        mutableStep.value = AccountSignInStep.Working
        SteamCredentials.save(app, SteamCredentials(accountName = account, refreshToken = refreshToken, clientId = clientId))
        SteamSession.logOn(app).getOrThrow()
        mutableStep.value = AccountSignInStep.Done(account)
        StoreChanges.announce(app)
        // Signed in: Steam stays connected for friends and chat, unless the person chose Offline.
        SteamConnection.refresh(app)
        // The library is read after the screen has said so; it may take a while.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            store.sync(app)
                .onSuccess { StoreChanges.announce(app) }
                .onFailure { Timber.tag(TAG).w(it, "First Steam library read failed") }
        }
    }

    /** Steam Guard: a code from the phone app or e-mail, or an approval in the phone app (GameNative's authenticator). */
    private val guard = object : IAuthenticator {
        override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
            mutableStep.value = AccountSignInStep.ApproveOnPhone
            return CompletableFuture.completedFuture(true)
        }

        override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
            mutableStep.value = AccountSignInStep.Code(sentByEmail = false, wrongBefore = previousCodeWasIncorrect)
            return nextCode()
        }

        override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
            mutableStep.value = AccountSignInStep.Code(sentByEmail = true, wrongBefore = previousCodeWasIncorrect)
            return nextCode()
        }

        private fun nextCode(): CompletableFuture<String> {
            val future = CompletableFuture<String>()
            scope.launch { future.complete(codes.receive()) }
            return future
        }
    }

    private fun reason(e: Exception): String = when (e) {
        is AuthenticationException -> when (e.result) {
            EResult.InvalidPassword -> "That account name and password do not match"
            EResult.RateLimitExceeded -> "Steam is refusing sign-ins from here for a while. Try again later"
            EResult.Expired, EResult.Timeout -> "The sign-in ran out of time. Try again"
            else -> "Steam refused the sign-in (${e.result?.name ?: e.message})"
        }
        else -> e.message ?: "Steam could not sign you in"
    }

    private companion object {
        const val TAG = "SteamSignIn"
    }
}
