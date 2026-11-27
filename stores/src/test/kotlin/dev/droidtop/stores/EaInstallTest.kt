package dev.droidtop.stores

import dev.droidtop.stores.ea.EaInstall
import dev.droidtop.stores.ea.EaInstalls
import dev.droidtop.stores.ea.EaInstaller
import dev.droidtop.stores.ea.EaManifest
import dev.droidtop.stores.ea.EaPaths
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class EaInstallTest {
    @Test
    fun `installer XML identifies the full game, launch arguments and finishing command`() {
        val xml = """<DiPManifest><gameVersion version="2.5"/><launchers>
            <launcher><filePath>Trial.exe</filePath><trial>true</trial></launcher>
            <launcher><filePath>[HKEY_LOCAL_MACHINE\SOFTWARE\EA\Game\Install Dir]bin\Game.exe</filePath><parameters>-language "en US"</parameters><trial>false</trial></launcher>
            </launchers><touchup><executable><filePath>__Installer\Touchup.exe</filePath><parameters>install -silent</parameters></executable></touchup>
            <contentIDs><contentID>content-1</contentID></contentIDs>
            <localeInfo locale="en_US"><title>Game &amp; Expansion</title></localeInfo></DiPManifest>"""
        val manifest = EaManifest.parse(xml)
        assertEquals("2.5", manifest.version)
        assertEquals("bin/Game.exe", EaPaths.relative(manifest.launcher()!!.path))
        assertEquals(listOf("-language", "en US"), EaPaths.splitArgs(manifest.launcher()!!.parameters))
        assertEquals("__Installer\\Touchup.exe", manifest.touchupPath)
        assertEquals("install -silent", manifest.touchupParameters)
        assertEquals(listOf("content-1"), manifest.contentIds)
        assertEquals("Game & Expansion", manifest.title("fr_FR"))
        assertEquals(xml, EaManifest.decode(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + xml.toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun `installer metadata cannot name files outside its folder or read XML entities`() {
        assertEquals("", EaPaths.relative("../../outside.exe"))
        assertEquals("", EaPaths.relative("C:\\outside.exe"))
        try {
            EaManifest.parse("""<!DOCTYPE x [<!ENTITY stolen SYSTEM "file:///private">]><x>&stolen;</x>""")
            fail("External declarations are not installer metadata")
        } catch (_: IllegalArgumentException) { }
        val root = Files.createTempDirectory("ea-manifest-").toFile()
        val outside = File(root.parentFile, root.name + "-outside.exe").apply { writeText("outside") }
        assertNull(EaPaths.find(root, "../${outside.name}"))
    }

    @Test
    fun `install records retain stable identity and do not confuse a partial update with a new install`() {
        val row = EaInstall("content-1", "offer-2", "A game", "https://example.invalid/art", "/games/EA", "/games/EA/Game",
            complete = false, everInstalled = true, version = "1.0", sizeBytes = 123, executable = "bin/Game.exe",
            arguments = listOf("-language", "en US"), extra = mapOf("touchup" to "__Installer/Touchup.exe"))
        assertEquals(listOf(row), EaInstalls.decode(EaInstalls.encode(listOf(row))))
        assertFalse(row.complete)
        assertTrue(row.everInstalled)
        val request = EaInstaller.request("https://example.invalid/build.zip?signature=ephemeral", File("/games/EA/Game"))
        assertEquals(File("/games/EA/Game").absolutePath, request.getString("gameDir"))
        assertFalse(EaInstalls.encode(listOf(row)).contains("signature="))
    }

    @Test
    fun `only a direct child bearing this game's install record can be removed or resumed`() {
        val root = Files.createTempDirectory("ea-root-").toFile()
        val game = File(root, "Game").apply { mkdir() }
        val row = EaInstall("content-1", "offer-2", "Game", null, root.absolutePath, game.absolutePath)
        try { EaInstalls.ownedFolder(row); fail("A person's unrelated folder must not be adopted") } catch (_: IllegalStateException) { }
        File(game, EaInstalls.PROOF).writeText("content-1")
        assertEquals(game.canonicalFile, EaInstalls.ownedFolder(row))
        try { EaInstalls.ownedFolder(row.copy(path = root.absolutePath)); fail("The whole store root is not one install") } catch (_: IllegalStateException) { }
        try { EaInstalls.ownedFolder(row.copy(id = "another-game")); fail("A different game's record is not accepted") } catch (_: IllegalStateException) { }
    }
}
