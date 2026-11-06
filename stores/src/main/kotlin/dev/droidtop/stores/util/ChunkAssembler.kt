package dev.droidtop.stores.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bookkeeping of a chunked store install, shared by GOG's Gen 2 and Epic's downloads (Droidtop/tracker#766): which
 * file positions each chunk fills, how many positions of each file are still to be written, and how many uses each
 * cached chunk has left. Built once from the plan, so placing a chunk looks up its positions instead of searching
 * every file of the game for it, which was quadratic in the size of the game.
 *
 * [place] writes one downloaded chunk into every position that uses it ([write]), then counts: a file whose last
 * position was written is complete ([onFileComplete], GOG checks its whole-file MD5 there), and a chunk whose last use
 * was written is no longer needed ([freeChunk] deletes it from the cache). With [allOrNothing] (GOG), a chunk that
 * could not be written everywhere counts nothing and fails, so the caller can fetch it again and place it again
 * without counting a position twice. Without it (Epic, as before), each position written counts, and only a
 * download that was stopped fails.
 */
internal class ChunkAssembler<F, C>(
    files: List<F>,
    chunksOf: (F) -> List<C>,
    private val chunkId: (C) -> String,
    private val fileKey: (F) -> String,
    private val allOrNothing: Boolean,
    private val write: suspend (file: F, chunk: C, position: Int) -> Result<*>,
    private val freeChunk: (chunkId: String) -> Unit,
    private val onFileComplete: (F) -> Unit = {},
) {
    private class Use<F, C>(val file: F, val chunk: C, val position: Int)

    /** Every position each chunk fills, by chunk id, in file order. */
    private val uses: Map<String, List<Use<F, C>>>

    /** The positions of each file still to be written, by [fileKey]. */
    private val pendingPositions = ConcurrentHashMap<String, AtomicInteger>()

    /** The uses each chunk has left, by chunk id. */
    private val usesLeft = ConcurrentHashMap<String, AtomicInteger>()

    init {
        val byChunk = LinkedHashMap<String, MutableList<Use<F, C>>>()
        files.forEach { file ->
            val chunks = chunksOf(file)
            pendingPositions[fileKey(file)] = AtomicInteger(chunks.size)
            chunks.forEachIndexed { position, chunk -> byChunk.getOrPut(chunkId(chunk)) { ArrayList() } += Use(file, chunk, position) }
        }
        uses = byChunk
        byChunk.forEach { (id, list) -> usesLeft[id] = AtomicInteger(list.size) }
    }

    /** How many positions use chunk [id] in all (0 for a chunk no file uses). */
    fun usesOf(id: String): Int = uses[id]?.size ?: 0

    /** How many uses chunk [id] has left. */
    fun usesLeft(id: String): Int = usesLeft[id]?.get() ?: 0

    /** The positions of file [key] still to be written. */
    fun pendingPositions(key: String): Int = pendingPositions[key]?.get() ?: 0

    /** Writes chunk [id] into every position that uses it and counts what was written (see the class). */
    suspend fun place(id: String, isActive: () -> Boolean = { true }): Result<Unit> {
        if (!isActive()) return Result.failure(IllegalStateException("Download cancelled"))
        val list = uses[id].orEmpty()
        val written = ArrayList<Use<F, C>>(list.size)
        list.forEach { use -> if (write(use.file, use.chunk, use.position).isSuccess) written += use }
        if (allOrNothing && written.size < list.size) {
            return Result.failure(IllegalStateException("Assembled ${written.size}/${list.size} position(s) for chunk $id"))
        }
        written.groupBy { fileKey(it.file) }.forEach { (key, ofFile) ->
            if (pendingPositions[key]?.addAndGet(-ofFile.size) == 0) onFileComplete(ofFile.first().file)
        }
        if (written.isNotEmpty()) {
            val left = usesLeft[id]?.addAndGet(-written.size)
            if (left != null && left <= 0) freeChunk(id)
        }
        return Result.success(Unit)
    }
}
