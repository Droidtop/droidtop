package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pad's focus ring over the input method's keys (Droidtop/tracker#338): every key reachable, rows wrap, edges stop. */
class KeyFocusTest {
    // Three rows: four keys of 25 across, three of 33 (offset), one wide space bar.
    private val keys = listOf(
        KeyFocus.Box(0, 0, 25, 10), KeyFocus.Box(25, 0, 25, 10), KeyFocus.Box(50, 0, 25, 10), KeyFocus.Box(75, 0, 25, 10),
        KeyFocus.Box(0, 10, 33, 10), KeyFocus.Box(33, 10, 33, 10), KeyFocus.Box(66, 10, 34, 10),
        KeyFocus.Box(0, 20, 100, 10),
    )

    @Test
    fun leftAndRightWrapAlongTheRow() {
        assertEquals(1, KeyFocus.next(keys, 0, Slab.Move.RIGHT))
        assertEquals(0, KeyFocus.next(keys, 3, Slab.Move.RIGHT))
        assertEquals(3, KeyFocus.next(keys, 0, Slab.Move.LEFT))
        assertEquals(7, KeyFocus.next(keys, 7, Slab.Move.RIGHT))
    }

    @Test
    fun upAndDownGoToTheNearestKeyAcross() {
        assertEquals(5, KeyFocus.next(keys, 1, Slab.Move.DOWN)) // centre 37.5 -> 49.5 over 16.5
        assertEquals(6, KeyFocus.next(keys, 3, Slab.Move.DOWN))
        assertEquals(7, KeyFocus.next(keys, 5, Slab.Move.DOWN))
        assertEquals(1, KeyFocus.next(keys, 5, Slab.Move.UP)) // centre 49.5: 37.5 is nearer than 62.5
    }

    @Test
    fun theTopAndBottomRowsStop() {
        assertEquals(2, KeyFocus.next(keys, 2, Slab.Move.UP))
        assertEquals(7, KeyFocus.next(keys, 7, Slab.Move.DOWN))
    }

    @Test
    fun everyKeyIsReachableFromTheStart() {
        val seen = mutableSetOf(KeyFocus.start(keys))
        var frontier = seen.toList()
        while (frontier.isNotEmpty()) {
            frontier = frontier.flatMap { from -> Slab.Move.entries.map { KeyFocus.next(keys, from, it) } }.filter { seen.add(it) }
        }
        assertEquals(keys.indices.toSet(), seen)
    }

    @Test
    fun theRingStartsInTheMiddleAndSurvivesALayerSwitch() {
        assertEquals(5, KeyFocus.start(keys))
        // A layer with different keys: the point lands on whatever is nearest it.
        val other = listOf(KeyFocus.Box(0, 0, 50, 15), KeyFocus.Box(50, 0, 50, 15), KeyFocus.Box(0, 15, 100, 15))
        assertEquals(1, KeyFocus.nearest(other, 62.5f, 5f))
    }

    @Test
    fun noKeysNoRing() {
        assertEquals(-1, KeyFocus.start(emptyList()))
    }
}
