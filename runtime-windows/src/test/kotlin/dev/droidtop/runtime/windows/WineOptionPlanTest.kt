package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Wine options apply to which build on which CPU, and what changing
 * one does to the others -- the fork's own coupling (docs/SPEC.md 5a), so the
 * rows never offer a value the launcher cannot run.
 */
class WineOptionPlanTest {

    private val arm64ec = "proton-10.0-arm64ec-2"
    private val x86Wine = "proton-9.0-x86_64"

    private fun settings(wine: String, emulator: String = WineOptionPlan.FEXCORE, dxwrapper: String = "dxvk", dxvk: String = "2.4.1-gplasync") =
        WineOptionPlan.Settings(
            wine = wine,
            emulator = emulator,
            box64 = "0.4.2",
            fexcore = "2605",
            driver = "wrapper",
            driverVersion = "System",
            dxwrapper = dxwrapper,
            dxvk = dxvk,
            vkd3d = "2.14.1",
        )

    @Test
    fun `an x86_64 device is offered only x86_64 builds from Wine 10 on`() {
        val known = listOf(arm64ec, x86Wine, "proton-10.0-4-x86_64-1", "proton-11.0-1-x86_64-1", "proton-11.0-1-arm64ec-1")
        assertEquals(
            listOf("proton-10.0-4-x86_64-1", "proton-11.0-1-x86_64-1"),
            WineOptionPlan.wineBuilds(x86Host = true, known = known),
        )
        assertEquals(known, WineOptionPlan.wineBuilds(x86Host = false, known = known))
    }

    @Test
    fun `the release is read from the build id, and an id without one is not ruled out`() {
        assertEquals(9, WineOptionPlan.wineMajor("proton-9.0-x86_64"))
        assertEquals(10, WineOptionPlan.wineMajor("proton-10.0-4-x86_64-1"))
        assertEquals(11, WineOptionPlan.wineMajor("Wine-11.2-x86_64"))
        assertEquals(null, WineOptionPlan.wineMajor("custom-x86_64"))
        assertFalse(WineOptionPlan.runsOnX86Host(x86Wine))
        assertTrue(WineOptionPlan.runsOnX86Host("custom-x86_64"))
        assertFalse(WineOptionPlan.runsOnX86Host("proton-11.0-1-arm64ec-1"))
    }

    @Test
    fun `FEXCore or Box64 is a choice only for an ARM Wine build on arm64`() {
        assertEquals(listOf("FEXCore", "Box64"), WineOptionPlan.emulators(x86Host = false, wine = arm64ec))
        assertTrue(WineOptionPlan.emulators(x86Host = false, wine = x86Wine).isEmpty())
        assertTrue(WineOptionPlan.emulators(x86Host = true, wine = x86Wine).isEmpty())
    }

    @Test
    fun `choosing an x86_64 build on arm64 makes Box64 the emulator`() {
        val next = WineOptionPlan.withWine(settings(arm64ec), x86Wine, x86Host = false, box64Choices = listOf("0.3.7", "0.4.2"))
        assertEquals(x86Wine, next.wine)
        assertEquals("Box64", next.emulator)
        assertEquals("0.4.2", next.box64)
    }

    @Test
    fun `moving onto an ARM build starts at FEXCore, and staying on one keeps the choice`() {
        val fromX86 = WineOptionPlan.withWine(settings(x86Wine, emulator = "Box64"), arm64ec, x86Host = false, box64Choices = listOf("0.4.2"))
        assertEquals("FEXCore", fromX86.emulator)
        val armToArm = WineOptionPlan.withWine(settings(arm64ec, emulator = "Box64"), "proton-11.0-1-arm64ec-1", x86Host = false, box64Choices = listOf("0.4.2"))
        assertEquals("Box64", armToArm.emulator)
    }

    @Test
    fun `a Box64 version missing from the new build's list falls back to its first`() {
        val next = WineOptionPlan.withWine(settings(x86Wine).copy(box64 = "0.3.2"), arm64ec, x86Host = false, box64Choices = listOf("0.4.2", "0.4.0"))
        assertEquals("0.4.2", next.box64)
    }

    @Test
    fun `FEXCore and Box64 versions show only where they are used`() {
        assertTrue(WineOptionPlan.usesFexcore(x86Host = false, wine = arm64ec))
        assertFalse(WineOptionPlan.usesFexcore(x86Host = false, wine = x86Wine))
        assertTrue(WineOptionPlan.usesBox64(x86Host = false, settings = settings(x86Wine)))
        assertFalse(WineOptionPlan.usesBox64(x86Host = false, settings = settings(arm64ec, emulator = "FEXCore")))
        assertTrue(WineOptionPlan.usesBox64(x86Host = false, settings = settings(arm64ec, emulator = "Box64")))
        assertFalse(WineOptionPlan.usesBox64(x86Host = true, settings = settings(x86Wine)))
    }

    @Test
    fun `VKD3D moves an old DXVK to one of 2_1 or later`() {
        val next = WineOptionPlan.withDxwrapper(settings(arm64ec, dxvk = "async-1.10.3"), "vkd3d", listOf("async-1.10.3", "1.11.1-sarek", "2.4.1-gplasync"))
        assertEquals("vkd3d", next.dxwrapper)
        assertEquals("2.4.1-gplasync", next.dxvk)
        assertEquals(listOf("2.4.1-gplasync"), WineOptionPlan.dxvkVersions("vkd3d", listOf("async-1.10.3", "1.11.1-sarek", "2.4.1-gplasync")))
    }

    @Test
    fun `a prefix's Direct3D spelling reads as its kind`() {
        assertEquals("dxvk", WineOptionPlan.dxwrapperKind("dxvk-2.4.1"))
        assertEquals("vkd3d", WineOptionPlan.dxwrapperKind("vkd3d"))
        assertEquals("wined3d", WineOptionPlan.dxwrapperKind("wined3d"))
        assertEquals("cnc-ddraw", WineOptionPlan.dxwrapperKind("cnc-ddraw"))
    }

    @Test
    fun `the driver build matters only for the Wrapper family on arm64`() {
        assertTrue(WineOptionPlan.usesDriverVersion(x86Host = false, driver = "wrapper-v2"))
        assertFalse(WineOptionPlan.usesDriverVersion(x86Host = false, driver = "vortek"))
        assertFalse(WineOptionPlan.usesDriverVersion(x86Host = true, driver = "wrapper"))
    }

    @Test
    fun `a game's own choices lay over the shared settings and read back as the difference`() {
        val shared = settings(arm64ec)
        val chosen = shared.copy(driver = "wrapper-v2", dxvk = "2.6.1-gplasync", emulator = "Box64")
        val choices = WineOptionPlan.diff(shared, chosen)
        assertEquals(mapOf("driver" to "wrapper-v2", "dxvk" to "2.6.1-gplasync", "emulator" to "Box64"), choices)
        assertEquals(chosen, WineOptionPlan.merge(shared, choices))
        assertTrue(choices.keys.all { it in WineOptionPlan.GAME_KEYS })
    }

    @Test
    fun `a game cannot override the Wine build, and the shared settings show through what it did not choose`() {
        val shared = settings(arm64ec)
        val merged = WineOptionPlan.merge(shared, mapOf("wine" to x86Wine, "dxvk" to "1.10.3"))
        assertEquals(arm64ec, merged.wine)
        assertEquals("1.10.3", merged.dxvk)
        assertEquals(shared.driver, merged.driver)
        assertTrue(WineOptionPlan.diff(shared, shared.copy(wine = x86Wine)).isEmpty())
    }
}
