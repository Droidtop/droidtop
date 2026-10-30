package dev.droidtop.library.settings

import dev.droidtop.runtime.power.PowerChoice
import dev.droidtop.runtime.power.PowerDecision
import dev.droidtop.runtime.power.PowerLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Power row's stepping and words (docs/SPEC.md "Power policy", Droidtop/tracker#841 slice 3b). */
class PowerRowTest {
    @Test
    fun `a press steps Auto, Saver, Standard, Performance, then Auto again`() {
        var pick: PowerChoice = PowerChoice.Auto
        val seen = (1..4).map { GamingSettingsCatalog.nextPowerPick(pick).also { pick = it } }
        assertEquals(
            listOf(
                PowerChoice.Fixed(PowerLevel.SAVER),
                PowerChoice.Fixed(PowerLevel.STANDARD),
                PowerChoice.Fixed(PowerLevel.PERFORMANCE),
                PowerChoice.Auto,
            ),
            seen,
        )
    }

    @Test
    fun `the row says the level in force, and why when it is held lower`() {
        val plain = PowerDecision(PowerLevel.PERFORMANCE, PowerLevel.PERFORMANCE, "This game's setting")
        val hot = PowerDecision(PowerLevel.PERFORMANCE, PowerLevel.STANDARD, "Getting hot", heatCapped = true)
        val fixed = PowerChoice.Fixed(PowerLevel.PERFORMANCE)
        assertEquals("Performance", GamingSettingsCatalog.powerValue(fixed, auto = false, decision = plain))
        assertEquals("Standard: Getting hot", GamingSettingsCatalog.powerValue(fixed, auto = false, decision = hot))
        assertEquals("Auto (off)", GamingSettingsCatalog.powerValue(PowerChoice.Auto, auto = false, decision = plain))
        assertEquals("Auto: Performance", GamingSettingsCatalog.powerValue(PowerChoice.Auto, auto = true, decision = plain))
        assertEquals("Auto: Standard, Getting hot", GamingSettingsCatalog.powerValue(PowerChoice.Auto, auto = true, decision = hot))
    }
}
