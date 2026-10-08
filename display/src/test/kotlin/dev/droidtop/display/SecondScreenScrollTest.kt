package dev.droidtop.display

import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A vertical drag that starts on anything a companion page holds scrolls the page (Droidtop/tracker#328):
 * a rail capsule, a tappable tile, a heading with its own tap detector and an Android widget. A sideways
 * drag on a rail still scrolls the rail, and a tap still taps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecondScreenScrollTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var page: ScrollState
    private lateinit var rail: LazyListState
    private var tileClicks = 0

    private fun showPage() {
        rule.setContent {
            page = rememberScrollState()
            rail = rememberLazyListState()
            Column(Modifier.size(width = 400.dp, height = 400.dp).secondScreenScroll(page)) {
                LazyRow(state = rail, modifier = Modifier.fillMaxWidth().testTag("rail")) {
                    items(20) { index ->
                        Box(Modifier.size(width = 100.dp, height = 150.dp).clickable { }.testTag("card$index"))
                    }
                }
                Box(Modifier.fillMaxWidth().height(48.dp).clickable { tileClicks++ }.testTag("tile"))
                Box(Modifier.fillMaxWidth().height(48.dp).pointerInput(Unit) { detectTapGestures { } }.testTag("header"))
                AndroidView(
                    factory = { context -> View(context).apply { isClickable = true; setOnClickListener { } } },
                    modifier = Modifier.fillMaxWidth().height(120.dp).testTag("widget"),
                )
                Spacer(Modifier.height(1500.dp))
            }
        }
    }

    private fun dragUpFrom(tag: String, sideways: Float = 0f) {
        rule.onNodeWithTag(tag).performTouchInput {
            swipe(start = center, end = center + Offset(sideways, -250f), durationMillis = 200)
        }
        rule.waitForIdle()
    }

    @Test
    fun `a drag up starting on a rail capsule scrolls the page`() {
        showPage()
        dragUpFrom("card1")
        assertTrue(page.value > 0)
    }

    @Test
    fun `a thumb's arc on a rail capsule scrolls the page, not the rail`() {
        showPage()
        dragUpFrom("card1", sideways = -150f)
        assertTrue(page.value > 0)
        assertEquals(0, rail.firstVisibleItemIndex)
    }

    @Test
    fun `an arc that starts sideways on a rail capsule ends up scrolling the page`() {
        showPage()
        // Recorded on the console: sideways first, then curving up (the drag the rail used to keep).
        val path = listOf(
            Offset(20f, -2f), Offset(20f, -4f), Offset(20f, -6f), Offset(18f, -12f), Offset(14f, -20f),
            Offset(12f, -36f), Offset(10f, -50f), Offset(8f, -60f), Offset(6f, -60f), Offset(4f, -50f),
        )
        rule.onNodeWithTag("card1").performTouchInput {
            down(center)
            path.forEach { step -> moveBy(step) }
            up()
        }
        rule.waitForIdle()
        assertTrue(page.value > 0)
    }

    @Test
    fun `a drag up starting on a tappable tile scrolls the page`() {
        showPage()
        dragUpFrom("tile")
        assertTrue(page.value > 0)
        assertEquals(0, tileClicks)
    }

    @Test
    fun `a drag up starting on a heading with its own tap detector scrolls the page`() {
        showPage()
        dragUpFrom("header")
        assertTrue(page.value > 0)
    }

    @Test
    fun `a drag up starting on an Android widget scrolls the page`() {
        showPage()
        dragUpFrom("widget")
        assertTrue(page.value > 0)
    }

    @Test
    fun `a sideways drag on a rail scrolls the rail and leaves the page`() {
        showPage()
        rule.onNodeWithTag("card2").performTouchInput {
            swipe(start = center, end = center + Offset(-300f, 10f), durationMillis = 200)
        }
        rule.waitForIdle()
        assertEquals(0, page.value)
        assertTrue(rail.firstVisibleItemIndex > 0 || rail.firstVisibleItemScrollOffset > 0)
    }

    @Test
    fun `a tap still reaches the tile`() {
        showPage()
        rule.onNodeWithTag("tile").performClick()
        rule.waitForIdle()
        assertEquals(1, tileClicks)
        assertEquals(0, page.value)
    }

    @Test
    fun `the page takes a drag only past the slop and mostly up or down`() {
        assertTrue(pageTakesDrag(Offset(5f, 20f), slop = 8f))
        assertTrue(pageTakesDrag(Offset(-20f, -20f), slop = 8f))
        assertTrue(!pageTakesDrag(Offset(0f, 6f), slop = 8f))
        assertTrue(!pageTakesDrag(Offset(30f, 12f), slop = 8f))
    }
}
