package dev.droidtop.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which pointer call a surface's absolute motion takes (Droidtop/tracker#908, step D3). */
class PointerTargetTest {
    @Test
    fun `the one-screen desktop keeps the unnamed call`() {
        assertEquals(PointerTarget.Layout, PointerTarget.choose(primary = true, surfaceOutput = "HEADLESS-1", outputCount = 1))
        assertEquals(PointerTarget.Layout, PointerTarget.choose(primary = true, surfaceOutput = "", outputCount = 1))
        assertEquals(PointerTarget.Layout, PointerTarget.choose(primary = true, surfaceOutput = null, outputCount = 0))
    }

    @Test
    fun `with a second output the primary names its own`() {
        assertEquals(PointerTarget.Output("HEADLESS-1"), PointerTarget.choose(primary = true, surfaceOutput = "HEADLESS-1", outputCount = 2))
        // A primary the compositor never named has no name to give.
        assertEquals(PointerTarget.Layout, PointerTarget.choose(primary = true, surfaceOutput = "", outputCount = 2))
    }

    @Test
    fun `a second surface takes its output by name, and never the unnamed call`() {
        assertEquals(PointerTarget.Output("HEADLESS-2"), PointerTarget.choose(primary = false, surfaceOutput = "HEADLESS-2", outputCount = 2))
        assertEquals(PointerTarget.Nowhere, PointerTarget.choose(primary = false, surfaceOutput = null, outputCount = 2))
        assertEquals(PointerTarget.Nowhere, PointerTarget.choose(primary = false, surfaceOutput = null, outputCount = 1))
        assertEquals(PointerTarget.Nowhere, PointerTarget.choose(primary = false, surfaceOutput = "", outputCount = 2))
    }

    private fun router(bridge: FakeHostBridge) = DesktopInputRouter().apply {
        seat = InputSeat(bridge)
        transform = PointerTransform(viewWidth = 1000, viewHeight = 500, outputWidth = 2000, outputHeight = 1000)
    }

    @Test
    fun `the router sends the unnamed call by default`() {
        val bridge = FakeHostBridge()
        router(bridge).pointAt(InputSource.TOUCH, 250f, 100f)
        assertEquals(listOf(listOf<Any>(500.0, 200.0, 2000, 1000)), bridge.pointerAbsolutes)
        assertTrue(bridge.namedAbsolutes.isEmpty())
    }

    @Test
    fun `the router names the output it is given`() {
        val bridge = FakeHostBridge()
        router(bridge).apply { pointerTarget = PointerTarget.Output("HEADLESS-2") }.pointAt(InputSource.TOUCH, 250f, 100f)
        assertEquals(listOf(listOf<Any>("HEADLESS-2", 500.0, 200.0, 2000, 1000)), bridge.namedAbsolutes)
        assertTrue(bridge.pointerAbsolutes.isEmpty())
    }

    @Test
    fun `the router sends nothing with no output, before layout, or without a seat`() {
        val bridge = FakeHostBridge()
        router(bridge).apply { pointerTarget = PointerTarget.Nowhere }.pointAt(InputSource.TOUCH, 250f, 100f)
        router(bridge).apply { transform = null }.pointAt(InputSource.TOUCH, 250f, 100f)
        router(bridge).apply { seat = null }.pointAt(InputSource.TOUCH, 250f, 100f)
        assertTrue(bridge.pointerAbsolutes.isEmpty())
        assertTrue(bridge.namedAbsolutes.isEmpty())
    }
}
