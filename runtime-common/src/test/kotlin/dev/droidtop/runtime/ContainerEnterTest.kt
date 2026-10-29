package dev.droidtop.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** docs/SPEC.md 3d "Entering another container" and "Exported commands" (Droidtop/tracker#555 D5, D7). */
class ContainerEnterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val htop = ExportedCommand("droidtop-sibling-1a2b3c4d", "Arch", "htop")

    @Test
    fun `a command name is a plain program name and never one of droidtop's helpers`() {
        listOf("htop", "python3", "g++", "7z", "node.js").forEach { assertTrue(it, ContainerEnter.validName(it)) }
        listOf("", "../bin/sh", "a b", "-rf", "x;y", "droidtop-open", "\$(id)", "a/b").forEach { assertFalse(it, ContainerEnter.validName(it)) }
    }

    @Test
    fun `a request names a token, and nothing else is a request`() {
        val token = htop.token
        assertEquals(token, ContainerEnter.requestToken("enter.$token.4242.1760000000000.d"))
        assertNull(ContainerEnter.requestToken(".enter.$token.4242.1760000000000"))
        assertNull(ContainerEnter.requestToken("enter.$token.4242.1760000000000"))
        assertNull(ContainerEnter.requestToken("enter.XYZ.1.2.d"))
        assertNull(ContainerEnter.requestToken("$token.4242.request"))
    }

    @Test
    fun `arguments are NUL-ended, kept as written, and bounded`() {
        assertEquals(listOf("-d", "10", "two words", ""), ContainerEnter.parseArgs("-d\u000010\u0000two words\u0000\u0000".toByteArray()))
        assertEquals(emptyList<String>(), ContainerEnter.parseArgs(ByteArray(0)))
        assertNull(ContainerEnter.parseArgs(ByteArray(ContainerEnter.MAX_ARGS_BYTES + 1)))
    }

    @Test
    fun `the command and its arguments are argv entries, never shell text`() {
        val argv = ContainerEnter.execCommand("/run/r/enter.x.d", "htop", listOf("; rm x", "\$(id)"), "/root")
        assertEquals(listOf("sh", "/run/r/enter.x.d", "htop", "; rm x", "\$(id)"), argv.drop(3))
        assertEquals(mapOf("DROIDTOP_CWD" to "/root"), ContainerEnter.execEnvironment("/root"))
        assertEquals(emptyMap<String, String>(), ContainerEnter.execEnvironment(null))
    }

    @Test
    fun `the wrapper runs the helper with its token and passes every argument on`() {
        val wrapper = ContainerEnter.wrapperScript(htop)
        assertTrue(wrapper.contains("exec sh ${ContainerEnter.HELPER} ${htop.token} \"\$@\"\n"))
    }

    @Test
    fun `the list survives a write and a read, and one name belongs to one container`() {
        val list = listOf(htop, ExportedCommand("droidtop-sibling-1a2b3c4d", "Arch", "python3"))
        assertEquals(list, ContainerEnter.decode(ContainerEnter.encode(list)))
        val other = ExportedCommand("droidtop-sibling-99", "Debian", "htop")
        assertEquals(listOf(other), ContainerEnter.with(listOf(htop), other, true))
        assertEquals(emptyList<ExportedCommand>(), ContainerEnter.with(listOf(htop), htop, false))
        assertEquals(listOf(other), ContainerEnter.without(list + other, htop.containerId))
        assertEquals(htop, ContainerEnter.forToken(list, htop.token))
        assertNull(ContainerEnter.forToken(list, "0123456789abcdef"))
    }

    @Test
    fun `saving writes the helper and an executable wrapper per name and removes the rest`() {
        val files = tmp.root
        val dir = ContainerEnter.hostCommandsDir(files).apply { mkdirs() }
        val stale = File(dir, "gone").apply { writeText("#!/bin/sh\n") }
        ContainerEnter.save(files, listOf(htop))
        val wrapper = File(dir, "htop")
        assertTrue(wrapper.isFile && wrapper.canExecute())
        assertFalse(stale.exists())
        assertTrue(ContainerEnter.hostHelper(files).readText().startsWith("#!/bin/sh"))
        assertEquals(listOf(htop), ContainerEnter.read(files))
    }

    @Test
    fun `droidtop-open, droidtop-view and xdg-open are commands, and an exported xdg-open replaces droidtop's`() {
        val files = tmp.root
        val dir = ContainerEnter.hostCommandsDir(files)
        ContainerEnter.save(files, listOf(htop))
        listOf("droidtop-open", "droidtop-view", "xdg-open").forEach { assertTrue(it, File(dir, it).let { f -> f.isFile && f.canExecute() }) }
        assertTrue(File(dir, "droidtop-view").readText().contains("exec sh ${ContainerLauncher.VIEW_HELPER} \"\$@\"\n"))
        assertTrue(File(dir, "xdg-open").readText().contains("exec sh ${ContainerLauncher.VIEW_HELPER} \"\$t\"\n"))
        val xdgOpen = ExportedCommand("droidtop-sibling-99", "Debian", "xdg-open")
        ContainerEnter.save(files, listOf(htop, xdgOpen))
        assertEquals(ContainerEnter.wrapperScript(xdgOpen), File(dir, "xdg-open").readText())
    }

    @Test
    fun `a container runs commands from the desktop unless switched off`() {
        val files = tmp.root
        assertTrue(ContainerEnter.allowed(files, htop.containerId))
        ContainerEnter.setAllowed(files, htop.containerId, false)
        assertFalse(ContainerEnter.allowed(files, htop.containerId))
        assertTrue(ContainerEnter.allowed(files, "droidtop-sibling-99"))
        ContainerEnter.setAllowed(files, htop.containerId, true)
        assertTrue(ContainerEnter.allowed(files, htop.containerId))
    }
}
