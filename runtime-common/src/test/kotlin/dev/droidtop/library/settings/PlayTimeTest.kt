package dev.droidtop.library.settings

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayTimeTest {
    private val zone = ZoneId.of("UTC")

    private fun ms(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `the ledger round-trips and drops what it cannot read`() {
        val days = mapOf(20000L to 600L, 20001L to 30L)
        assertEquals(days, PlayTime.parse(PlayTime.format(days)))
        assertEquals(emptyMap<Long, Long>(), PlayTime.parse(null))
        assertEquals(mapOf(5L to 7L), PlayTime.parse("junk,5:7,x:1,6:0,9:-3"))
    }

    @Test
    fun `play across midnight is split between the two days`() {
        val days = PlayTime.add(emptyMap(), ms("2026-10-14", 23, 50), ms("2026-10-15", 0, 20), zone)
        assertEquals(10 * 60L, PlayTime.usedToday(days, ms("2026-10-14", 23, 59), zone))
        assertEquals(20 * 60L, PlayTime.usedToday(days, ms("2026-10-15", 8), zone))
    }

    @Test
    fun `the week runs from Monday and today counts only today`() {
        // 2026-10-14 is a Wednesday; Monday is the 12th, the previous Sunday the 11th.
        var days = PlayTime.add(emptyMap(), ms("2026-10-11", 10), ms("2026-10-11", 11), zone)
        days = PlayTime.add(days, ms("2026-10-12", 10), ms("2026-10-12", 10, 30), zone)
        days = PlayTime.add(days, ms("2026-10-14", 9), ms("2026-10-14", 9, 15), zone)
        val now = ms("2026-10-14", 12)
        assertEquals(15 * 60L, PlayTime.usedToday(days, now, zone))
        assertEquals(45 * 60L, PlayTime.usedThisWeek(days, now, zone))
        // On the Sunday itself the week still began on the Monday before it.
        assertEquals(60 * 60L, PlayTime.usedThisWeek(days, ms("2026-10-11", 23), zone))
    }

    @Test
    fun `old days fall out of the ledger`() {
        val old = PlayTime.add(emptyMap(), ms("2026-09-01", 10), ms("2026-09-01", 11), zone)
        val days = PlayTime.add(old, ms("2026-10-14", 10), ms("2026-10-14", 11), zone)
        assertEquals(1, days.size)
    }

    @Test
    fun `the tighter limit decides the time left, and no limit is no answer`() {
        assertEquals(null, PlayTime.remainingSeconds(PlayLimits(), 100, 100))
        val limits = PlayLimits(dailyMinutes = 60, weeklyMinutes = 120)
        assertEquals(40 * 60L, PlayTime.remainingSeconds(limits, 20 * 60L, 30 * 60L))
        // the week is the tighter one
        assertEquals(10 * 60L, PlayTime.remainingSeconds(limits, 20 * 60L, 110 * 60L))
        assertEquals(0L, PlayTime.remainingSeconds(limits, 61 * 60L, 0L))
        assertEquals(30 * 60L, PlayTime.remainingSeconds(PlayLimits(weeklyMinutes = 60), 0, 30 * 60L))
    }

    @Test
    fun `wording`() {
        assertEquals("No limit", PlayTime.limitLabel(0))
        assertEquals("30 min", PlayTime.limitLabel(30))
        assertEquals("2 h", PlayTime.limitLabel(120))
        assertEquals("1 h 30 min", PlayTime.limitLabel(90))
        assertEquals("Today 45 min of 2 h · This week 3 h 10 min of 10 h", PlayTime.summary(PlayLimits(120, 600), 45 * 60L, 190 * 60L))
        assertEquals("Today 45 min · This week 3 h 10 min", PlayTime.summary(PlayLimits(), 45 * 60L, 190 * 60L))
        assertEquals("Today 45 min of 2 h · This week 3 h 10 min", PlayTime.summary(PlayLimits(dailyMinutes = 120), 45 * 60L, 190 * 60L))
    }

    @Test
    fun `warn once, tell at the limit, quit after the grace`() {
        val e = PlayEnforcer(warnBeforeSeconds = 300, graceSeconds = 60)
        assertEquals(PlayStep.None, e.step(0, null))
        assertEquals(PlayStep.None, e.step(1_000, 3_600))
        assertEquals(PlayStep.Warn(5), e.step(2_000, 300))
        // once only
        assertEquals(PlayStep.None, e.step(17_000, 285))
        assertEquals(PlayStep.TimeUp(60), e.step(100_000, 0))
        assertEquals(PlayStep.None, e.step(130_000, 0))
        assertEquals(PlayStep.None, e.step(159_000, 0))
        assertEquals(PlayStep.Quit, e.step(160_000, 0))
        // asked again until the game is gone
        assertEquals(PlayStep.Quit, e.step(175_000, 0))
    }

    @Test
    fun `a game started past the limit is told and given the grace`() {
        val e = PlayEnforcer(warnBeforeSeconds = 300, graceSeconds = 60)
        assertEquals(PlayStep.TimeUp(60), e.step(0, 0))
        assertEquals(PlayStep.None, e.step(30_000, 0))
        assertEquals(PlayStep.Quit, e.step(60_000, 0))
    }

    @Test
    fun `more time added starts it over`() {
        val e = PlayEnforcer(warnBeforeSeconds = 300, graceSeconds = 60)
        assertEquals(PlayStep.TimeUp(60), e.step(0, 0))
        // the limit was raised
        assertEquals(PlayStep.None, e.step(10_000, 3_600))
        assertEquals(PlayStep.Warn(3), e.step(20_000, 150))
        assertTrue(e.step(30_000, 100) is PlayStep.None)
    }
}
