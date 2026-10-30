package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one store for a value kept as a string in preferences (Droidtop/tracker#771). */
class CachedPrefTest {
    private class Prefs(vararg stored: Pair<String, String>) : CachedPref.Backing {
        val values = mutableMapOf(*stored)
        var reads = 0
        var writes = 0
        val unreadable = mutableListOf<Pair<String, String>>()

        override fun read(key: String): String? = values[key].also { reads++ }
        override fun write(key: String, value: String) {
            values[key] = value
            writes++
        }
        override fun unreadable(key: String, raw: String, error: Throwable) {
            unreadable += key to raw
        }
    }

    private fun numbers() = CachedPref("numbers", emptyList<Int>(), { text -> text.split(",").map { it.trim().toInt() } }, { it.joinToString(",") })

    @Test
    fun `the stored text is read once and then held`() {
        val prefs = Prefs("numbers" to "1,2")
        val store = numbers()
        assertEquals(listOf(1, 2), store.get(prefs))
        assertEquals(listOf(1, 2), store.get(prefs))
        assertEquals(1, prefs.reads)
    }

    @Test
    fun `nothing stored reads as empty and is not reported`() {
        val prefs = Prefs()
        assertEquals(emptyList<Int>(), numbers().get(prefs))
        assertTrue(prefs.unreadable.isEmpty())
    }

    @Test
    fun `a value that does not parse is handed over as it was before anything replaces it`() {
        val prefs = Prefs("numbers" to "1,two")
        val store = numbers()
        assertEquals(emptyList<Int>(), store.get(prefs))
        assertEquals(listOf("numbers" to "1,two"), prefs.unreadable)
        store.update(prefs) { it + 3 }
        assertEquals("3", prefs.values["numbers"])
        assertEquals(1, prefs.unreadable.size)
    }

    @Test
    fun `an update is saved, and one that changes nothing is not written`() {
        val prefs = Prefs("numbers" to "1")
        val store = numbers()
        assertEquals(listOf(1, 2), store.update(prefs) { it + 2 })
        assertEquals("1,2", prefs.values["numbers"])
        store.update(prefs) { it }
        assertEquals(1, prefs.writes)
    }

    @Test
    fun `updates from many threads all land`() {
        val prefs = Prefs()
        val store = numbers()
        val threads = (1..8).map { n -> Thread { repeat(50) { store.update(prefs) { it + n } } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(400, store.get(prefs).size)
        assertEquals(400, prefs.values.getValue("numbers").split(",").size)
    }
}
