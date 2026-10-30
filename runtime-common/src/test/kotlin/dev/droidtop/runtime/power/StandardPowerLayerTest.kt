package dev.droidtop.runtime.power

import dev.droidtop.runtime.systemstatus.GameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The standard layer's pure parts (docs/SPEC.md "Power policy", Droidtop/tracker#841 slice 3a). */
class StandardPowerLayerTest {
    @Test
    fun `each level maps to Android's game mode`() {
        assertEquals(GameMode.BATTERY, StandardPowerLayer.modeFor(PowerLevel.SAVER))
        assertEquals(GameMode.STANDARD, StandardPowerLayer.modeFor(PowerLevel.STANDARD))
        assertEquals(GameMode.PERFORMANCE, StandardPowerLayer.modeFor(PowerLevel.PERFORMANCE))
        assertEquals(GameMode.PERFORMANCE, StandardPowerLayer.modeFor(PowerLevel.MAX))
    }

    @Test
    fun `the read-back finds the package's mode by name or number, not another package's`() {
        val dump = """
            GameManagerService:
              Package: com.other.game
                Game Mode: performance
              Package: org.ppsspp.ppsspp
                Downscaling: 0.7
                Game Mode: 3
              Package: org.ppsspp.ppssppgold mode=performance
        """.trimIndent()
        assertEquals(GameMode.BATTERY, GameModeReadBack.parse(dump, "org.ppsspp.ppsspp"))
        assertEquals(GameMode.PERFORMANCE, GameModeReadBack.parse(dump, "com.other.game"))
        assertEquals(GameMode.PERFORMANCE, GameModeReadBack.parse(dump, "org.ppsspp.ppssppgold"))
        assertNull(GameModeReadBack.parse(dump, "com.absent"))
        assertNull(GameModeReadBack.parse("", "com.other.game"))
    }

    @Test
    fun `the journal keeps one line per package and reads back what it wrote`() {
        val entries = mapOf("org.ppsspp.ppsspp" to GameMode.STANDARD, "com.retroarch" to GameMode.BATTERY)
        assertEquals(entries, PowerJournal.decode(PowerJournal.encode(entries)))
        assertEquals(emptyMap<String, GameMode>(), PowerJournal.decode("garbage\n=\n"))
    }
}
