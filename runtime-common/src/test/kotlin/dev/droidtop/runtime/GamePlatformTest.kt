package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamePlatformTest {
    @Test
    fun `prefers a Linux depot over a Windows depot`() {
        val linux = GameDepotOption(depotId = 1, platform = GameDepotPlatform.LINUX)
        val windows = GameDepotOption(depotId = 2, platform = GameDepotPlatform.WINDOWS)

        assertEquals(linux, selectBestDepot(listOf(windows, linux)))
        assertEquals(linux, selectBestDepot(listOf(linux, windows)))
    }

    @Test
    fun `falls back to Windows when there's no Linux depot`() {
        val windows = GameDepotOption(depotId = 2, platform = GameDepotPlatform.WINDOWS)
        val macos = GameDepotOption(depotId = 3, platform = GameDepotPlatform.MACOS)

        assertEquals(windows, selectBestDepot(listOf(macos, windows)))
    }

    @Test
    fun `never picks macOS`() {
        val macos = GameDepotOption(depotId = 3, platform = GameDepotPlatform.MACOS)
        assertNull(selectBestDepot(listOf(macos)))
    }

    @Test
    fun `empty options returns null`() {
        assertNull(selectBestDepot(emptyList()))
    }
}
