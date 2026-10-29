package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageListingTest {
    private fun info(id: String, name: String, digest: String?) =
        ContainerInfo(Container(id, ContainerRole.SIBLING, ContainerBackend.PROOT, "/r/$id"), running = false, digest = digest, name = name)

    @Test
    fun `each image lists the containers made from it, the newest image first`() {
        val nginx = OciImageStore.Entry("sha256:" + "a".repeat(64), "docker.io/library/nginx:latest", 1000, createdMs = 2_000)
        val alpine = OciImageStore.Entry("sha256:" + "b".repeat(64), "docker.io/library/alpine:3.20", 500, createdMs = 3_000)
        val undated = OciImageStore.Entry("sha256:" + "c".repeat(64), "ghcr.io/x/y:1", 10)
        val rows = ImageListing.rows(
            listOf(nginx, undated, alpine),
            listOf(info("c2", "web", nginx.digest), info("c1", "api", nginx.digest), info("c3", "old", null)),
        )
        assertEquals(listOf("alpine:3.20", "nginx:latest", "ghcr.io/x/y:1"), rows.map { it.name })
        assertEquals(listOf(emptyList(), listOf("api", "web"), emptyList<String>()), rows.map { it.usedBy })
    }

    @Test
    fun `Docker Hub's own prefixes are dropped, other registries kept`() {
        assertEquals("debian:bookworm", ImageListing.shortReference("docker.io/library/debian:bookworm"))
        assertEquals("linuxserver/foot:1", ImageListing.shortReference("docker.io/linuxserver/foot:1"))
        assertEquals("ghcr.io/a/b:2", ImageListing.shortReference("ghcr.io/a/b:2"))
    }
}
