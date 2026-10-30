package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Standard's widget stacks as data, and what the launcher's ghost-widget cleanup keeps (docs/SPEC.md "Standard"). */
class WidgetStackTableTest {
    private val clock = StackMember(11, "com.example/.Clock")
    private val weather = StackMember(12, "com.example/.Weather")
    private val notes = StackMember(13, "com.example/.Notes")

    @Test
    fun `stacking onto a widget starts a stack, and stacking again adds to it`() {
        val one = WidgetStackTable.add(emptyList(), clock, weather)
        assertEquals(listOf(WidgetStack(listOf(clock, weather))), one)
        val two = WidgetStackTable.add(one, weather, notes)
        assertEquals(listOf(WidgetStack(listOf(clock, weather, notes))), two)
    }

    @Test
    fun `a swipe goes round the stack both ways`() {
        val stack = WidgetStack(listOf(clock, weather, notes))
        assertEquals(weather, WidgetStackTable.next(stack, clock.id, +1))
        assertEquals(clock, WidgetStackTable.next(stack, notes.id, +1))
        assertEquals(notes, WidgetStackTable.next(stack, clock.id, -1))
        assertNull(WidgetStackTable.next(stack, 99, +1))
        assertNull(WidgetStackTable.next(WidgetStack(listOf(clock)), clock.id, +1))
    }

    @Test
    fun `the cleanup keeps a live stack's hidden widgets and not the shown one`() {
        val stacks = listOf(WidgetStack(listOf(clock, weather, notes)))
        // The model's item shows weather.
        assertEquals(setOf(clock.id, notes.id), WidgetStackTable.spared(stacks, setOf(weather.id, 40)))
    }

    @Test
    fun `the cleanup frees a removed stack's widgets`() {
        val stacks = listOf(WidgetStack(listOf(clock, weather)))
        assertTrue(WidgetStackTable.spared(stacks, setOf(40)).isEmpty())
        assertTrue(WidgetStackTable.live(stacks, setOf(40)).isEmpty())
    }

    @Test
    fun `taking a widget out leaves no stack of one`() {
        val stacks = listOf(WidgetStack(listOf(clock, weather, notes)))
        assertEquals(listOf(WidgetStack(listOf(clock, notes))), WidgetStackTable.remove(stacks, weather.id))
        assertTrue(WidgetStackTable.remove(listOf(WidgetStack(listOf(clock, weather))), clock.id).isEmpty())
    }

    @Test
    fun `the table survives a round trip, and a damaged one is refused`() {
        val stacks = listOf(WidgetStack(listOf(clock, weather)), WidgetStack(listOf(notes, StackMember(14, "a/.B"))))
        assertEquals(stacks, WidgetStackTable.decode(WidgetStackTable.encode(stacks)))
        assertTrue(runCatching { WidgetStackTable.decode("not json") }.isFailure)
    }
}
