package dev.droidtop.library

import dev.droidtop.library.stores.StoreLaunch
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class StoreLaunchResolverTest {
    @Test
    fun `the store program keeps arguments and environment without duplicating the person's options`() {
        val root = Files.createTempDirectory("store-launch-").toFile()
        val exe = File(root, "Game.exe").apply { writeBytes(byteArrayOf(0x4d, 0x5a)) }
        val store = StoreLaunch(exe, root, listOf("--from-store"), mapOf("EALaunchEnv" to "production"))
        val launch = WindowsLaunchResolver.select(WineGameSettings(launchOptions = "--from-person"), root, store)!!
        assertEquals(exe, launch.executable)
        assertEquals(listOf("--from-store", "--from-person"), launch.arguments)
        assertEquals(store.environment, launch.environment)
    }

    @Test
    fun `choosing an executable by hand does not silently drop its required store environment`() {
        val root = Files.createTempDirectory("store-manual-launch-").toFile()
        val original = File(root, "Original.exe").apply { writeBytes(byteArrayOf(0x4d, 0x5a)) }
        val manual = File(root, "Manual.exe").apply { writeBytes(byteArrayOf(0x4d, 0x5a)) }
        val store = StoreLaunch(original, root, listOf("--original-only"), mapOf("OriginSessionKey" to "ephemeral-session"))
        val launch = WindowsLaunchResolver.select(WineGameSettings(executable = manual.name, arguments = listOf("--manual")), root, store)!!
        assertEquals(manual.canonicalFile, launch.executable)
        assertEquals(listOf("--manual"), launch.arguments)
        assertEquals(store.environment, launch.environment)
    }

    @Test
    fun `a missing store program reports the missing install rather than detecting another program`() {
        val root = Files.createTempDirectory("store-missing-launch-").toFile()
        try {
            WindowsLaunchResolver.select(null, root, StoreLaunch(File(root, "Missing.exe"), root))
            fail("The store's missing executable must be reported")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("Verify or reinstall"))
        }
    }
}
