package dev.droidtop.pluginhost

import dev.droidtop.library.settings.DangerConfirm
import dev.droidtop.library.settings.NoticeTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The red dialogs before a plugin runs without droidtop's protection (Droidtop/tracker#481): each is a danger notice
 * that recommends Cancel, still lets the person go on, and names no tier (docs/plugin-api.md 4, "Users must not have
 * to know").
 */
class PluginDangersTest {
    // What the dialog says; Details holds the exact terms (permission ids among them) for whoever opens it.
    private fun words(c: DangerConfirm) =
        listOfNotNull(c.notice.title, c.notice.body, c.notice.recommendation, c.proceedLabel).joinToString("\n")

    private fun assertRedAndPlain(c: DangerConfirm) {
        assertEquals(NoticeTone.Danger, c.notice.tone)
        assertTrue(c.notice.recommendation.orEmpty().startsWith("We recommend Cancel"))
        assertEquals("Cancel", c.cancelLabel)
        val text = words(c).lowercase()
        for (word in listOf("tier", "contained", "full_trust", "host.")) assertFalse("\"$word\" in: $text", text.contains(word))
        val details = c.notice.details.orEmpty().lowercase()
        for (word in listOf("tier", "contained")) assertFalse("\"$word\" in: $details", details.contains(word))
    }

    @Test fun `full access asks in red, recommends Cancel, and can still be installed or updated`() {
        val install = PluginDangers.fullAccess("Sample", "https://example.org/catalog", listOf("Full access"), update = false)
        assertRedAndPlain(install)
        assertEquals("Allow full access?", install.notice.title)
        assertEquals("Install anyway", install.proceedLabel)
        assertTrue(install.notice.details.orEmpty().contains("https://example.org/catalog"))
        assertEquals("Update anyway", PluginDangers.fullAccess("Sample", "o", emptyList(), update = true).proceedLabel)
    }

    @Test fun `a plugin whose protection did not start is asked about, with the layer's reason, for this run only`() {
        val one = PluginDangers.protectionFailed("Sample", "no TSYNC")
        assertRedAndPlain(one)
        assertEquals("Protection didn't start", one.notice.title)
        assertEquals("Run anyway", one.proceedLabel)
        assertTrue(one.notice.details.orEmpty().contains("no TSYNC"))
        assertTrue(one.notice.details.orEmpty().contains("this run of droidtop only"))

        val all = PluginDangers.protectionFailedAll(listOf(PluginDangers.Failure("a", "A", "old kernel"), PluginDangers.Failure("b", "B", "no TSYNC")))
        assertRedAndPlain(all)
        assertEquals("2 plugins would run with full access on this device.", all.notice.body)
        assertEquals(listOf("A", "B"), all.items)
        assertEquals("Run all anyway", all.proceedLabel)
    }

    @Test fun `only a load error that says protection failed is read as one`() {
        assertEquals("no TSYNC", PluginDangers.protectionReport(PluginDangers.PROTECTION_FAILED + "no TSYNC"))
        assertNull(PluginDangers.protectionReport("timed out"))
        assertNull(PluginDangers.protectionReport(null))
    }

    @Test fun `a plugin held back is listed until it loads, and is not allowed to run unless the person chose to`() {
        PluginDangers.recordFailure(PluginDangers.Failure("dangers-test", "Sample", "no TSYNC"))
        assertTrue("dangers-test" in PluginDangers.failures.value)
        assertFalse(PluginDangers.allowedThisRun("dangers-test"))
        PluginDangers.loaded("dangers-test")
        assertFalse("dangers-test" in PluginDangers.failures.value)
    }
}
