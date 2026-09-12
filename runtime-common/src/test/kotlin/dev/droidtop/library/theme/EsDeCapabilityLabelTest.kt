package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Settings showed a Traditional Chinese label for a theme variant on an
 * English device, because a capabilities.xml declares one `<label>` per
 * supported language and the parser kept whichever came last.
 * slate-es-de ships 24 of them, so the odds of the last one being the
 * right one are about one in 24.
 *
 * The resolver is tested directly rather than through parseCapabilities:
 * that reads `android.util.Xml`, which a plain JVM test has no
 * implementation of, and nothing in this module's test tree parses XML for
 * that reason.
 */
class EsDeCapabilityLabelTest {

    /**
     * The resolver is exercised directly rather than through a locale
     * switch: Locale.setDefault in a unit test leaks into every other test
     * in the same JVM, and what is worth pinning is the order of the
     * fallbacks, not the platform's locale plumbing.
     */
    @Test
    fun `an exact language wins, then the language, then en_US`() {
        val labels = mapOf(
            "a" to mapOf("en_US" to "A english", "de_DE" to "A deutsch", "zh_TW" to "A chinese"),
            "b" to mapOf("de_AT" to "B austrian", "en_US" to "B english"),
            "c" to mapOf("en_US" to "C english", "zh_TW" to "C chinese"),
            "d" to mapOf("" to "D unlabelled"),
        )
        val german = withLocale(Locale("de", "DE")) { EsDeThemeParser.resolveLabels(labels) }
        assertEquals("A deutsch", german["a"])
        // No de_DE for b, but de_AT is the same language.
        assertEquals("B austrian", german["b"])
        // No German at all for c: en_US, not "whatever came last".
        assertEquals("C english", german["c"])
        // An older theme with no language attribute at all.
        assertEquals("D unlabelled", german["d"])

        val english = withLocale(Locale.US) { EsDeThemeParser.resolveLabels(labels) }
        assertEquals("A english", english["a"])
        assertEquals("B english", english["b"])
        assertEquals("C english", english["c"])
    }

    private fun <T> withLocale(locale: Locale, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            Locale.setDefault(previous)
        }
    }
}
