package dev.droidtop.net

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device flow's state handling (docs/SPEC.md 12a "Signing in with GitHub"), with a scripted transport and a fake clock: no network, no real waiting. */
class GitHubDeviceFlowTest {
    private class Script(private val answers: ArrayDeque<() -> DeviceFlowReply>) : DeviceFlowTransport {
        val posts = mutableListOf<Pair<String, Map<String, String>>>()
        override fun post(url: String, form: Map<String, String>): DeviceFlowReply {
            posts += url to form
            return (answers.removeFirstOrNull() ?: error("unexpected request ${posts.size}"))()
        }
    }

    private class Clock {
        var now = 1_000_000L
        val sleeps = mutableListOf<Long>()
        fun sleep(ms: Long) {
            sleeps += ms
            now += ms
        }
    }

    private fun ok(body: String) = { DeviceFlowReply(200, body) }
    private val pending = ok("""{"error":"authorization_pending"}""")
    private val code = DeviceCode("dev-secret", "ABCD-1234", "https://github.com/login/device", 900, 5)

    private fun flow(script: Script, clock: Clock) =
        GitHubDeviceFlow(script, "client-id", { clock.now }, clock::sleep)

    private fun script(vararg answers: () -> DeviceFlowReply) = Script(ArrayDeque(answers.toList()))

    @Test
    fun requestCodeParsesTheGrantAndSendsOnlyTheClientIdAndScope() {
        val s = script(ok("""{"device_code":"dc","user_code":"WXYZ-0000","verification_uri":"https://github.com/login/device","expires_in":600,"interval":7}"""))
        val result = flow(s, Clock()).requestCode("repo")
        val issued = (result as DeviceCodeResult.Issued).code
        assertEquals("WXYZ-0000", issued.userCode)
        assertEquals(7, issued.intervalSeconds)
        assertEquals(600, issued.expiresInSeconds)
        assertEquals(mapOf("client_id" to "client-id", "scope" to "repo"), s.posts.single().second)
        assertEquals(GitHubOAuth.DEVICE_CODE_URL, s.posts.single().first)
    }

    @Test
    fun noScopeIsSentWhenPublicDataIsEnough() {
        val s = script(ok("""{"device_code":"dc","user_code":"A","verification_uri":"https://github.com/login/device","expires_in":600,"interval":5}"""))
        flow(s, Clock()).requestCode(GitHubOAuth.SCOPE_PUBLIC)
        assertFalse(s.posts.single().second.containsKey("scope"))
    }

    @Test
    fun aVerificationAddressOffGitHubIsRefused() {
        val s = script(ok("""{"device_code":"dc","user_code":"A","verification_uri":"https://evil.example/device","expires_in":600,"interval":5}"""))
        assertTrue(flow(s, Clock()).requestCode("") is DeviceCodeResult.Failed)
    }

    @Test
    fun aDisabledDeviceFlowIsExplained() {
        val s = script(ok("""{"error":"device_flow_disabled"}"""))
        val failed = flow(s, Clock()).requestCode("") as DeviceCodeResult.Failed
        assertTrue(failed.reason.contains("not enabled"))
    }

    @Test
    fun offlineAtTheStartIsOffline() {
        val s = script({ throw IOException("no route") })
        assertTrue(flow(s, Clock()).requestCode("") is DeviceCodeResult.Offline)
    }

    @Test
    fun pollsAtTheStatedIntervalUntilGranted() {
        val clock = Clock()
        val s = script(pending, pending, ok("""{"access_token":"tok","token_type":"bearer","scope":"repo"}"""))
        val outcome = flow(s, clock).poll(code, { false })
        assertEquals(DeviceFlowOutcome.Granted("tok", "repo"), outcome)
        assertEquals(3, s.posts.size)
        // Three intervals of five seconds, slept in one-second slices.
        assertEquals(15_000L, clock.sleeps.sum())
        assertTrue(clock.sleeps.all { it <= 1000L })
        // The device code, client id and grant type go to the token endpoint, nothing else.
        assertEquals(setOf("client_id", "device_code", "grant_type"), s.posts.first().second.keys)
        assertEquals(GitHubOAuth.ACCESS_TOKEN_URL, s.posts.first().first)
    }

