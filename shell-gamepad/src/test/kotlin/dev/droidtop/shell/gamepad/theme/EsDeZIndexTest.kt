package dev.droidtop.shell.gamepad.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins ES-DE's own per-element-type default zIndex values, the ones each
 * view passes to `setDefaultZIndex` when it builds a component
 * (GamelistView.cpp:176-403, SystemView.cpp:596-819). An element with no
 * `zIndex` in the theme gets these, not zero.
 */
class EsDeZIndexTest {
    @Test
    fun `image and video default to 30`() {
        assertEquals(30f, defaultZIndexOf("image"))
        assertEquals(30f, defaultZIndexOf("video"))
    }

    @Test
    fun `animation and badges default to 35`() {
        assertEquals(35f, defaultZIndexOf("animation"))
        assertEquals(35f, defaultZIndexOf("badges"))
    }

    @Test
    fun `text and datetime default to 40`() {
        assertEquals(40f, defaultZIndexOf("text"))
        assertEquals(40f, defaultZIndexOf("datetime"))
    }

    @Test
    fun `gamelistinfo and rating default to 45`() {
        assertEquals(45f, defaultZIndexOf("gamelistinfo"))
        assertEquals(45f, defaultZIndexOf("rating"))
    }

    @Test
    fun `the primary browsing component defaults to 50`() {
        assertEquals(50f, defaultZIndexOf("carousel"))
        assertEquals(50f, defaultZIndexOf("grid"))
        assertEquals(50f, defaultZIndexOf("textlist"))
    }

    @Test
    fun `a type with no default of its own stays at zero`() {
        // gameselector, helpsystem, systemstatus, clock and sound are
        // never given one by either view.
        assertEquals(0f, defaultZIndexOf("gameselector"))
        assertEquals(0f, defaultZIndexOf("helpsystem"))
        assertEquals(0f, defaultZIndexOf("clock"))
    }

    @Test
    fun `decaffe's unlabelled gamelist metadata labels now sort above its background art`() {
        // decaffe's gamelist declares back/back2 zIndex 1, backart3 2 and
        // gamedisplay 3, while playtime2/publisher2/developer2/genre2/
        // players2/release2 declare none at all. At a default of 0 the
        // labels were painted under the art; at ES-DE's own 40 they are not.
        assertEquals(true, defaultZIndexOf("text") > 3f)
        assertEquals(true, defaultZIndexOf("rating") > 3f)
    }
}
