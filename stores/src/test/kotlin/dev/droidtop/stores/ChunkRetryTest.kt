package dev.droidtop.stores

import dev.droidtop.stores.gog.GOGDownloadManager
import dev.droidtop.stores.util.ChunkRetry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one store retry around a chunk or file fetch (Droidtop/tracker#766). */
class ChunkRetryTest {
    @Test
    fun `the pause doubles from a second and none comes before the first try`() {
        assertEquals(0L, ChunkRetry.pauseBefore(0))
        assertEquals(1_000L, ChunkRetry.pauseBefore(1))
        assertEquals(2_000L, ChunkRetry.pauseBefore(2))
        assertEquals(16_000L, ChunkRetry.pauseBefore(40))
    }

    @Test
    fun `a fetch that works the second time is tried twice`() = runBlocking {
        val tries = mutableListOf<Int>()
        val result = ChunkRetry.run("t", "chunk") { attempt ->
            tries += attempt
            if (attempt == 0) Result.failure(IllegalStateException("damaged")) else Result.success("ok")
        }
        assertEquals("ok", result.getOrNull())
        assertEquals(listOf(0, 1), tries)
    }

    @Test
    fun `it stops at its attempts and gives the last failure`() = runBlocking {
        var tries = 0
        val result = ChunkRetry.run<Unit>("t", "chunk", attempts = 2) { attempt ->
            tries++
            Result.failure(IllegalStateException("bad $attempt"))
        }
        assertEquals(2, tries)
        assertEquals("bad 1", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a stopped download is not fetched`() = runBlocking {
        var tries = 0
        val result = ChunkRetry.run("t", "chunk", isActive = { false }) { tries++; Result.success(Unit) }
        assertEquals(0, tries)
        assertTrue(result.isFailure)
        assertEquals("Download cancelled", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a GOG chunk gets one try per CDN up to three, and at least two`() {
        assertEquals(2, GOGDownloadManager.chunkAttempts(1))
        assertEquals(2, GOGDownloadManager.chunkAttempts(2))
        assertEquals(3, GOGDownloadManager.chunkAttempts(3))
        assertEquals(3, GOGDownloadManager.chunkAttempts(7))
    }
}
