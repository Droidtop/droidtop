package dev.droidtop.hostbridge

import dev.droidtop.runtime.DisplayOutput
import dev.droidtop.runtime.DisplayOutputKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The desktop session's extra outputs (Droidtop/tracker#908, step D2). */
class DesktopOutputsTest {
    private fun display(id: Int, width: Int = 1080, height: Int = 1920) = DisplayOutput(
        id = id.toString(),
        androidDisplayId = id,
        kind = if (id == 0) DisplayOutputKind.PRIMARY_SCREEN else DisplayOutputKind.SECOND_SCREEN,
        widthPx = width,
        heightPx = height,
    )

    private fun placement(name: String, x: Int, width: Int, height: Int, scale: Double = 1.0, enabled: Boolean = true) =
        OutputPlacement(name, enabled, x, 0, width, height, 0, scale)

    private val primary = DesktopOutput(display(0), "HEADLESS-1")

    @Test
    fun `the new output is the name that was not there before`() {
        assertEquals("HEADLESS-2", DesktopOutputs.newOutputName(listOf("HEADLESS-1"), listOf("HEADLESS-1", "HEADLESS-2")))
        assertNull(DesktopOutputs.newOutputName(listOf("HEADLESS-1"), listOf("HEADLESS-1")))
        // One that went meanwhile does not count, and the new one is found wherever it is listed.
        assertEquals("HEADLESS-3", DesktopOutputs.newOutputName(listOf("HEADLESS-1", "HEADLESS-2"), listOf("HEADLESS-3", "HEADLESS-1")))
    }

    @Test
    fun `a name that could carry another sway command is never taken`() {
        assertFalse(DesktopOutputs.isOutputName("HEADLESS-2; exit"))
        assertFalse(DesktopOutputs.isOutputName(""))
        assertNull(DesktopOutputs.newOutputName(emptyList(), listOf("a,b")))
        assertTrue(runCatching { DesktopOutputs.unplugCommand("x y") }.isFailure)
    }

    @Test
    fun `a new output goes right of the primary, in logical pixels`() {
        // The primary at 1920x1080 and scale 2.25 is 853 logical pixels wide (wlroots truncates).
        val placements = listOf(placement("HEADLESS-1", 0, 1920, 1080, 2.25), placement("HEADLESS-2", 0, 1080, 1920))
        assertEquals(853, DesktopOutputs.positionX(placements, "HEADLESS-2"))
        assertEquals("output HEADLESS-2 pos 853 0", DesktopOutputs.positionCommand("HEADLESS-2", 853))
    }

    @Test
    fun `a further output goes right of the rightmost, and a disabled one takes no room`() {
        val placements = listOf(
            placement("HEADLESS-1", 0, 1920, 1080),
            placement("HEADLESS-2", 1920, 1280, 720),
            placement("HEADLESS-3", 0, 800, 600, enabled = false),
            placement("HEADLESS-4", 0, 800, 600),
        )
        assertEquals(3200, DesktopOutputs.positionX(placements, "HEADLESS-4"))
        assertEquals(0, DesktopOutputs.positionX(listOf(placement("HEADLESS-2", 0, 800, 600)), "HEADLESS-2"))
    }

    @Test
    fun `an output is sized once the compositor reports the size asked`() {
        val placements = listOf(placement("HEADLESS-1", 0, 1920, 1080), placement("HEADLESS-2", 1920, 1080, 1920))
        assertTrue(DesktopOutputs.sized(placements, "HEADLESS-2", 1080, 1920))
        assertFalse(DesktopOutputs.sized(placements, "HEADLESS-2", 1920, 1080))
        assertFalse(DesktopOutputs.sized(placements, "HEADLESS-3", 1080, 1920))
    }

    @Test
    fun `extending adds a display once, and again replaces it`() {
        val once = DesktopOutputs.extended(listOf(primary), display(9), "HEADLESS-2")
        assertEquals(listOf(primary, DesktopOutput(display(9), "HEADLESS-2")), once)
        val resized = DesktopOutputs.extended(once, display(9, 1920, 1080), "HEADLESS-2")
        assertEquals(listOf(primary, DesktopOutput(display(9, 1920, 1080), "HEADLESS-2")), resized)
        assertEquals("HEADLESS-2", DesktopOutputs.outputFor(resized, 9)?.outputName)
        assertNull(DesktopOutputs.outputFor(resized, 4))
    }

    @Test
    fun `the primary is never replaced, found or removed as an extra output`() {
        val outputs = DesktopOutputs.extended(listOf(primary), display(0), "HEADLESS-2")
        assertEquals(listOf(primary), outputs)
        assertNull(DesktopOutputs.outputFor(listOf(primary), 0))
        assertEquals(listOf(primary), DesktopOutputs.retracted(listOf(primary), 0))
        assertEquals(emptyList<DesktopOutput>(), DesktopOutputs.extended(emptyList(), display(9), "HEADLESS-2"))
    }

    @Test
    fun `retracting forgets that display only, and twice is the same as once`() {
        val outputs = DesktopOutputs.extended(DesktopOutputs.extended(listOf(primary), display(9), "HEADLESS-2"), display(11), "HEADLESS-3")
        val retracted = DesktopOutputs.retracted(outputs, 9)
        assertEquals(listOf(primary, DesktopOutput(display(11), "HEADLESS-3")), retracted)
        assertEquals(retracted, DesktopOutputs.retracted(retracted, 9))
        assertEquals("output HEADLESS-2 unplug", DesktopOutputs.unplugCommand("HEADLESS-2"))
    }
}
