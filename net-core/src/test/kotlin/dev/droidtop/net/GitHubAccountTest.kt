package dev.droidtop.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The token store abstraction and the one place both ways in meet it (docs/SPEC.md 12a "Signing in with GitHub"). */
class GitHubAccountTest {
    private class MemoryStore(var refuse: Boolean = false) : GitHubCredentialStore {
        var held: GitHubCredential? = null
        override fun load() = held
        override fun save(credential: GitHubCredential): Boolean {
            if (refuse) return false
            held = credential
            return true
        }
        override fun clear() { held = null }
        override fun hasStoredToken() = held != null
    }

    private val issued = """{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""

    private fun flow(vararg answers: DeviceFlowReply): GitHubDeviceFlow {
        val queue = ArrayDeque(answers.toList())
        var now = 0L
        return GitHubDeviceFlow({ _, _ -> queue.removeFirst() }, "id", { now }, { now += it })
    }

    private fun reply(body: String) = DeviceFlowReply(200, body)

    @Test
    fun aPastedTokenIsCheckedLabelledAndStored() {
        val store = MemoryStore()
        val account = GitHubAccount(store, { LoginLookup.Known("octocat") })
        val saved = account.savePasted("  ghp_secret  ") as SaveTokenResult.Saved
        assertEquals("octocat", saved.credential.login)
        assertEquals("ghp_secret", store.held?.token)
        assertEquals(GitHubTokenOrigin.PASTED, store.held?.origin)
    }

    @Test
    fun aTokenGitHubRejectsIsNotKept() {
        val store = MemoryStore()
        val account = GitHubAccount(store, { LoginLookup.Rejected })
        assertEquals(SaveTokenResult.Rejected, account.savePasted("bad"))
        assertNull(store.held)
    }

    @Test
    fun aTokenThatCouldNotBeCheckedOfflineIsKeptWithoutALogin() {
        val store = MemoryStore()
        val account = GitHubAccount(store, { LoginLookup.Unreachable("no network") })
        val saved = account.savePasted("tok") as SaveTokenResult.Saved
        assertFalse(saved.loginKnown)
        assertNull(store.held?.login)
        assertEquals("tok", store.held?.token)
    }

    @Test
    fun blankInputIsNothingToSave() {
        val account = GitHubAccount(MemoryStore(), { error("must not ask GitHub about nothing") })
        assertEquals(SaveTokenResult.Blank, account.savePasted("   "))
    }

    @Test
    fun refusedSecureStorageMeansNothingIsStored() {
        val store = MemoryStore(refuse = true)
        val account = GitHubAccount(store, { LoginLookup.Known("octocat") })
        assertEquals(SaveTokenResult.StorageRefused, account.savePasted("tok"))
        assertNull(store.held)
    }

    @Test
    fun deviceFlowSignInShowsTheCodeThenStoresTheLabelledToken() {
        val store = MemoryStore()
        val shown = mutableListOf<String>()
        val account = GitHubAccount(
            store,
            { token -> if (token == "granted-tok") LoginLookup.Known("octocat") else LoginLookup.Rejected },
            flow(
                reply(issued),
                reply("""{"error":"authorization_pending"}"""),
                reply("""{"access_token":"granted-tok","scope":""}"""),
            ),
        )
        val result = account.signIn("", { false }, { shown += it.userCode }) as SignInResult.SignedIn
        assertEquals(listOf("ABCD-1234"), shown)
        assertEquals("octocat", result.credential.login)
        assertEquals(GitHubTokenOrigin.DEVICE_FLOW, store.held?.origin)
        assertEquals("", store.held?.scope)
        assertEquals("granted-tok", store.held?.token)
    }

    @Test
    fun aDeniedOrCancelledSignInStoresNothing() {
        val denied = MemoryStore()
        val a = GitHubAccount(denied, { LoginLookup.Rejected }, flow(reply(issued), reply("""{"error":"access_denied"}""")))
        assertEquals(SignInResult.Ended(DeviceFlowOutcome.Denied), a.signIn("", { false }, {}))
        assertNull(denied.held)

        val cancelled = MemoryStore()
        val b = GitHubAccount(cancelled, { LoginLookup.Rejected }, flow(reply(issued)))
        assertEquals(SignInResult.Ended(DeviceFlowOutcome.Cancelled), b.signIn("", { true }, {}))
        assertNull(cancelled.held)
    }

    @Test
    fun anOfflineStartIsReportedAsOffline() {
        val account = GitHubAccount(
            MemoryStore(),
            { LoginLookup.Rejected },
            GitHubDeviceFlow({ _, _ -> throw java.io.IOException("no route") }, "id", { 0L }, {}),
        )
        val result = account.signIn("", { false }, {}) as SignInResult.CouldNotStart
        assertTrue(result.offline)
    }

    @Test
    fun aGrantedTokenThatCannotBeStoredIsNotLost() {
        val account = GitHubAccount(
            MemoryStore(refuse = true),
            { LoginLookup.Known("octocat") },
            flow(reply(issued), reply("""{"access_token":"t","scope":""}""")),
        )
        assertEquals(SignInResult.StorageRefused, account.signIn("", { false }, {}))
    }

    @Test
    fun signingOutRemovesTheCredential() {
        val store = MemoryStore()
        val account = GitHubAccount(store, { LoginLookup.Known("octocat") })
        account.savePasted("tok")
        assertTrue(store.hasStoredToken())
        account.signOut()
        assertNull(account.current())
        assertFalse(store.hasStoredToken())
    }
}
