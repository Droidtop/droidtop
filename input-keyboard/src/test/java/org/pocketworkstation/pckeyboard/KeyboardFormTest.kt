package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** One-handed and split as pure geometry (Droidtop/tracker#342). */
class KeyboardFormTest {
    // Four keys of 250 in a row of 1000, the last two a space bar straddling nothing: x 0, 250, 500, 750.
    private fun row() = Triple(intArrayOf(0, 250, 500, 750), intArrayOf(250, 250, 250, 250), intArrayOf(0, 0, 0, 0))

    @Test fun theFullFormChangesNothing() {
        val (x, w, g) = row()
        assertEquals(1000, KeyboardForm.layout(x, w, g, 1000, KeyboardForm.FULL))
        assertEquals(listOf(0, 250, 500, 750), x.toList())
    }

    @Test fun oneHandedScalesEveryKeyAndTheTotal() {
        val (x, w, g) = row()
        val total = KeyboardForm.layout(x, w, g, 1000, KeyboardForm.LEFT)
        assertEquals(700, total)
        assertEquals(listOf(0, 175, 350, 525), x.toList())
        assertEquals(listOf(175, 175, 175, 175), w.toList())
    }

    @Test fun splitOpensAGapInTheMiddleAndKeepsTheFullWidth() {
        val (x, w, g) = row()
        val total = KeyboardForm.layout(x, w, g, 1000, KeyboardForm.SPLIT)
        assertEquals(1000, total)
        // Scaled to 80%: 0, 200, 400, 600 of width 200; the middle is 400, the gap 200.
        assertEquals(listOf(0, 200, 600, 800), x.toList())
        assertEquals(listOf(200, 200, 200, 200), w.toList())
    }

    @Test fun aKeyAcrossTheMiddleIsWidenedOverTheGap() {
        val x = intArrayOf(0, 150)
        val w = intArrayOf(150, 400)
        val g = intArrayOf(0, 0)
        // After 80% scaling key 2 is at 120 and 320 wide, well across the middle (280) of a 560 grid; the gap is 140.
        val total = KeyboardForm.layout(x, w, g, 700, KeyboardForm.SPLIT)
        assertEquals(700, total)
        assertEquals(listOf(0, 120), x.toList())
        assertEquals(listOf(120, 320 + 140), w.toList())
    }

    @Test fun keysThatOnlyTouchTheMiddleByRoundingKeepTheirOwnWidth() {
        // 14 columns of 71 across 994: the 7th key ends, and the 8th starts, a pixel off the middle after scaling.
        val x = IntArray(14) { it * 71 }
        val w = IntArray(14) { 71 }
        val g = IntArray(14)
        val total = KeyboardForm.layout(x, w, g, 994, KeyboardForm.SPLIT)
        assertEquals(setOf(57), w.toSet())
        // No key is wider than the others, and the left half and the right half are separated by the gap.
        assertEquals(199, x[7] - (x[6] + w[6]))
        assertEquals(994, total)
    }

    @Test fun formsAreNamedAndCycleBackToFull() {
        assertSame(KeyboardForm.SPLIT, KeyboardForm.of("split"))
        assertSame(KeyboardForm.FULL, KeyboardForm.of("nonsense"))
        assertSame(KeyboardForm.FULL, KeyboardForm.of(null))
        var form = KeyboardForm.FULL
        val seen = ArrayList<String>()
        repeat(4) {
            form = KeyboardForm.next(form)
            seen += form.id
        }
        assertEquals(listOf("split", "left", "right", "full"), seen)
    }

    // Droidtop/tracker#903 H1: one shape per orientation, split by default in landscape on a handheld.

    @Test fun landscapeOnAHandheldIsSplitUntilSomethingIsChosen() {
        assertSame(KeyboardForm.SPLIT, KeyboardForm.choose(null, null, landscape = true, handheld = true))
        assertSame(KeyboardForm.SPLIT, KeyboardForm.choose("auto", "auto", landscape = true, handheld = true))
        assertSame(KeyboardForm.FULL, KeyboardForm.choose(null, null, landscape = false, handheld = true))
        assertSame(KeyboardForm.FULL, KeyboardForm.choose(null, null, landscape = true, handheld = false))
    }

    @Test fun eachOrientationKeepsItsOwnChoice() {
        assertSame(KeyboardForm.FULL, KeyboardForm.choose("left", "full", landscape = true, handheld = true))
        assertSame(KeyboardForm.LEFT, KeyboardForm.choose("left", "full", landscape = false, handheld = true))
        // A portrait choice made after the migration doesn't reach an automatic landscape.
        assertSame(KeyboardForm.SPLIT, KeyboardForm.choose("right", null, landscape = true, handheld = true))
    }

    @Test fun theConsoleIsAHandheldAndADisplayIsNot() {
        // Retroid Pocket 5: 1920x1080 at about 400 ppi, a 2.7 inch short side.
        assertTrue(KeyboardForm.handheld(1920, 1080, 401f, 401f, smallestWidthDp = 720))
        // A 1080p 24 inch monitor at about 92 ppi: an 11.7 inch short side.
        assertFalse(KeyboardForm.handheld(1920, 1080, 92f, 92f, smallestWidthDp = 1080))
        // An 11 inch tablet, 2560x1600 at 274 ppi: a 5.8 inch short side.
        assertFalse(KeyboardForm.handheld(2560, 1600, 274f, 274f, smallestWidthDp = 800))
        // No believable dpi: the dp rule.
        assertTrue(KeyboardForm.handheld(1920, 1080, 0f, 0f, smallestWidthDp = 480))
        assertFalse(KeyboardForm.handheld(1920, 1080, 0f, 0f, smallestWidthDp = 720))
    }

    @Test fun aShapeChosenBeforeH1IsCarriedIntoLandscapeOnce() {
        assertEquals("split", KeyboardForm.carriedToLandscape("split", landscapeSet = false))
        assertEquals("right", KeyboardForm.carriedToLandscape("right", landscapeSet = false))
        // "full" may only be the settings screen's stored default, so landscape stays automatic.
        assertNull(KeyboardForm.carriedToLandscape("full", landscapeSet = false))
        assertNull(KeyboardForm.carriedToLandscape("split", landscapeSet = true))
        assertNull(KeyboardForm.carriedToLandscape(null, landscapeSet = false))
        assertNull(KeyboardForm.carriedToLandscape("nonsense", landscapeSet = false))
    }

    // Droidtop/tracker#903 H12c: the thumb split and the floating height.

    @Test fun aWideScreenGetsAThumbSplitWithTheHalvesAtTheEdges() {
        // A 10 inch wide tablet, 2560 px at 256 dpi: halves of 3 inches, a 40% gap.
        val thumb = KeyboardForm.thumb(2560, 256f)!!
        assertEquals("thumb", thumb.id)
        assertEquals(0.4f, thumb.gapFraction, 1e-5f)
        assertEquals(0.6f, thumb.widthFraction, 1e-5f)
        // H1's split code lays it out: 1000 px, keys scaled to 60%, the right half moved past a 400 px gap.
        val (x, w, g) = row()
        assertEquals(1000, KeyboardForm.layout(x, w, g, 1000, thumb))
        assertEquals(listOf(0, 150, 700, 850), x.toList())
        assertEquals(listOf(150, 150, 150, 150), w.toList())
    }

    @Test fun aHandheldOrAPhoneHasNoThumbSplit() {
        // The Retroid Pocket 5 sideways: 4.8 inches.
        assertNull(KeyboardForm.thumb(1920, 401f))
        // Just over the threshold opens a gap a little wider than H1's.
        val inches = KeyboardForm.THUMB_MIN_WIDTH_INCHES
        assertNull(KeyboardForm.thumb((inches * 200).toInt(), 200f))
        val just = KeyboardForm.thumb(((inches + 0.5f) * 200).toInt(), 200f)!!
        assertTrue(just.gapFraction > KeyboardForm.SPLIT.gapFraction)
        // A huge display keeps the halves from shrinking to slivers.
        assertEquals(KeyboardForm.THUMB_MAX_GAP, KeyboardForm.thumb(10000, 100f)!!.gapFraction, 0f)
        assertNull(KeyboardForm.thumb(2560, 0f))
    }

    @Test fun theFloatingHeightScalesEveryRow() {
        val y = intArrayOf(0, 100, 200)
        val h = intArrayOf(100, 100, 100)
        assertEquals(240, KeyboardForm.scaleHeights(y, h, 300, 0.8f))
        assertEquals(listOf(0, 80, 160), y.toList())
        assertEquals(listOf(80, 80, 80), h.toList())
        assertEquals(300, KeyboardForm.scaleHeights(intArrayOf(0), intArrayOf(100), 300, 1f))
        assertEquals(300, KeyboardForm.scaleHeights(intArrayOf(0), intArrayOf(100), 300, Float.NaN))
    }

    @Test fun oneHandedAtAnyWidthKeepsItsSide() {
        assertEquals(KeyboardForm.Form("right", 0.85f, 0f, true), KeyboardForm.oneHanded(0.85f, atEnd = true))
        assertEquals(KeyboardForm.LEFT, KeyboardForm.oneHanded(0.7f, atEnd = false))
    }
}
