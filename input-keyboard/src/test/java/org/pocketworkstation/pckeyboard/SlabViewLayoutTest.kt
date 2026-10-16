package org.pocketworkstation.pckeyboard

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The companion's Input keyboard at the sizes the RP5 gives it (Droidtop/tracker#567: it "moves things around, doesn't
 * scale properly, and gets cut off"). The panel is 1080x1920; the tab leaves the keyboard part of that, in portrait and
 * turned to landscape, at small and large font scales. At every size no key leaves the view, a row is never under the
 * minimum when the host gave the minimum height, labels never go under their minimum, and switching to the symbols
 * layer keeps every key the same height.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SlabViewLayoutTest {
    private val colors = SlabView.Colors(0, 1, 2, 3, 4, 5)

    private fun slab(width: Int, height: Int): SlabView {
        val view = SlabView(
            RuntimeEnvironment.getApplication(), Slab.Kind.PLAIN, Slab.Form.FIELD, Slab.EnterPurpose.ENTER, colors,
            { it.name }, {}, extras = true,
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        return view
    }

    private fun density() = RuntimeEnvironment.getApplication().resources.displayMetrics.density

    private fun assertInside(view: SlabView, case: String) {
        val rects = view.keyRects()
        assertTrue("$case: keys drawn", rects.isNotEmpty())
        rects.forEach { r ->
            assertTrue("$case: $r inside ${view.width}x${view.height}", r.left >= 0f && r.top >= 0f && r.right <= view.width + 0.5f && r.bottom <= view.height + 0.5f)
        }
    }

    /** Taps the first key of the bottom row: Symbols on the letters layer, Letters on the symbols layer. */
    private fun switchLayer(view: SlabView) {
        val key = view.keyRects().last { it.left == view.keyRects().minOf { r -> r.left } }
        val now = SystemClock.uptimeMillis()
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, key.centerX(), key.centerY(), 0))
    }

    private fun check(width: Int, height: Int, case: String) {
        val view = slab(width, height)
        assertInside(view, "$case letters")
        val rowLetters = view.keyRects().first().height()
        if (height >= SlabView.minimumHeight(density(), extras = true)) {
            assertTrue("$case: row $rowLetters under the minimum", rowLetters >= SlabView.MIN_ROW_DP * density() - 0.5f)
        }
        assertTrue("$case: label under the minimum", view.labelPx() >= SlabView.MIN_LABEL_SP * density() - 0.01f)
        assertEquals("$case: starts on letters", Slab.Layer.LETTERS, view.state.layer)
        switchLayer(view)
        assertEquals("$case: the bottom-left key opened symbols", Slab.Layer.SYMBOLS, view.state.layer)
        assertInside(view, "$case symbols")
        assertEquals("$case: a layer switch keeps the key height", rowLetters, view.keyRects().first().height(), 0.5f)
    }

    /** The heights the tab can give: the RP5's portrait and landscape shares, the reserved minimum, and less. */
    private fun heights(width: Int, tall: Int): List<Pair<Int, Int>> {
        val least = SlabView.minimumHeight(density(), extras = true)
        return listOf(width to tall, width to least, width to least / 2, width to 4000)
    }

    private fun sweep(label: String) {
        // Portrait: the 1080-wide panel, the keyboard's share of the tab. Landscape: 1920 wide, a short share.
        (heights(1080, 900) + heights(1920, 420)).forEach { (w, h) -> check(w, h, "$label ${w}x$h") }
    }

    @Test @Config(qualifiers = "w411dp-h731dp-xxhdpi")
    fun atTheRp5DensityEveryKeyFits() = sweep("xxhdpi 1.0")

    @Test @Config(qualifiers = "w411dp-h731dp-xhdpi")
    fun atALowerDensityEveryKeyFits() = sweep("xhdpi 1.0")

    @Test @Config(qualifiers = "w411dp-h731dp-xxhdpi")
    fun atASmallFontScaleEveryKeyFits() {
        RuntimeEnvironment.setFontScale(0.85f)
        sweep("xxhdpi 0.85")
    }

    @Test @Config(qualifiers = "w411dp-h731dp-xxhdpi")
    fun atLargeFontScalesEveryKeyFits() {
        RuntimeEnvironment.setFontScale(1.3f)
        sweep("xxhdpi 1.3")
        RuntimeEnvironment.setFontScale(2.0f)
        sweep("xxhdpi 2.0")
    }
}
