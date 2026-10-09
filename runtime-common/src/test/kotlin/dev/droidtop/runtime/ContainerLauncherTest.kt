package dev.droidtop.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerLauncherTest {
    @Test
    fun `a token is 16 hex digits, stable per id`() {
        val token = ContainerLauncher.token("engine:/storage/emulated/0/Games/Some Game (v1.2)")
        assertTrue(Regex("[0-9a-f]{16}").matches(token))
        assertEquals(token, ContainerLauncher.token("engine:/storage/emulated/0/Games/Some Game (v1.2)"))
        assertFalse(token == ContainerLauncher.token("engine:/storage/emulated/0/Games/Other"))
    }

    @Test
    fun `the entry runs the helper with the token and nothing else`() {
        val entry = ContainerLauncher.desktopEntry("0123456789abcdef", "Game: The \"Sequel\"\nPart 2", "/run/droidtop-shared-storage/primary/art/x.png")
        val lines = entry.lines()
        assertEquals("[Desktop Entry]", lines.first())
        assertTrue("Type=Application" in lines)
        assertTrue("Name=Game: The \"Sequel\"\\nPart 2" in lines)
        assertTrue("Exec=sh /run/droidtop-app-storage/desktop-launcher/bin/droidtop-open 0123456789abcdef" in lines)
        assertTrue("Icon=/run/droidtop-shared-storage/primary/art/x.png" in lines)
        assertTrue("Categories=Game;" in lines)
    }

    @Test
    fun `no icon line without local art`() {
        assertFalse(ContainerLauncher.desktopEntry("0123456789abcdef", "Game", null).contains("Icon="))
    }

    @Test
    fun `string values escape what the specification escapes`() {
        assertEquals("a\\\\b\\tc\\rd", ContainerLauncher.escapeValue("a\\b\tc\rd"))
        assertEquals("\\s lead", ContainerLauncher.escapeValue("  lead"))
    }

    @Test
    fun `only a whole request naming a token is one`() {
        assertEquals("0123456789abcdef", ContainerLauncher.requestToken("0123456789abcdef.4242.request"))
        assertNull(ContainerLauncher.requestToken(".0123456789abcdef.4242"))
        assertNull(ContainerLauncher.requestToken(".0123456789abcdef.4242.request"))
        assertNull(ContainerLauncher.requestToken("rm -rf.4242.request"))
        assertNull(ContainerLauncher.requestToken("0123456789ABCDEF.1.request"))
        assertNull(ContainerLauncher.requestToken("0123456789abcdef.1.txt"))
    }

    @Test
    fun `the helper refuses anything but a token and renames the request into place`() {
        val script = ContainerLauncher.helperScript()
        assertTrue(script.startsWith("#!/bin/sh\n"))
        assertTrue(script.contains("*[!0-9a-f]* | '') echo 'usage: droidtop-open <entry>' >&2; exit 2 ;;"))
        assertTrue(script.contains("dir=/run/droidtop-app-storage/desktop-launcher/requests"))
        assertTrue(script.trimEnd().lines().last().startsWith("mv -f "))
    }

    @Test
    fun `host paths sit under the files directory the containers see`() {
        val files = File("/data/user/0/dev.droidtop.app/files")
        assertEquals(
            ContainerLauncher.HELPER,
            ContainerLayout.hostStorageToContainerPath(files, ContainerLauncher.hostHelper(files)),
        )
        assertEquals(
            ContainerLauncher.REQUESTS_DIR,
            ContainerLayout.hostStorageToContainerPath(files, ContainerLauncher.hostRequestsDir(files)),
        )
        assertEquals(
            "${ContainerLauncher.DATA_DIR}/applications",
            ContainerLayout.hostStorageToContainerPath(files, ContainerLauncher.hostApplicationsDir(files)),
        )
    }
}
