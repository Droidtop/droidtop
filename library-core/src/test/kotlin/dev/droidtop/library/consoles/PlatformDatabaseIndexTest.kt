package dev.droidtop.library.consoles

import java.io.File
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * The app composes the monolithic databases out of the per-file tree; the
 * platform repository's own generator composes the same documents in Python.
 * Two implementations of one format is exactly the arrangement that drifts
 * silently -- a refreshed database that parses but is subtly not what the repo
 * published -- so this runs both against the real pinned snapshot and demands
 * they agree.
 *
 * It also verifies the hashes: the index's sha256 for every file is recomputed
 * with the same helper the refresh uses to decide what to download, so a
 * mismatch in digest or encoding fails here rather than making every refresh
 * re-download the whole tree forever.
 */
class PlatformDatabaseIndexTest {
    private val repo = File("../vendor/droidtop-platforms")

    private fun index(): PlatformDatabaseIndex.Index =
        PlatformDatabaseIndex.parse(File(repo, "index.json").readText())

    @Test
    fun `composes every legacy database exactly as the repository generator does`() {
        assumeTrue("vendor/droidtop-platforms is not checked out", File(repo, "index.json").isFile)
        val index = index()
        val composedCollections = index.collections.values.filter { it.legacy != null }
        assertEquals("every consumed collection is present", 4, composedCollections.size)
        for (collection in composedCollections) {
            val entries = index.files.filter { it.collection == collection.name }
            val composed = PlatformDatabaseIndex.compose(
                collection,
                entries.map { it to File(repo, it.path).readText() },
            )
            val published = File(repo, "legacy/" + collection.legacy).readText()
            assertEquals(
                collection.name + " composes differently in Kotlin than in the generator",
                JSONObject(published).toString(),
                JSONObject(composed).toString(),
            )
        }
    }

    @Test
    fun `every file matches the sha256 the index published`() {
        assumeTrue("vendor/droidtop-platforms is not checked out", File(repo, "index.json").isFile)
        val index = index()
        assertEquals(
            "no file in the index is missing or mis-hashed",
            emptyList<String>(),
            index.files.filterNot { entry ->
                PlatformDatabaseTransport.sha256(File(repo, entry.path).readText()) == entry.sha256
            }.map { it.path },
        )
    }
}
