package dev.droidtop.stores

import dev.droidtop.stores.util.ChunkAssembler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chunked-install bookkeeping GOG and Epic share (Droidtop/tracker#766). */
class ChunkAssemblerTest {
    private data class Part(val file: String, val chunks: List<String>)

    // Chunk "a" fills two positions of one.bin and one of two.bin; "b" is one.bin's last; "c" is two.bin's.
    private val files = listOf(Part("one.bin", listOf("a", "b", "a")), Part("two.bin", listOf("c", "a")))

    private class Log {
        val written = mutableListOf<String>()
        val freed = mutableListOf<String>()
        val completed = mutableListOf<String>()
    }

    private fun assembler(log: Log, allOrNothing: Boolean, fails: (String, String, Int) -> Boolean = { _, _, _ -> false }) = ChunkAssembler(
        files = files,
        chunksOf = { it.chunks },
        chunkId = { it },
        fileKey = { it.file },
        allOrNothing = allOrNothing,
        write = { file, chunk, position ->
            if (fails(file.file, chunk, position)) {
                Result.failure<Unit>(IllegalStateException("bad"))
            } else {
                log.written += "${file.file}#$position=$chunk"
                Result.success(Unit)
            }
        },
        freeChunk = { log.freed += it },
        onFileComplete = { log.completed += it.file },
    )

    @Test
    fun `a chunk goes to every position that uses it, and leaves the cache after its last`() = runBlocking {
        val log = Log()
        val a = assembler(log, allOrNothing = true)
        assertEquals(3, a.usesOf("a"))
        assertTrue(a.place("a").isSuccess)
        assertEquals(listOf("one.bin#0=a", "one.bin#2=a", "two.bin#1=a"), log.written)
        assertEquals(listOf("a"), log.freed)
        assertEquals(1, a.pendingPositions("one.bin"))
        assertEquals(emptyList<String>(), log.completed)
        a.place("b")
        a.place("c")
        assertEquals(listOf("one.bin", "two.bin"), log.completed)
        assertEquals(listOf("a", "b", "c"), log.freed)
        assertEquals(0, a.usesLeft("a"))
    }

    @Test
    fun `all or nothing, a chunk that missed one position counts nothing and can be placed again`() = runBlocking {
        val log = Log()
        var broken = true
        val a = assembler(log, allOrNothing = true) { file, _, position -> broken && file == "two.bin" && position == 1 }
        val first = a.place("a")
        assertTrue(first.isFailure)
        assertEquals("Assembled 2/3 position(s) for chunk a", first.exceptionOrNull()?.message)
        assertEquals(3, a.usesLeft("a"))
        assertEquals(3, a.pendingPositions("one.bin"))
        assertEquals(emptyList<String>(), log.freed)
        broken = false
        assertTrue(a.place("a").isSuccess)
        assertEquals(1, a.pendingPositions("one.bin"))
        assertEquals(1, a.pendingPositions("two.bin"))
        assertEquals(listOf("a"), log.freed)
    }

    @Test
    fun `counting each position, a missed one is left and the chunk stays cached`() = runBlocking {
        val log = Log()
        val a = assembler(log, allOrNothing = false) { file, _, _ -> file == "two.bin" }
        assertTrue(a.place("a").isSuccess)
        assertEquals(1, a.usesLeft("a"))
        assertEquals(emptyList<String>(), log.freed)
        assertEquals(1, a.pendingPositions("one.bin"))
    }

    @Test
    fun `a stopped download places nothing, and an unknown chunk is nothing to do`() = runBlocking {
        val log = Log()
        val a = assembler(log, allOrNothing = true)
        assertTrue(a.place("a") { false }.isFailure)
        assertEquals(emptyList<String>(), log.written)
        assertTrue(a.place("zzz").isSuccess)
        assertEquals(0, a.usesOf("zzz"))
    }
}