    @Test
    fun slowDownLengthensTheInterval() {
        val clock = Clock()
        val s = script(ok("""{"error":"slow_down","interval":10}"""), ok("""{"access_token":"tok","scope":""}"""))
        flow(s, clock).poll(code, { false })
        // 5 s before the first poll, then the 10 s GitHub asked for.
        assertEquals(15_000L, clock.sleeps.sum())
    }

    @Test
    fun slowDownWithoutANumberAddsFiveSeconds() {
        val clock = Clock()
        val s = script(ok("""{"error":"slow_down"}"""), ok("""{"access_token":"tok","scope":""}"""))
        flow(s, clock).poll(code, { false })
        assertEquals(15_000L, clock.sleeps.sum())
    }

    @Test
    fun denialIsTerminal() {
        val s = script(pending, ok("""{"error":"access_denied"}"""))
        assertEquals(DeviceFlowOutcome.Denied, flow(s, Clock()).poll(code, { false }))
    }

    @Test
    fun expiredTokenAnswerIsTerminal() {
        val s = script(ok("""{"error":"expired_token"}"""))
        assertEquals(DeviceFlowOutcome.Expired, flow(s, Clock()).poll(code, { false }))
    }

    @Test
    fun theCodeExpiringBeforeApprovalEndsTheFlowWithoutAnotherRequestPastTheDeadline() {
        val clock = Clock()
        val short = code.copy(expiresInSeconds = 12)
        val s = script(pending, pending, pending, pending)
        assertEquals(DeviceFlowOutcome.Expired, flow(s, clock).poll(short, { false }))
        assertTrue(s.posts.size <= 2)
        assertTrue(clock.sleeps.sum() <= 12_000L)
    }

    @Test
    fun cancellingStopsBeforeAnyRequestAndReportsCancelled() {
        val s = script()
        assertEquals(DeviceFlowOutcome.Cancelled, flow(s, Clock()).poll(code, { true }))
        assertTrue(s.posts.isEmpty())
    }

    @Test
    fun cancellingMidWaitIsPrompt() {
        val clock = Clock()
        var checks = 0
        val s = script(pending)
        val outcome = flow(s, clock).poll(code, { ++checks > 8 })
        assertEquals(DeviceFlowOutcome.Cancelled, outcome)
        assertTrue(clock.sleeps.sum() < 10_000L)
    }

    @Test
    fun aBriefNetworkDropIsRiddenOutButAPersistentOneIsOffline() {
        val ride = script({ throw IOException("blip") }, ok("""{"access_token":"tok","scope":""}"""))
        assertTrue(flow(ride, Clock()).poll(code, { false }) is DeviceFlowOutcome.Granted)

        val gone = script({ throw IOException("down") }, { throw IOException("down") }, { throw IOException("down") })
        assertTrue(flow(gone, Clock()).poll(code, { false }) is DeviceFlowOutcome.Offline)
    }

    @Test
    fun serverErrorsCountAsTheNetworkBeingAway() {
        val gone = script({ DeviceFlowReply(503, "") }, { DeviceFlowReply(502, "") }, { DeviceFlowReply(500, "") })
        assertTrue(flow(gone, Clock()).poll(code, { false }) is DeviceFlowOutcome.Offline)
    }

    @Test
    fun anUnknownErrorFailsWithGitHubsWords() {
        val s = script(ok("""{"error":"incorrect_device_code"}"""))
        val failed = flow(s, Clock()).poll(code, { false }) as DeviceFlowOutcome.Failed
        assertTrue(failed.reason.contains("no longer recognises"))
    }

    @Test
    fun theWaitingCallbackCountsDown() {
        val clock = Clock()
        val seen = mutableListOf<Int>()
        val s = script(ok("""{"access_token":"tok","scope":""}"""))
        flow(s, clock).poll(code, { false }, seen::add)
        assertEquals(900, seen.first())
        assertTrue(seen.zipWithNext().all { (a, b) -> b < a })
    }

    @Test
    fun theTokenNeverShowsInAStringForm() {
        assertFalse(DeviceFlowOutcome.Granted("tok-secret-123", "repo").toString().contains("tok-secret-123"))
        assertFalse(code.toString().contains("dev-secret"))
        assertFalse(GitHubCredential("tok-secret-123", "me", GitHubTokenOrigin.PASTED).toString().contains("tok-secret-123"))
    }
}
