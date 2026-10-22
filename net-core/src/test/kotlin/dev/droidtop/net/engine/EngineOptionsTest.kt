package dev.droidtop.net.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EngineOptionsTest {
    @Test
    fun theFileNameAndOneConnectionAreAlwaysSet() {
        val options = EngineOptions.forRequest("acquire_9.zip", emptyMap())
        assertEquals("acquire_9.zip", options["out"])
        assertEquals("1", options["split"])
        assertEquals("1", options["max-connection-per-server"])
        assertFalse("header" in options)
    }

    @Test
    fun aSessionsUserAgentAndRefererBecomeAria2sOwnOptions() {
        val options = EngineOptions.forRequest(
            "f",
            linkedMapOf("user-agent" to "Mozilla/5.0 Session", "Referer" to "https://site.test/p", "Cookie" to "a=b", "X-Token" to "t"),
        )
        assertEquals("Mozilla/5.0 Session", options["user-agent"])
        assertEquals("https://site.test/p", options["referer"])
        assertEquals("Cookie: a=b\nX-Token: t", options["header"])
    }

    @Test
    fun headersTheEngineManagesItselfAreLeftOut() {
        val options = EngineOptions.forRequest("f", mapOf("Range" to "bytes=5-", "Host" to "evil.test", "Connection" to "close"))
        assertFalse("header" in options)
    }

    @Test
    fun aHeaderThatCouldCarryAnotherIsRefused() {
        for (headers in listOf(mapOf("X" to "a\nOn-Download-Complete: x"), mapOf("X" to "a\rb"), mapOf("X: y" to "z"), mapOf("" to "z"), mapOf("X Y" to "z"))) {
            try {
                EngineOptions.forRequest("f", headers)
                fail("accepted $headers")
            } catch (e: TransferException) {
                assertEquals("a request header of this download is not valid", e.message)
            }
        }
    }

    @Test
    fun errorCodesAreSortedIntoRetryRestartAndFail() {
        for (code in listOf(2, 5, 6, 19, 29)) assertTrue(EngineErrors.classify(code) === EngineErrors.Verdict.Retry)
        assertTrue(EngineErrors.classify(8) === EngineErrors.Verdict.Restart)
        assertEquals("the server does not have that file", (EngineErrors.classify(3) as EngineErrors.Verdict.Fail).message)
        assertEquals("there is not enough space for this download", (EngineErrors.classify(9) as EngineErrors.Verdict.Fail).message)
        assertEquals("the download engine reported error 99", (EngineErrors.classify(99) as EngineErrors.Verdict.Fail).message)
    }
}
