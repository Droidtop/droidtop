package dev.droidtop.library.integrations

import android.content.Context
import android.content.ContextWrapper
import dev.droidtop.pluginhost.SourceFailures
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge/grouping and timeout logic of the search fan-out
 * (docs/SPEC.md 12a "Search fan-out"), driven against fake
 * [GameSourceProvider]s so it needs no real plugin connection -- per the
 * owner's own "Unit-test the merge/grouping and timeout logic" and
 * "unit-test against fake implementations of each API" directives.
 */
class PluginSearchAggregatorTest {

    private fun result(id: String) = AcquireContentResult(
        id = id,
        title = id,
        subtitle = null,
        sizeLabel = null,
        platform = null,
        artUrl = null,
        raw = "{}",
    )

    private fun fakeSource(label: String, delayMs: Long = 0, throws: Boolean = false, results: List<AcquireContentResult> = emptyList()): GameSourceProvider =
        object : GameSourceProvider {
            override val id = "fake_$label"
            override val label = label
            override fun detailScreen(result: AcquireContentResult, systemId: String?, systemName: String?, destination: java.io.File?) = dev.droidtop.library.settings.CatalogScreen("fake_detail", result.title, groups = { emptyList() })
            override suspend fun search(context: Context, query: String, platform: String?): Result<List<AcquireContentResult>> {
                delay(delayMs)
                if (throws) error("$label failed")
                return Result.success(results)
            }
        }

    @Test
    fun `outcomes keep source order and each source's own result order`() = runBlocking {
        val a = fakeSource("A", delayMs = 30, results = listOf(result("a1"), result("a2")))
        val b = fakeSource("B", results = listOf(result("b1")))
        val outcomes = PluginSearchAggregator.fanOutOutcomes(listOf(a, b)) { it.search(mockContext(), "q", null) }
        assertEquals(listOf(a, b), outcomes.map { it.source })
        val hits = outcomes.hits()
        assertEquals(listOf("a1", "a2", "b1"), hits.map { it.result.id })
        assertEquals(listOf(a, a, b), hits.map { it.source })
    }

    @Test
    fun `a failing source is reported with its reason, not folded into no results`() = runBlocking {
        val ok = fakeSource("OK", results = listOf(result("ok1")))
        val broken = fakeSource("Broken", throws = true)
        val outcomes = PluginSearchAggregator.fanOutOutcomes(listOf(ok, broken)) { it.search(mockContext(), "q", null) }
        assertEquals(null, outcomes[0].failure)
        assertEquals("Broken failed", outcomes[1].failure)
        assertEquals(emptyList<AcquireContentResult>(), outcomes[1].results)
    }

    @Test
    fun `a source that answers with an empty list is a real answer, not a failure`() = runBlocking {
        val none = fakeSource("None")
        val outcomes = PluginSearchAggregator.fanOutOutcomes(listOf(none)) { it.search(mockContext(), "q", null) }
        assertEquals(null, outcomes.single().failure)
        assertEquals(emptyList<AcquireContentResult>(), outcomes.single().results)
    }

    @Test
    fun `a source slower than the timeout is reported as not answering in time`() = runBlocking {
        val slow = fakeSource("Slow", delayMs = 200, results = listOf(result("slow1")))
        val outcomes = PluginSearchAggregator.fanOutOutcomes(listOf(slow), timeoutMs = 40) { it.search(mockContext(), "q", null) }
        assertTrue(outcomes.single().failure!!.contains("did not answer in time"))
    }

    @Test
    fun `a failure with no message still gets a readable sentence`() {
        assertEquals("IllegalStateException", PluginSearchAggregator.describe(IllegalStateException()))
        val long = PluginSearchAggregator.describe(RuntimeException("x".repeat(500)))
        assertEquals(200, long.length)
    }

    @Test
    fun `fanOut runs every source concurrently and merges what comes back`() = runBlocking {
        val a = fakeSource("A", results = listOf(result("a1")))
        val b = fakeSource("B", results = listOf(result("b1"), result("b2")))
        val hits = PluginSearchAggregator.fanOut(listOf(a, b)) { it.search(mockContext(), "q", null).getOrNull().orEmpty() }
        assertEquals(setOf("a1", "b1", "b2"), hits.map { it.result.id }.toSet())
    }

    @Test
    fun `a source that throws contributes nothing but does not fail the others`() = runBlocking {
        val ok = fakeSource("OK", results = listOf(result("ok1")))
        val broken = fakeSource("Broken", throws = true)
        val hits = PluginSearchAggregator.fanOut(listOf(ok, broken)) { it.search(mockContext(), "q", null).getOrNull().orEmpty() }
        assertEquals(listOf("ok1"), hits.map { it.result.id })
    }

    @Test
    fun `a source slower than the timeout contributes nothing, a fast source still returns`() = runBlocking {
        val fast = fakeSource("Fast", delayMs = 5, results = listOf(result("fast1")))
        val slow = fakeSource("Slow", delayMs = 200, results = listOf(result("slow1")))
        val hits = PluginSearchAggregator.fanOut(
            listOf(fast, slow),
            timeoutMs = 40,
        ) { it.search(mockContext(), "q", null).getOrNull().orEmpty() }
        assertEquals(listOf("fast1"), hits.map { it.result.id })
    }

