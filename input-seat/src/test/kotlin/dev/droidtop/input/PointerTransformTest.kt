package dev.droidtop.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The transform is the one part of desktop input where a wrong answer still
 * looks like it works: the cursor moves, it just does not go where the
 * finger went. So the cases here are the ones that would hide a scale
 * error — a view and an output of different sizes, differing aspect ratios,
 * and the far edges.
 */
class PointerTransformTest {

    private val stretched = PointerTransform(
        viewWidth = 1080,
        viewHeight = 2400,
        outputWidth = 1920,
        outputHeight = 1080,
        fit = SurfaceFit.STRETCH,
    )

    @Test
    fun `stretch maps the view centre to the output centre`() {
        val point = stretched.map(540f, 1200f)!!
        assertEquals(960f, point.x, 0.001f)
        assertEquals(540f, point.y, 0.001f)
    }

    @Test
    fun `stretch scales each axis independently`() {
        // A quarter across and three quarters down the view must land a
        // quarter across and three quarters down the output, even though
        // the two have very different aspect ratios.
        val point = stretched.map(270f, 1800f)!!
        assertEquals(480f, point.x, 0.001f)
        assertEquals(810f, point.y, 0.001f)
    }

    @Test
    fun `stretch reports the extent it scaled into`() {
        val point = stretched.map(0f, 0f)!!
        assertEquals(1920, point.extentWidth)
        assertEquals(1080, point.extentHeight)
    }

    @Test
    fun `stretch keeps the far edge inside the output`() {
        // The protocol's extent is exclusive, so the bottom-right corner of
        // the view must not produce x == extentWidth.
        val point = stretched.map(1080f, 2400f)!!
        assertEquals(1919f, point.x, 0.001f)
        assertEquals(1079f, point.y, 0.001f)
    }

    @Test
    fun `stretch clamps a coordinate that ran off the surface`() {
        val point = stretched.map(-50f, 9000f)!!
        assertEquals(0f, point.x, 0.001f)
        assertEquals(1079f, point.y, 0.001f)
    }

    @Test
    fun `no geometry yet means no injection`() {
        assertNull(PointerTransform(0, 0, 1920, 1080).map(10f, 10f))
        assertNull(PointerTransform(1080, 2400, 0, 0).map(10f, 10f))
    }

    @Test
    fun `letterbox centres the output and keeps its aspect ratio`() {
        // 1920x1080 into a 1080x2400 view fits by width: scale 0.5625,
        // content 1080x607.5, so 896.25px of bar above and below.
        val transform = PointerTransform(1080, 2400, 1920, 1080, SurfaceFit.LETTERBOX)

        val topLeft = transform.map(0f, 896.25f)!!
        assertEquals(0f, topLeft.x, 0.01f)
        assertEquals(0f, topLeft.y, 0.01f)

        val centre = transform.map(540f, 1200f)!!
        assertEquals(960f, centre.x, 0.01f)
        assertEquals(540f, centre.y, 0.01f)
    }

    @Test
    fun `letterbox refuses a touch that landed on a bar`() {
        val transform = PointerTransform(1080, 2400, 1920, 1080, SurfaceFit.LETTERBOX)
        assertNull(transform.map(540f, 100f))
        assertNull(transform.map(540f, 2300f))
    }

    @Test
    fun `stretch deltas use the same per-axis scale as positions`() {
        val (dx, dy) = stretched.mapDelta(108f, 240f)!!
        assertEquals(192f, dx, 0.001f)
        assertEquals(108f, dy, 0.001f)
    }

    @Test
    fun `letterbox deltas use the single uniform scale`() {
        val transform = PointerTransform(1080, 2400, 1920, 1080, SurfaceFit.LETTERBOX)
        val (dx, dy) = transform.mapDelta(54f, 54f)!!
        assertEquals(96f, dx, 0.01f)
        assertEquals(96f, dy, 0.01f)
    }
}
