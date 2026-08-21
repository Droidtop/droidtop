package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Exercises real parsing of the exact bundled seed-list file (loaded from
 * the classpath — see runtime-common/build.gradle.kts wiring
 * `src/main/assets` into the test sourceSet's resources) so a malformed
 * `known-image-repositories.json` fails a fast JVM test instead of only
 * surfacing as a runtime crash the first time [BundledImageRepositories.load]
 * runs on-device.
 */
class ImageCatalogTest {
    private fun loadBundledSeedListText(): String =
        javaClass.classLoader!!.getResourceAsStream("known-image-repositories.json")!!
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `bundled seed list parses and has at least one PRIMARY entry`() {
        val list = BundledImageRepositories.parse(loadBundledSeedListText())

        assertTrue("seed list should have at least one repository", list.repositories.isNotEmpty())
        assertTrue(
            "seed list should have at least one PRIMARY (or BOTH) entry — DesktopSessionService.selectPrimaryImage() requires it",
            list.repositories.any { it.role == ImageCatalogRole.PRIMARY || it.role == ImageCatalogRole.BOTH },
        )
    }

    @Test
    fun `entry ids are unique`() {
        val list = BundledImageRepositories.parse(loadBundledSeedListText())
        val duplicates = list.repositories.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) fail("duplicate repository ids: $duplicates")
    }

    @Test
    fun `every entry claiming arm64Available=false has a note explaining why`() {
        val list = BundledImageRepositories.parse(loadBundledSeedListText())
        val unexplained = list.repositories.filter { !it.arm64Available && it.notes.isNullOrBlank() }
        if (unexplained.isNotEmpty()) {
            fail("entries marked arm64Available=false must explain why via `notes`: ${unexplained.map { it.id }}")
        }
    }

    @Test
    fun `every PRIMARY entry declares a compositorFamily and a non-NOT_APPLICABLE headlessSupport`() {
        val list = BundledImageRepositories.parse(loadBundledSeedListText())
        val incomplete = list.repositories.filter {
            it.role != ImageCatalogRole.SIBLING &&
                (it.compositorFamily == null || it.headlessSupport == HeadlessSupport.NOT_APPLICABLE)
        }
        if (incomplete.isNotEmpty()) {
            fail("PRIMARY/BOTH entries need a real compositorFamily + headlessSupport verdict: ${incomplete.map { it.id }}")
        }
    }

    @Test
    fun `no repository specifies a registry other than its declared default without a reason`() {
        // Not a strict rule, just a sanity check: every entry using a
        // non-default registry (e.g. ghcr.io for Void Linux) should have a
        // note explaining why, same discipline as arm64Available=false.
        val list = BundledImageRepositories.parse(loadBundledSeedListText())
        val unexplained = list.repositories.filter { it.registry != DEFAULT_REGISTRY && it.notes.isNullOrBlank() }
        if (unexplained.isNotEmpty()) {
            fail("entries using a non-default registry should explain why via `notes`: ${unexplained.map { it.id }}")
        }
    }

    @Test
    fun `ResolvedImage toRootfsImage builds registry-repository-tag with the resolved digest`() {
        val repo = KnownImageRepository(
            id = "test-repo",
            os = "alpine",
            role = ImageCatalogRole.SIBLING,
            repository = "library/alpine",
            officialSource = true,
            arm64Available = true,
        )
        val resolved = ResolvedImage(repository = repo, tag = "3.20", digest = "sha256:deadbeef")

        val image = resolved.toRootfsImage()

        assertEquals("docker.io/library/alpine:3.20", image.reference)
        assertEquals("sha256:deadbeef", image.digest)
    }

    /** Fake [ImageCatalogResolver] — no network calls — so selection logic (in :app's DesktopSessionService) is testable without a live registry. */
    private class FakeResolver(private val tagsByRepoId: Map<String, List<String>>) : ImageCatalogResolver {
        override suspend fun listTags(repository: KnownImageRepository): List<String> =
            tagsByRepoId[repository.id] ?: emptyList()

        override suspend fun resolve(repository: KnownImageRepository, tag: String): ResolvedImage =
            ResolvedImage(repository = repository, tag = tag, digest = "sha256:fake-$tag")
    }

    @Test
    fun `fake resolver round-trips list-then-resolve, for testing selection logic without a network call`() = kotlinx.coroutines.runBlocking {
        val repo = KnownImageRepository(
            id = "alpine",
            os = "alpine",
            role = ImageCatalogRole.SIBLING,
            repository = "library/alpine",
            officialSource = true,
            arm64Available = true,
        )
        val resolver = FakeResolver(mapOf("alpine" to listOf("3.20", "3.19", "edge")))

        val tags = resolver.listTags(repo)
        assertEquals(listOf("3.20", "3.19", "edge"), tags)

        val resolved = resolver.resolve(repo, tags.first())
        assertEquals("3.20", resolved.tag)
        assertEquals("docker.io/library/alpine:3.20", resolved.toRootfsImage().reference)
    }
}
