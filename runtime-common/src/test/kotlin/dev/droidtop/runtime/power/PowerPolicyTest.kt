package dev.droidtop.runtime.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The power policy's core (docs/SPEC.md "Power policy", Droidtop/tracker#841 slice 2). */
class PowerPolicyTest {
    private val cool = HeatReading(headroom = 0.3f, status = 0, batteryC = 30f)
    private val switchGame = PowerTarget(game = "zelda", emulator = "org.eden", platform = "switch")

    private fun resolve(
        target: PowerTarget = PowerTarget(),
        rules: List<PowerRule> = emptyList(),
        heat: HeatReading = cool,
        hasMax: Boolean = true,
        personalCap: PowerLevel? = null,
        battery: Int? = 80,
        charging: Boolean = false,
        screenOn: Boolean = true,
        desktopLoad: PowerLevel? = null,
    ) = PowerResolver.resolve(
        PowerInputs(
            target = target, rules = rules, hasMax = hasMax, heat = heat, personalCap = personalCap,
            batteryPercent = battery, charging = charging, screenOn = screenOn, desktopLoad = desktopLoad,
        ),
    )

    @Test
    fun `at rest is Saver and a game no rule names is Standard`() {
        assertEquals(PowerLevel.SAVER, resolve().effective)
        assertEquals(PowerLevel.STANDARD, resolve(target = switchGame).effective)
    }

    @Test
    fun `the most specific rule that names a level wins, and Auto keeps looking`() {
        val rules = listOf(
            PowerRule(PowerScope.PLATFORM, "switch", PowerChoice.Fixed(PowerLevel.PERFORMANCE)),
            PowerRule(PowerScope.EMULATOR, "org.eden", PowerChoice.Auto),
        )
        assertEquals(PowerLevel.PERFORMANCE, resolve(target = switchGame, rules = rules).effective)
        val withGame = rules + PowerRule(PowerScope.GAME, "zelda", PowerChoice.Fixed(PowerLevel.SAVER))
        val decision = resolve(target = switchGame, rules = withGame)
        assertEquals(PowerLevel.SAVER, decision.effective)
        assertEquals("This game's setting", decision.why)
    }

    @Test
    fun `Max falls to Performance on a device without it`() {
        val rules = listOf(PowerRule(PowerScope.GAME, "zelda", PowerChoice.Fixed(PowerLevel.MAX)))
        assertEquals(PowerLevel.MAX, resolve(target = switchGame, rules = rules).effective)
        assertEquals(PowerLevel.PERFORMANCE, resolve(target = switchGame, rules = rules, hasMax = false).effective)
    }

    @Test
    fun `heat caps every level, a fixed pick included`() {
        val pick = listOf(PowerRule(PowerScope.GAME, "zelda", PowerChoice.Fixed(PowerLevel.MAX)))
        assertEquals(PowerLevel.PERFORMANCE, resolve(switchGame, pick, HeatReading(headroom = 0.75f)).effective)
        val hot = resolve(switchGame, pick, HeatReading(headroom = 0.9f))
        assertEquals(PowerLevel.PERFORMANCE, hot.effective)
        assertTrue(hot.heatCapped)
        assertEquals(PowerLevel.STANDARD, resolve(switchGame, pick, HeatReading(headroom = 1.2f)).effective)
        assertEquals(PowerLevel.STANDARD, resolve(switchGame, pick, HeatReading(status = 2)).effective)
        assertEquals(PowerLevel.SAVER, resolve(switchGame, pick, HeatReading(status = 3)).effective)
    }

    @Test
    fun `without Android's headroom, battery temperature and its rise cap instead`() {
        val pick = listOf(PowerRule(PowerScope.GAME, "zelda", PowerChoice.Fixed(PowerLevel.PERFORMANCE)))
        assertEquals(PowerLevel.PERFORMANCE, resolve(switchGame, pick, HeatReading(batteryC = 41f)).effective)
        assertEquals(PowerLevel.STANDARD, resolve(switchGame, pick, HeatReading(batteryC = 42.5f)).effective)
        assertEquals(PowerLevel.STANDARD, resolve(switchGame, pick, HeatReading(batteryC = 46f)).effective)
        assertEquals(PowerLevel.STANDARD, resolve(switchGame, pick, HeatReading(batteryC = 35f, risePerMinute = 1.5f)).effective)
        assertEquals(PowerLevel.PERFORMANCE, resolve(switchGame, pick, HeatReading(batteryC = 35f, risePerMinute = 0.2f)).effective)
    }

    @Test
    fun `no heat reading at all means nothing above Standard`() {
        val pick = listOf(PowerRule(PowerScope.GAME, "zelda", PowerChoice.Fixed(PowerLevel.PERFORMANCE)))
        val decision = resolve(switchGame, pick, HeatReading())
        assertEquals(PowerLevel.STANDARD, decision.effective)
        assertEquals("No heat reading", decision.why)
    }

    @Test
    fun `the person's own heat limit only lowers`() {
        val pick = listOf(PowerRule(PowerScope.GAME, "zelda", PowerChoice.Fixed(PowerLevel.PERFORMANCE)))
        assertEquals(PowerLevel.STANDARD, resolve(switchGame, pick, personalCap = PowerLevel.STANDARD).effective)
        assertEquals(PowerLevel.PERFORMANCE, resolve(switchGame, pick, personalCap = PowerLevel.MAX).effective)
    }

    @Test
    fun `a low battery not charging caps at Standard, and the screen off at Saver unless the Desktop is busy`() {
        val pick = listOf(PowerRule(PowerScope.GAME, "zelda", PowerChoice.Fixed(PowerLevel.PERFORMANCE)))
        assertEquals(PowerLevel.STANDARD, resolve(switchGame, pick, battery = 12).effective)
        assertEquals(PowerLevel.PERFORMANCE, resolve(switchGame, pick, battery = 12, charging = true).effective)
        assertEquals(PowerLevel.SAVER, resolve(switchGame, pick, screenOn = false).effective)
        val busy = resolve(screenOn = false, desktopLoad = PowerLevel.PERFORMANCE)
        assertEquals(PowerLevel.PERFORMANCE, busy.effective)
        assertEquals("Desktop is busy", busy.why)
    }

    @Test
    fun `a rise waits 8 seconds, a fall 30, and heat applies at once`() {
        val up = PowerDecision(PowerLevel.PERFORMANCE, PowerLevel.PERFORMANCE, "rule")
        var state = PowerGovernor.step(null, PowerDecision(PowerLevel.STANDARD, PowerLevel.STANDARD, "x"), 0)
        state = PowerGovernor.step(state, up, 1_000)
        assertEquals(PowerLevel.STANDARD, state.applied)
        state = PowerGovernor.step(state, up, 9_500)
        assertEquals(PowerLevel.PERFORMANCE, state.applied)

        val down = PowerDecision(PowerLevel.STANDARD, PowerLevel.STANDARD, "x")
        var falling = PowerGovernor.step(state, down, 10_000)
        assertEquals(PowerLevel.PERFORMANCE, falling.applied)
        falling = PowerGovernor.step(falling, down, 41_000)
        assertEquals(PowerLevel.STANDARD, falling.applied)

        val hot = PowerDecision(PowerLevel.PERFORMANCE, PowerLevel.STANDARD, "Heat is high", heatCapped = true)
        assertEquals(PowerLevel.STANDARD, PowerGovernor.step(state, hot, 10_000).applied)
        assertFalse(down.heatCapped)
    }
}