    @Test
    fun `fanOut runs sources in parallel, not sequentially`() = runBlocking {
        val calls = AtomicInteger(0)
        val sources = (1..5).map { i ->
            fakeSource("S$i", delayMs = 60, results = listOf(result("r$i")))
        }
        val startedAt = System.nanoTime()
        val hits = PluginSearchAggregator.fanOut(sources) {
            calls.incrementAndGet()
            it.search(mockContext(), "q", null).getOrNull().orEmpty()
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(5, calls.get())
        assertEquals(5, hits.size)
        // Five 60ms sources run in parallel finish close to 60ms total, not 300ms --
        // a generous ceiling (250ms) keeps this from flaking under CI load while
        // still catching an accidental fall-back to sequential execution.
        assertTrue("expected parallel execution, took ${elapsedMs}ms", elapsedMs < 250)
    }

    private fun fakeSourceCounting(calls: AtomicInteger): GameSourceProvider = object : GameSourceProvider {
        override val id = "counting"
        override val label = "Counting"
        override fun detailScreen(result: AcquireContentResult, systemId: String?, systemName: String?, destination: java.io.File?) = dev.droidtop.library.settings.CatalogScreen("count_detail", result.title, groups = { emptyList() })
        override suspend fun search(context: Context, query: String, platform: String?): Result<List<AcquireContentResult>> {
            calls.incrementAndGet()
            return Result.success(emptyList())
        }
    }

    /** No fake source above reads anything off the Context it is handed; a bare ContextWrapper is enough to satisfy the interface's non-null Context parameter without instantiating a real Android Context. */
    private fun mockContext(): Context = object : ContextWrapper(null) {}

    @Test
    fun `a sign-in or browser-check answer is droidtop's sentence and action, a detail goes behind Technical details`() {
        val source = fakeSource("Site")
        val signIn = PluginSearchAggregator.failureOutcome(source, SourceReplyFailure(dev.droidtop.pluginhost.PluginErrorCode.SIGN_IN_REQUIRED, "whatever", null))
        assertEquals(SourceFailures.NEEDS_SIGN_IN, signIn.failure)
        assertEquals(dev.droidtop.pluginhost.PluginWebAccount.Need.SIGN_IN, signIn.access)
        val check = PluginSearchAggregator.failureOutcome(source, SourceReplyFailure(dev.droidtop.pluginhost.PluginErrorCode.BROWSER_CHECK_REQUIRED, null, "<p>Just a moment</p>"))
        assertEquals(SourceFailures.NEEDS_BROWSER_CHECK, check.failure)
        assertTrue(check.technical!!.contains("Detail: Just a moment"))
        assertTrue(check.technical!!.contains("Answer: BROWSER_CHECK_REQUIRED"))
        val plain = PluginSearchAggregator.failureOutcome(source, SourceReplyFailure(dev.droidtop.pluginhost.PluginErrorCode.FAILED, "The site did not answer", "socket closed after 3 s"))
        assertEquals("The site did not answer", plain.failure)
        assertTrue(plain.technical!!.contains("Detail: socket closed after 3 s"))
        assertEquals(null, plain.access)
    }

    @Test
    fun `technical details say what failed, never only the headline again (tracker 746)`() {
        val source = fakeSource("Site")
        val vague = PluginSearchAggregator.failureOutcome(
            source,
            SourceReplyFailure(dev.droidtop.pluginhost.PluginErrorCode.FAILED, "Site could not complete the request", null),
            now = 1_761_757_200_000L,
        )
        assertEquals("Site could not complete the request", vague.failure)
        val lines = vague.technical!!.lines()
        assertEquals("Call: library.sources search", lines.first())
        assertTrue(lines.contains("Answer: FAILED"))
        assertTrue(lines.contains("At: 2025-10-29 17:00:00 UTC"))
        assertFalse("the headline is not repeated", lines.any { it.startsWith("Said:") })
    }

    @Test
    fun `a failing source outcome carries the plain sentence and keeps its own words`() = runBlocking {
        val source = object : GameSourceProvider {
            override val id = "lewd"
            override val label = "LewdCorner"
            override suspend fun search(context: android.content.Context, query: String, platform: String?): Result<List<AcquireContentResult>> =
                Result.failure(IllegalStateException("JavaScript is disabled. For a better experience, please enable JavaScript in your browser before proceeding."))
            override fun detailScreen(result: AcquireContentResult, systemId: String?, systemName: String?, destination: java.io.File?) =
                dev.droidtop.library.settings.CatalogScreen("d", result.title, groups = { emptyList() })
        }
        val outcome = PluginSearchAggregator.fanOutOutcomes(listOf(source)) { it.search(android.content.ContextWrapper(null), "q", null) }.single()
        assertEquals(SourceFailures.SIGN_IN, outcome.failure)
        assertNotNull(outcome.technical)
        assertTrue(outcome.technical!!.contains("Said: JavaScript is disabled"))
        assertTrue(outcome.technical!!.contains("Exception: IllegalStateException"))
        assertFalse(outcome.failure!!.contains("JavaScript"))
    }
}
