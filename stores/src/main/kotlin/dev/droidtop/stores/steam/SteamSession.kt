package dev.droidtop.stores.steam

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.steam.discovery.FileServerListProvider
import `in`.dragonbra.javasteam.steam.discovery.ServerQuality
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import `in`.dragonbra.javasteam.steam.handlers.steamgameserver.SteamGameServer
import `in`.dragonbra.javasteam.steam.handlers.steammasterserver.SteamMasterServer
import `in`.dragonbra.javasteam.steam.handlers.steamscreenshots.SteamScreenshots
import `in`.dragonbra.javasteam.steam.handlers.steamuser.ChatMode
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.handlers.steamworkshop.SteamWorkshop
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import java.io.Closeable
import java.io.File
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * droidtop's one connection to Steam (docs/SPEC.md 7g, "Stores"): what
 * GameNative's SteamService did to reach Steam (connect over a web socket
 * with a kept server list, log on with the refresh token a sign-in left,
 * take the licence list), lifted into droidtop (GameNative and JavaSteam,
 * GPL-3.0). It is not an Android service and keeps no notification: it is
 * opened by whoever needs Steam (the sign-in screen, a library sync, an
 * install job, an update check), held while they work ([use]), and closed a
 * few minutes after the last of them lets go. A Steam install runs inside
 * its job, which is what keeps the process alive while it downloads.
 *
 * Not carried from GameNative's service: friends and persona, achievements,
 * Steam Cloud, family sharing, game invites, collections and the continuous
 * product-info watcher; droidtop's library reads product info when it syncs.
 */
internal object SteamSession {
    private const val TAG = "SteamSession"
    private val PROTOCOLS: EnumSet<ProtocolTypes> = EnumSet.of(ProtocolTypes.WEB_SOCKET)
    private const val CONNECT_TIMEOUT_MS = 20_000L
    private const val LOG_ON_TIMEOUT_MS = 30_000L
    private const val LICENCES_TIMEOUT_MS = 60_000L
    private const val IDLE_CLOSE_MS = 3L * 60 * 1000

