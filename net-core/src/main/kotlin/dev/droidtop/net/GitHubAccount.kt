package dev.droidtop.net

/** How a token came to be stored: pasted by the person, or granted by the sign-in on github.com. */
enum class GitHubTokenOrigin { PASTED, DEVICE_FLOW }

/**
 * The person's GitHub credential as droidtop keeps it: the token plus
 * what is shown about it. [login] is null until GitHub has said whose
 * token it is. The token never reaches a log line, a backup or an export:
 * [toString] leaves it out for the same reason.
 */
data class GitHubCredential(
    val token: String,
    val login: String?,
    val origin: GitHubTokenOrigin,
    /** The OAuth scope the sign-in was granted ("" is public data only); null for a pasted token, whose scopes droidtop does not know. */
    val scope: String? = null,
) {
    override fun toString(): String = "GitHubCredential(login=$login, origin=$origin, scope=$scope)"
}

/**
 * Where the credential lives. The Android implementation is
 * [GitHubTokenStore] (Keystore-encrypted); the unit tests use a map. One
 * credential at a time: signing in again replaces it.
 */
interface GitHubCredentialStore {
    fun load(): GitHubCredential?

    /** False when the secure storage refused: in that case nothing is stored. */
    fun save(credential: GitHubCredential): Boolean

    fun clear()

    /** True when something is stored even if [load] can no longer read it (a restored device lost its Keystore key). */
    fun hasStoredToken(): Boolean
}

/** What asking GitHub "whose token is this" came back with. */
sealed interface LoginLookup {
    data class Known(val login: String) : LoginLookup
    /** GitHub answered 401: expired, revoked or mistyped. */
    object Rejected : LoginLookup
    /** GitHub could not be reached or answered something else. */
    data class Unreachable(val reason: String) : LoginLookup
}

sealed interface SaveTokenResult {
    data class Saved(val credential: GitHubCredential, val loginKnown: Boolean) : SaveTokenResult
    object Rejected : SaveTokenResult
    object StorageRefused : SaveTokenResult
    object Blank : SaveTokenResult
}

sealed interface SignInResult {
    data class SignedIn(val credential: GitHubCredential) : SignInResult
    data class Ended(val outcome: DeviceFlowOutcome) : SignInResult
    data class CouldNotStart(val reason: String, val offline: Boolean) : SignInResult
    object StorageRefused : SignInResult
}

/**
 * The one place the two ways in (paste a token, sign in on github.com)
 * meet the one credential store, so a token is validated, labelled and
 * stored the same way whichever path produced it. Free of Android: the
 * store, the flow and the login lookup are all injected.
 */
class GitHubAccount(
    private val store: GitHubCredentialStore,
    private val lookupLogin: (token: String) -> LoginLookup,
    private val flow: GitHubDeviceFlow = GitHubDeviceFlow(),
) {
    fun current(): GitHubCredential? = store.load()

    /** Stores a pasted [token] after asking GitHub whose it is: a token GitHub rejects is not kept; one that merely could not be checked (offline) is kept unlabelled. */
    fun savePasted(token: String): SaveTokenResult {
        val clean = token.trim()
        if (clean.isEmpty()) return SaveTokenResult.Blank
        val login = when (val found = lookupLogin(clean)) {
            is LoginLookup.Known -> found.login
            LoginLookup.Rejected -> return SaveTokenResult.Rejected
            is LoginLookup.Unreachable -> null
        }
        val credential = GitHubCredential(clean, login, GitHubTokenOrigin.PASTED)
        if (!store.save(credential)) return SaveTokenResult.StorageRefused
        return SaveTokenResult.Saved(credential, loginKnown = login != null)
    }

    /**
     * The whole device-flow sign-in: ask for a code, hand it to [onCode] so the screen shows it, poll
     * until it ends, and on approval label the token with its login and store it. Blocking.
     */
    fun signIn(
        scope: String,
        isCancelled: () -> Boolean,
        onCode: (DeviceCode) -> Unit,
        onWaiting: (secondsLeft: Int) -> Unit = {},
    ): SignInResult {
        val code = when (val issued = flow.requestCode(scope)) {
            is DeviceCodeResult.Issued -> issued.code
            is DeviceCodeResult.Offline -> return SignInResult.CouldNotStart(issued.reason, offline = true)
            is DeviceCodeResult.Failed -> return SignInResult.CouldNotStart(issued.reason, offline = false)
        }
        onCode(code)
        val granted = when (val outcome = flow.poll(code, isCancelled, onWaiting)) {
            is DeviceFlowOutcome.Granted -> outcome
            else -> return SignInResult.Ended(outcome)
        }
        val login = (lookupLogin(granted.token) as? LoginLookup.Known)?.login
        val credential = GitHubCredential(granted.token, login, GitHubTokenOrigin.DEVICE_FLOW, granted.scope)
        return if (store.save(credential)) SignInResult.SignedIn(credential) else SignInResult.StorageRefused
    }

    /** Removes the credential from this device. (GitHub keeps the authorisation until the person revokes it in their GitHub settings; the screen says so.) */
    fun signOut() = store.clear()
}
