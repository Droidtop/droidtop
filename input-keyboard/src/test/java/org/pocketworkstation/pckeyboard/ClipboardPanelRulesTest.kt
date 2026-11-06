package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clipboard panel's rules (docs/SPEC.md 6a, "Clipboard history", Droidtop/tracker#903 H12d): one intake rule for
 * every source, "Keep clips for" with pins, search, ages, the panel's order and sections, and the upgrade. The paired
 * device's cases were PairedClipsTest ("Type from another device", "On the keyboard").
 */
class ClipboardPanelRulesTest {
    private val history = ClipboardHistory()

    private fun offer(
        text: String,
        now: Long = 1_000L,
        historyOn: Boolean = true,
        incognito: Boolean = false,
        fieldSensitive: Boolean = false,
        markedSensitive: Boolean = false,
    ) = ClipIntake.keep(history, text, now, historyOn, incognito, fieldSensitive, markedSensitive)

    // --- sensitive clips, per source ---

    @Test
    fun `the system clipboard listener drops a clip marked sensitive, a password field's copy and anything in incognito`() {
        // Android 13 and later: the source set ClipDescription.EXTRA_IS_SENSITIVE (ClipboardHistoryStore.markedSensitive).
        assertEquals(ClipIntake.Outcome.DROPPED, offer("hunter2", markedSensitive = true))
        // Every version: the keyboard serves a password or no-learning field (IncognitoRules.sensitiveField).
        assertEquals(ClipIntake.Outcome.DROPPED, offer("hunter2", fieldSensitive = true))
        assertEquals(ClipIntake.Outcome.DROPPED, offer("anything", incognito = true))
        assertEquals(0, history.size)
        assertEquals(ClipIntake.Outcome.KEPT, offer("a plain copy"))
    }

    @Test
    fun `the keyboard's own copy is dropped when it is typed text or made beside a password field`() {
        // Text typed into a field through the clipboard is put there marked sensitive (putOnClipboard, typed = true).
        assertEquals(ClipIntake.Outcome.DROPPED, offer("typed, not copied", markedSensitive = true))
        // A clip sent to a container while a password field has the keyboard.
        assertEquals(ClipIntake.Outcome.DROPPED, offer("for the terminal", fieldSensitive = true))
        assertEquals(0, history.size)
        // A clip or emoji handed to a container otherwise is an ordinary clip.
        assertEquals(ClipIntake.Outcome.KEPT, offer("ls -la"))
    }

    @Test
    fun `a paired device's clip marked sensitive, beside a password field, in incognito or with the history off is dropped`() {
        assertEquals(ClipIntake.Outcome.DROPPED, offer("secret", markedSensitive = true))
        assertEquals(ClipIntake.Outcome.DROPPED, offer("c", fieldSensitive = true))
        assertEquals(ClipIntake.Outcome.DROPPED, offer("a", incognito = true))
        assertEquals(ClipIntake.Outcome.DROPPED, offer("b", historyOn = false))
        assertEquals(0, history.size)
    }

    @Test
    fun `a clip from the phone is on the panel, newest first`() {
        assertEquals(ClipIntake.Outcome.KEPT, offer("first"))
        assertEquals(ClipIntake.Outcome.KEPT, offer("second", now = 2_000L))
        assertEquals(listOf("second", "first"), history.entries.map { it.text })
    }

    @Test
    fun `a blank clip or one over the history's limit is not stored`() {
        assertEquals(ClipIntake.Outcome.DROPPED, offer("   "))
        assertEquals(ClipIntake.Outcome.DROPPED, offer("x".repeat(5001)))
        assertEquals(0, history.size)
    }

    @Test
    fun `the same clip again moves to the top and keeps its pin`() {
        offer("note")
        history.setPinned("note", true)
        offer("other")
        assertEquals(ClipIntake.Outcome.KEPT, offer("note"))
        assertEquals(2, history.size)
        assertTrue(history.entries.first { it.text == "note" }.pinned)
    }

    @Test
    fun `a text seen marked sensitive is forgotten unless the person pinned it`() {
        offer("hunter2")
        offer("my note")
        history.setPinned("my note", true)
        assertEquals(ClipIntake.Outcome.FORGOT, offer("hunter2", markedSensitive = true))
        assertEquals(ClipIntake.Outcome.DROPPED, offer("my note", fieldSensitive = true))
        assertEquals(listOf("my note"), history.entries.map { it.text })
    }

    // --- Keep clips for ---

    @Test
    fun `expiry removes old unpinned clips and keeps pinned ones`() {
        val day = ClipKeep.DAY.millis!!
        history.add("old pinned", 0)
        history.setPinned("old pinned", true)
        history.add("old", 1)
        history.add("fresh", day + 10)
        assertTrue(history.expire(day + 100, day))
        assertEquals(listOf("old pinned", "fresh"), history.entries.map { it.text })
        assertFalse(history.expire(day + 100, day))
    }

    @Test
    fun `until cleared keeps everything`() {
        history.add("ancient", 0)
        assertFalse(history.expire(Long.MAX_VALUE / 2, ClipKeep.FOREVER.millis))
        assertEquals(1, history.size)
    }

    @Test
    fun `clear unpinned keeps pinned clips`() {
        history.add("a", 1)
        history.add("b", 2)
        history.setPinned("a", true)
        assertEquals(1, history.unpinnedCount)
        history.clear(keepPinned = true)
        assertEquals(listOf("a"), history.entries.map { it.text })
    }

