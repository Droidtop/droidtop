package dev.droidtop.runtime.windows

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WineWorkingDirectoryTest {

    @Test
    fun `missing game working directory falls back to the ImageFs root`() {
        val root = File(".")

        assertEquals(root, WineWorkingDirectory.resolve(null, root))
    }

    @Test
    fun `missing game and ImageFs directories produce an actionable error`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            WineWorkingDirectory.resolve(null, null)
        }

        assertEquals(
            "the game folder and Windows system folder are missing; run Set up Windows games in Settings",
            failure.message,
        )
    }
}
