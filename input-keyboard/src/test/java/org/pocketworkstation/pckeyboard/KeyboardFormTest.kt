package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
        val x = intArrayOf(0, 300)
        val w = intArrayOf(300, 400)
        val g = intArrayOf(0, 0)
        // After 80% scaling key 2 is at 240 and 320 wide, across the middle (280) of a 560 grid; the gap is 140.
        val total = KeyboardForm.layout(x, w, g, 700, KeyboardForm.SPLIT)
        assertEquals(700, total)
        assertEquals(listOf(0, 240), x.toList())
        assertEquals(listOf(240, 320 + 140), w.toList())
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
}