    /** Log-on answers that mean the stored sign-in no longer works (GameNative's shouldClearUserDataForLoggedOnFailure). */
    private val SIGN_IN_GONE = setOf(
        EResult.InvalidPassword, EResult.IllegalPassword, EResult.PasswordUnset, EResult.AccountLogonDenied,
        EResult.AccountLogonDeniedNoMail, EResult.AccountLogonDeniedVerifiedEmailRequired,
        EResult.AccountLoginDeniedNeedTwoFactor, EResult.InvalidLoginAuthCode, EResult.ExpiredLoginAuthCode,
        EResult.RequirePasswordReEntry, EResult.ParentalControlRestricted, EResult.CachedCredentialInvalid,
        EResult.AccessDenied, EResult.Expired, EResult.Revoked,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val holders = AtomicInteger(0)
    private var idleClose: Job? = null

    @Volatile private var client: SteamClient? = null
    @Volatile private var callbacks: CallbackManager? = null
    private val subscriptions = mutableListOf<Closeable>()
    @Volatile private var user: SteamUser? = null

    @Volatile
    var apps: SteamApps? = null
        private set

    /** Steam Cloud's handler on the current connection (the save sync), or null while there is none. */
    @Volatile
    var cloud: SteamCloud? = null
        private set

    /** The HTTP client the connection was configured with: Steam Cloud's file transfers go through it. */
    val httpClient: OkHttpClient? get() = client?.configuration?.httpClient as? OkHttpClient

    @Volatile private var connected = CompletableDeferred<Boolean>()
    @Volatile private var logOnAnswer: CompletableDeferred<EResult>? = null

    /** The licence list of the current log-on; Steam sends it right after the log-on succeeds. */
    @Volatile private var licenceList = CompletableDeferred<List<License>>()

    @Volatile private var appContext: Context? = null

    init {
        // JavaSteam's logger registry is a plain HashMap that its threads
        // modify concurrently; GameNative swapped it for a concurrent map
        // before anything else ran (its "JavaSteam logger CME hot-fix").
        runCatching {
            val field = Class.forName("in.dragonbra.javasteam.util.log.LogManager").getDeclaredField("LOGGERS")
            field.isAccessible = true
            field.set(null, java.util.concurrent.ConcurrentHashMap<Any, Any>())
        }
    }

    val isLoggedOn: Boolean get() = client?.steamID?.isValid == true

    /** The account id (the low half of the Steam id) of the current log-on, or null. */
    val accountId: Int? get() = client?.steamID?.takeIf { it.isValid }?.accountID?.toInt()

    /**
     * Runs [block] with a connection held open; the connection closes
     * [IDLE_CLOSE_MS] after the last holder lets go. Connects first when
     * needed; throws when Steam cannot be reached.
     */
    suspend fun <T> use(context: Context, block: suspend (SteamClient) -> T): T {
        acquire(context)
        try {
            return block(connectedClient(context))
        } finally {
            release()
        }
    }

    /** Holds the connection open until [release] (the sign-in screen, for as long as it shows). */
    fun acquire(context: Context) {
        appContext = context.applicationContext
        holders.incrementAndGet()
        idleClose?.cancel()
        idleClose = null
    }

    fun release() {
        if (holders.decrementAndGet() > 0) return
        holders.set(0)
        idleClose?.cancel()
        idleClose = scope.launch {
            delay(IDLE_CLOSE_MS)
            if (holders.get() == 0) lock.withLock { disconnect() }
        }
    }

    /** The client, connected: connects (trying another server after a timeout, three times) when it is not. */
    suspend fun connectedClient(context: Context): SteamClient = lock.withLock {
        client?.takeIf { it.isConnected && connected.isCompleted }?.let { return@withLock it }
        repeat(3) { attempt ->
            val fresh = client ?: start(context.applicationContext)
            connected = CompletableDeferred()
            withContext(Dispatchers.IO) { fresh.connect() }
            if (withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() } == true) return@withLock fresh
            Timber.tag(TAG).w("Steam did not answer (try ${attempt + 1}); trying another server")
            runCatching { fresh.servers.tryMark(fresh.currentEndpoint, PROTOCOLS, ServerQuality.BAD) }
            runCatching { fresh.disconnect() }
        }
        disconnect()
        error("Steam could not be reached. Check the network and try again")
    }

    /**
     * Logs on with the stored sign-in, when not logged on already. A sign-in
     * Steam no longer accepts is forgotten, and the failure says to sign in
     * again.
     */
    suspend fun logOn(context: Context): Result<Unit> {
        val credentials = SteamCredentials.load(context) ?: return Result.failure(IllegalStateException("Sign in to Steam first"))
        connectedClient(context)
        return lock.withLock {
            if (isLoggedOn) return@withLock Result.success(Unit)
            val answer = CompletableDeferred<EResult>()
            logOnAnswer = answer
            licenceList = CompletableDeferred()
            val steamUser = user ?: return@withLock Result.failure(IllegalStateException("Steam is not connected"))
            steamUser.logOn(
                LogOnDetails(
                    username = asciiOnly(credentials.accountName).trim(),
                    password = null,
                    shouldRememberPassword = true,
                    twoFactorCode = null,
                    authCode = null,
                    accessToken = credentials.refreshToken,
                    loginID = deviceId(context),
                    machineName = machineName(context),
                    chatMode = ChatMode.NEW_STEAM_CHAT,
                ),
            )
            when (val result = withTimeoutOrNull(LOG_ON_TIMEOUT_MS) { answer.await() }) {
                EResult.OK -> {
                    val id = client?.steamID
                    if (id != null && id.isValid && id.convertToUInt64() != credentials.steamId64) {
                        SteamCredentials.save(context, credentials.copy(steamId64 = id.convertToUInt64()))
                    }
                    Result.success(Unit)
                }
                null -> Result.failure(IllegalStateException("Steam did not answer the sign-in. Try again"))
                in SIGN_IN_GONE -> {
                    SteamCredentials.clear(context)
                    Result.failure(IllegalStateException("Steam no longer accepts this device's sign-in ($result). Sign in again"))
                }
                else -> Result.failure(IllegalStateException("Steam refused the sign-in: $result"))
            }
        }
    }

