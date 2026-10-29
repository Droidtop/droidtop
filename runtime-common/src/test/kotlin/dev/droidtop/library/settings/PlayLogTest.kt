package dev.droidtop.library.settings

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** The "Your play" numbers (docs/SPEC.md 7f, Droidtop/tracker#597): per game, per system, this week and all time. */
class PlayLogTest {
    private val zone = ZoneId.of("UTC")
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `sessions add up per game, this week, all time, most played and per system`() {
        var log = emptyMap<String, GamePlay>()
        // Monday 2026-10-26 is the week's start; the 20th is the week before.
        log = PlayLog.add(log, "/roms/snes/Metroid.sfc", "Super Metroid", "Super Nintendo", at(20, 10), at(20, 12), zone)
        log = PlayLog.add(log, "/roms/snes/Metroid.sfc", "Super Metroid", "Super Nintendo", at(27, 10), at(27, 11), zone)
        log = PlayLog.add(log, "steam:620", "Portal 2", null, at(28, 20), at(28, 23), zone)
        log = PlayLog.add(log, "/roms/gba/Zelda.gba", "Minish Cap", "Game Boy Advance", at(28, 9), at(28, 9) + 30 * 60_000L, zone)

        val summary = PlayLog.summary(log, at(29, 12), zone)
        assertEquals(4 * 3600L + 1800L, summary.weekSeconds)
        assertEquals(6 * 3600L + 1800L, summary.allTimeSeconds)
        assertEquals(listOf("Super Metroid", "Portal 2", "Minish Cap"), summary.top.map { it.title })
        assertEquals(mapOf("Super Nintendo" to 3 * 3600L, PlayLog.NO_SYSTEM to 3 * 3600L, "Game Boy Advance" to 1800L), summary.bySystem.toMap())
        assertEquals("Game Boy Advance", summary.bySystem.last().first)
    }

    @Test
    fun `a session that ends before it starts adds nothing, and a later name wins`() {
        val log = PlayLog.add(emptyMap(), "steam:620", "Portal 2", null, at(28, 20), at(28, 20), zone)
        assertEquals(emptyMap<String, GamePlay>(), log)
        val renamed = PlayLog.add(PlayLog.add(log, "g", "Old", "PC", at(28, 1), at(28, 2), zone), "g", "New", null, at(28, 3), at(28, 4), zone)
        assertEquals("New", renamed["g"]?.title)
        assertEquals("PC", renamed["g"]?.system)
        assertEquals(7200L, renamed["g"]?.totalSeconds)
    }
}
