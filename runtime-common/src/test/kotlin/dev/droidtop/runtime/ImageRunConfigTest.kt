package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/SPEC.md 3 "The image's own program" (Droidtop/tracker#555 D6a): an image's program, as Docker runs it. */
class ImageRunConfigTest {
    // nginx's config, trimmed to what is read.
    private val nginx = """
        {"architecture":"arm64","config":{"Env":["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin","NGINX_VERSION=1.27.2"],
        "Entrypoint":["/docker-entrypoint.sh"],"Cmd":["nginx","-g","daemon off;"],"WorkingDir":"","User":""},"rootfs":{"type":"layers"}}
    """.trimIndent()

    @Test
    fun `an image's program is its entrypoint then its command`() {
        val config = ImageRunConfig.fromImageConfig(nginx)
        assertEquals(listOf("/docker-entrypoint.sh", "nginx", "-g", "daemon off;"), config.program())
        assertEquals("1.27.2", config.environment()["NGINX_VERSION"])
        assertEquals(null, config.workingDir)
        assertTrue(config.runsAsRoot)
    }

    @Test
    fun `arguments replace the command, never the entrypoint`() {
        assertEquals(listOf("/docker-entrypoint.sh", "nginx", "-t"), ImageRunConfig.fromImageConfig(nginx).program(listOf("nginx", "-t")))
    }

    @Test
    fun `a base image names no program, and a null Cmd is empty`() {
        val alpine = """{"config":{"Env":["PATH=/bin"],"Cmd":null}}"""
        assertEquals(emptyList<String>(), ImageRunConfig.fromImageConfig(alpine).program())
        assertEquals(ImageRunConfig(), ImageRunConfig.fromImageConfig("not json"))
        assertEquals(ImageRunConfig(), ImageRunConfig.fromImageConfig("""{"rootfs":{}}"""))
    }

    @Test
    fun `the kept form reads back the same`() {
        val config = ImageRunConfig(listOf("A=1", "B"), listOf("/e"), listOf("run", "it"), "/srv", "1000:1000")
        assertEquals(config, ImageRunConfig.fromImageConfig(ImageRunConfig.encode(config)))
        assertEquals(mapOf("A" to "1", "B" to ""), config.environment())
        assertFalse(config.runsAsRoot)
    }

    @Test
    fun `the program reads as a shell line`() {
        assertEquals("/docker-entrypoint.sh nginx -g 'daemon off;'", ImageRunConfig.shellWords(listOf("/docker-entrypoint.sh", "nginx", "-g", "daemon off;")))
        assertEquals("echo 'it'\\''s'", ImageRunConfig.shellWords(listOf("echo", "it's")))
    }
}