    /** The licence list of this log-on: waits for Steam to send it. */
    suspend fun licences(): List<License> =
        withTimeoutOrNull(LICENCES_TIMEOUT_MS) { licenceList.await() } ?: error("Steam did not send the list of what you own. Try again")

    /** Logs off and closes the connection; for a sign-out. */
    suspend fun logOff() {
        lock.withLock {
            runCatching { user?.logOff() }
            disconnect()
        }
    }

    private fun start(context: Context): SteamClient {
        val configuration = SteamConfiguration.create {
            it.withProtocolTypes(PROTOCOLS)
            it.withCellID(SteamCredentials.load(context)?.cellId ?: 0)
            it.withServerListProvider(FileServerListProvider(File(context.cacheDir, "steam_server_list.bin")))
            it.withConnectionTimeout(60_000L)
            it.withHttpClient(
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    // Keeps the web socket alive while nothing is said.
                    .pingInterval(15, TimeUnit.SECONDS)
                    .build(),
            )
        }
        val steam = SteamClient(configuration).apply {
            removeHandler(SteamGameServer::class.java)
            removeHandler(SteamMasterServer::class.java)
            removeHandler(SteamWorkshop::class.java)
            removeHandler(SteamScreenshots::class.java)
        }
        val manager = CallbackManager(steam)
        user = steam.getHandler(SteamUser::class.java)
        apps = steam.getHandler(SteamApps::class.java)
        cloud = steam.getHandler(SteamCloud::class.java)
        subscriptions += manager.subscribe(ConnectedCallback::class.java) { connected.complete(true) }
        subscriptions += manager.subscribe(DisconnectedCallback::class.java) {
            Timber.tag(TAG).i("Disconnected from Steam (asked: ${it.isUserInitiated})")
            connected = CompletableDeferred()
            logOnAnswer?.complete(EResult.NoConnection)
        }
        subscriptions += manager.subscribe(LoggedOnCallback::class.java) { onLoggedOn(it) }
        subscriptions += manager.subscribe(LoggedOffCallback::class.java) {
            Timber.tag(TAG).i("Logged off Steam: ${it.result}")
        }
        subscriptions += manager.subscribe(LicenseListCallback::class.java) { callback ->
            if (callback.result == EResult.OK) licenceList.complete(callback.licenseList.toList())
        }
        client = steam
        callbacks = manager
        // Steam's answers arrive on this loop; it ends when the client is replaced.
        scope.launch {
            while (isActive && callbacks === manager) {
                runCatching { manager.runWaitCallbacks(1000L) }.onFailure { Timber.tag(TAG).w(it, "Steam callback failed") }
            }
        }
        return steam
    }

    private fun onLoggedOn(callback: LoggedOnCallback) {
        Timber.tag(TAG).i("Logged on to Steam: ${callback.result}")
        if (callback.result == EResult.OK) {
            appContext?.let { context ->
                SteamCredentials.load(context)?.takeIf { it.cellId != callback.cellID }?.let {
                    runCatching { SteamCredentials.save(context, it.copy(cellId = callback.cellID)) }
                }
            }
        }
        logOnAnswer?.complete(callback.result)
    }

    private fun disconnect() {
        val steam = client ?: return
        runCatching { steam.disconnect() }
        subscriptions.forEach { runCatching { it.close() } }
        subscriptions.clear()
        callbacks = null
        client = null
        user = null
        apps = null
        cloud = null
        connected = CompletableDeferred()
        logOnAnswer?.complete(EResult.NoConnection)
        logOnAnswer = null
        licenceList = CompletableDeferred()
    }

    /** Steam drops every non-ASCII character of an account name (GameNative's SteamUtils.removeSpecialChars). */
    fun asciiOnly(text: String): String = text.replace(Regex("[^\\u0000-\\u007F]"), "")

    /** The device's own name, as Steam lists the sign-in. */
    fun machineName(context: Context): String = runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** A log-on id unique to this device and app, so another client on the same network is not logged off. */
    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): Int =
        (Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "").hashCode()
}
