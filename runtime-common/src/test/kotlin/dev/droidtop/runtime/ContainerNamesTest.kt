package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ContainerNamesTest {
    private val dir: File = Files.createTempDirectory("names").toFile()

    private fun info(id: String, role: ContainerRole = ContainerRole.SIBLING, image: String? = null) =
        ContainerInfo(Container(id, role, ContainerBackend.PROOT, "/x/$id"), running = false, image = image)

    @Test
    fun `a new container is named after its image, numbered when taken`() {
        assertEquals("Debian", ContainerNames.defaultName(ContainerRole.SIBLING, "docker.io/library/debian:latest", emptyList()))
        assertEquals("Debian 2", ContainerNames.defaultName(ContainerRole.SIBLING, "docker.io/library/debian:latest", listOf("debian")))
        assertEquals("Void-glibc", ContainerNames.defaultName(ContainerRole.SIBLING, "ghcr.io/void-linux/void-glibc@sha256:ab", emptyList()))
        assertEquals("Desktop", ContainerNames.defaultName(ContainerRole.PRIMARY, "docker.io/library/alpine:latest", emptyList()))
        assertEquals("Container", ContainerNames.defaultName(ContainerRole.SIBLING, null, emptyList()))
    }

    @Test
    fun `listing fills in stored names and defaults for the unnamed`() {
        val names = ContainerNames(File(dir, ContainerNames.FILE_NAME))
        names.set("s1", "Work")
        val named = names.named(
            listOf(
                info("p", ContainerRole.PRIMARY),
                info("s1", image = "docker.io/library/debian:latest"),
                info("s2", image = "docker.io/library/debian:latest"),
                info("s3", image = "docker.io/library/debian:latest"),
            ),
        )
        assertEquals(listOf("Desktop", "Work", "Debian", "Debian 2"), named.map { it.displayName })
    }

    @Test
    fun `a rename is checked against the other names`() {
        val names = ContainerNames(File(dir, ContainerNames.FILE_NAME))
        val all = listOf(info("a", image = "docker.io/library/alpine:latest"), info("b", image = "docker.io/library/debian:latest"))
        names.rename("a", "  Tools ", all)
        assertEquals("Tools", names.get("a"))
        assertNotNull(runCatching { names.rename("b", "tools", all) }.exceptionOrNull())
        assertNotNull(ContainerNames.problemWith(" ", emptyList()))
        assertNotNull(ContainerNames.problemWith("x".repeat(ContainerNames.MAX_LENGTH + 1), emptyList()))
        assertNull(ContainerNames.problemWith("Debian", listOf("Alpine")))
        names.remove("a")
        assertNull(names.get("a"))
    }
}
