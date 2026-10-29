package dev.droidtop.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContainerUserTest {
    @get:Rule val tmp = TemporaryFolder()

    private val passwd = """
        root:x:0:0:root:/root:/bin/sh
        # a comment
        postgres:x:70:70:PostgreSQL:/var/lib/postgresql:/bin/sh
        nginx:x:101:101:nginx:/var/cache/nginx:/sbin/nologin
    """.trimIndent()
    private val group = "root:x:0:\nwww-data:x:82:nginx\npostgres:x:70:\n"

    private fun resolve(spec: String) = ContainerUser.resolve(ContainerUser.parse(spec)!!, passwd, group)

    @Test
    fun `a user is read as Docker reads it`() {
        assertEquals(ContainerUser.Spec("postgres", null), ContainerUser.parse("postgres"))
        assertEquals(ContainerUser.Spec("1000", "1000"), ContainerUser.parse(" 1000:1000 "))
        assertEquals("nginx:www-data", ContainerUser.parse("nginx:www-data").toString())
        assertNull(ContainerUser.parse(""))
        assertNull(ContainerUser.parse("a b"))
        assertNull(ContainerUser.parse("x;rm"))
        assertNull(ContainerUser.parse("-u"))
        assertTrue(ContainerUser.parse("0")!!.isRoot)
        assertTrue(ContainerUser.parse("root:root")!!.isRoot)
        assertTrue(!ContainerUser.parse("root:www-data")!!.isRoot)
    }

    @Test
    fun `a user resolves in the image's own passwd and group files`() {
        assertEquals(ContainerUser.Resolved(70, 70, "postgres"), resolve("postgres").getOrThrow())
        assertEquals(ContainerUser.Resolved(101, 82, "nginx"), resolve("nginx:www-data").getOrThrow())
        assertEquals(ContainerUser.Resolved(101, 5, "nginx"), resolve("101:5").getOrThrow())
        // A uid without a line runs with gid 0, as Docker's does.
        assertEquals(ContainerUser.Resolved(1000, 0, null), resolve("1000").getOrThrow())
        assertEquals("unable to find user redis: no matching entries in passwd file", resolve("redis").exceptionOrNull()!!.message)
        assertEquals("unable to find group staff: no matching entries in group file", resolve("nginx:staff").exceptionOrNull()!!.message)
    }

    @Test
    fun `the files are read from the tree, never through a link out of it`() {
        val root = tmp.newFolder("rootfs")
        File(root, "etc").mkdirs()
        File(root, "etc/passwd").writeText(passwd)
        assertEquals(70, ContainerUser.resolveIn(root, ContainerUser.Spec("postgres", null)).getOrThrow().uid)
        val outside = tmp.newFile("host-passwd").apply { writeText("redis:x:999:999::/:/bin/sh\n") }
        File(root, "etc/passwd").delete()
        java.nio.file.Files.createSymbolicLink(File(root, "etc/passwd").toPath(), outside.toPath())
        assertTrue(ContainerUser.resolveIn(root, ContainerUser.Spec("redis", null)).isFailure)
    }

    @Test
    fun `the scripts open their files first and switch user last, keeping the arguments and the environment`() {
        // A stand-in su that prints what it was given, first on the PATH.
        val bin = tmp.newFolder("bin")
        File(bin, "su").apply {
            writeText("#!/bin/sh\nprintf '[%s]' \"\$@\"; echo; echo \"switch=\${${ContainerUser.SWITCH_ENV}-unset} A=\$A\"\n")
            setExecutable(true)
        }
        fun run(env: Map<String, String>, argv: List<String>): String {
            val process = ProcessBuilder(argv).redirectErrorStream(true).apply {
                environment()["PATH"] = bin.path + ":" + (System.getenv("PATH") ?: "/usr/bin:/bin")
                environment()["A"] = "1"
                environment().putAll(env)
            }.start()
            return process.inputStream.bufferedReader().readText().also { process.waitFor() }
        }
        val argv = ContainerEnter.directCommand(listOf("printf", "<%s>", "a b", "\$(id)"))
        assertEquals("<a b><\$(id)>", run(emptyMap(), argv))
        assertEquals(
            "[-s][/bin/sh][postgres][-c][exec \"\$@\"][sh][printf][<%s>][a b][\$(id)]\nswitch=unset A=1\n",
            run(mapOf(ContainerUser.SWITCH_ENV to "postgres"), argv),
        )

        // The program's log is opened before the switch, so the user writes to it through what is already open.
        val log = File(tmp.root, "p.log")
        val config = ImageRunConfig(cmd = listOf("sh", "-c", "echo out; echo err >&2"))
        val program = ContainerProgram.command(config, "c1").toMutableList().also { it[4] = log.path }
        run(emptyMap(), program)
        val lines = log.readLines()
        assertTrue(lines.first().startsWith("--- started "))
        assertEquals(listOf("out", "err"), lines.drop(1))
    }
}
