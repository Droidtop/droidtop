package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notice model's focus and accessibility rules (docs/SPEC.md 7k, "Notices"; Droidtop/tracker#485,
 * final design test 6): a notice is a stop of the pad only when something happens there, A does the
 * action or else opens Info, and a screen reader hears the tone first and gets Info and the action as
 * custom actions.
 */
class NoticeTest {
    private val turnOn = NoticeAction("Turn on") {}

    @Test
    fun `a notice with nothing to press or read is not a stop`() {
        assertFalse(Notice(NoticeTone.Info, "Saved").takesFocus())
        assertTrue(Notice(NoticeTone.Info, "Saved").takesFocus(cut = true))
        assertTrue(Notice(NoticeTone.Caution, "Core installs are off for safety", action = turnOn).takesFocus())
        assertTrue(Notice(NoticeTone.Caution, "Unofficial list", details = "https://example.invalid/list.json").takesFocus())
        assertFalse(Notice(NoticeTone.Caution, "Unofficial list", details = "  ").takesFocus())
    }

    @Test
    fun `A does the action, else opens Info, else nothing`() {
        assertEquals(NoticePress.ACTION, Notice(NoticeTone.Caution, "t", details = "d", action = turnOn).aPress())
        assertEquals(NoticePress.INFO, Notice(NoticeTone.Caution, "t", details = "d").aPress())
        assertEquals(NoticePress.NONE, Notice(NoticeTone.Caution, "t").aPress())
    }

    @Test
    fun `a screen reader hears the tone first, then the plugin it is from`() {
        val notice = Notice(
            NoticeTone.Caution,
            "Unofficial plugin",
            body = "Approve it only if someone you trust pointed you to it.",
            source = "Example",
        )
        assertEquals(
            "Caution, Unofficial plugin, Approve it only if someone you trust pointed you to it., from Example",
            notice.spoken(),
        )
        assertTrue(Notice(NoticeTone.Danger, "Allow full access?").spoken().startsWith("Danger, "))
    }

    @Test
    fun `custom actions are Info when there is more to read, then the action`() {
        assertEquals(listOf("Info", "Turn on"), Notice(NoticeTone.Caution, "t", details = "d", action = turnOn).accessibilityActions())
        assertEquals(listOf("Turn on"), Notice(NoticeTone.Caution, "t", action = turnOn).accessibilityActions())
        assertEquals(emptyList<String>(), Notice(NoticeTone.Info, "t").accessibilityActions())
    }

    @Test
    fun `a notice row reads its title and body as a row's title and subtitle`() {
        val item = NoticeItem("n", Notice(NoticeTone.Info, "Title", body = "Body"), NoticePlacement.Group)
        assertEquals("Title", item.title)
        assertEquals("Body", item.subtitle)
        assertEquals(NoticePlacement.Group, item.placement)
    }
}