    @Test
    fun `an unpinned clip gets the whole limit again`() {
        val hour = ClipKeep.HOUR.millis!!
        history.add("kept a while", 0)
        history.setPinned("kept a while", true, now = 10 * hour)
        history.setPinned("kept a while", false, now = 10 * hour)
        assertFalse(history.expire(10 * hour + 1, hour))
        assertEquals(10 * hour, history.entries.single().at)
    }

    @Test
    fun `the setting reads its stored values and falls back to until cleared`() {
        assertEquals(ClipKeep.HOUR, ClipKeep.fromPref("hour"))
        assertEquals(ClipKeep.DAY, ClipKeep.fromPref("day"))
        assertEquals(ClipKeep.WEEK, ClipKeep.fromPref("week"))
        assertEquals(ClipKeep.FOREVER, ClipKeep.fromPref("forever"))
        assertEquals(ClipKeep.FOREVER, ClipKeep.fromPref(null))
        assertEquals(ClipKeep.FOREVER, ClipKeep.fromPref("fortnight"))
    }

    // --- search ---

    @Test
    fun `search matches every word in any order, ignoring case and accents`() {
        assertTrue(ClipSearch.matches("Meet at the Café at noon", "cafe NOON"))
        assertTrue(ClipSearch.matches("Meet at the Café at noon", "noon meet"))
        assertFalse(ClipSearch.matches("Meet at the Café at noon", "cafe midnight"))
        assertTrue(ClipSearch.matches("anything", "   "))
        assertTrue(ClipSearch.matches("line one\nline two", "one two"))
    }

    @Test
    fun `search keeps the panel's order`() {
        history.add("apple pie", 1)
        history.add("apple tart", 2)
        history.add("pear", 3)
        history.setPinned("apple pie", true)
        assertEquals(listOf("apple pie", "apple tart"), ClipSearch.filter(history.entries, "apple").map { it.text })
    }

    // --- ages ---

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `ages read now, minutes and hours today`() {
        val now = 10 * day + 15 * hour
        assertEquals(ClipAge.Age(ClipAge.Unit.NOW, 0), ClipAge.of(now - 30_000, now, 0))
        assertEquals(ClipAge.Age(ClipAge.Unit.MINUTES, 2), ClipAge.of(now - 2 * minute - 5_000, now, 0))
        assertEquals(ClipAge.Age(ClipAge.Unit.HOURS, 3), ClipAge.of(now - 3 * hour, now, 0))
        // The clock moved back: a time ahead reads as now.
        assertEquals(ClipAge.Age(ClipAge.Unit.NOW, 0), ClipAge.of(now + hour, now, 0))
    }

    @Test
    fun `yesterday, days and weeks follow the local calendar`() {
        // 01:00 local, two hours ahead of UTC: a clip from 23:00 local the evening before is yesterday.
        val offset = 2 * hour
        val now = 10 * day + 1 * hour - offset
        assertEquals(ClipAge.Unit.YESTERDAY, ClipAge.of(now - 2 * hour, now, offset).unit)
        assertEquals(ClipAge.Age(ClipAge.Unit.DAYS, 3), ClipAge.of(now - 3 * day, now, offset))
        assertEquals(ClipAge.Age(ClipAge.Unit.WEEKS, 2), ClipAge.of(now - 15 * day, now, offset))
        // Read in UTC the same two moments are one day apart too, but it is 23:00 and 21:00: hours.
        assertEquals(ClipAge.Unit.HOURS, ClipAge.of(now - 2 * hour, now, 0).unit)
    }

    // --- order and sections ---

    @Test
    fun `pinned clips sit at the top, then the last five minutes, then earlier`() {
        val now = 100 * minute
        history.add("old", now - 60 * minute)
        history.add("pinned", now - 90 * minute)
        history.add("new", now - minute)
        history.setPinned("pinned", true)
        assertEquals(listOf("pinned", "new", "old"), history.entries.map { it.text })
        val groups = ClipGroups.of(history.entries, now)
        assertEquals(listOf(ClipGroups.Group.PINNED, ClipGroups.Group.RECENT, ClipGroups.Group.OLDER), groups.map { it.first })
        assertEquals(listOf("new"), groups[1].second.map { it.text })
        assertEquals(listOf(ClipGroups.Group.OLDER), ClipGroups.of(listOf(ClipboardHistory.Entry("x", false, 0)), now).map { it.first })
    }

    // --- the upgrade ---

    @Test
    fun `clips saved by the build before survive the upgrade with their pins`() {
        // What the previous build wrote into clipboard_history.bin (sealed) or clipboard_history.txt: P or -, time, text.
        val saved = "P\t5\tmy address\n-\t9\tline one\\nline two\n-\t7\tplain\n"
        history.restore(saved)
        // No "Keep clips for" stored yet: until cleared, so nothing goes.
        val keep = ClipKeep.fromPref(null)
        assertFalse(history.expire(Long.MAX_VALUE / 2, keep.millis))
        assertEquals(listOf("my address", "line one\nline two", "plain"), history.entries.map { it.text })
        assertTrue(history.entries.first().pinned)
        // And they are written back in the same format.
        val back = ClipboardHistory()
        back.restore(history.encode())
        assertEquals(history.entries, back.entries)
    }
}
