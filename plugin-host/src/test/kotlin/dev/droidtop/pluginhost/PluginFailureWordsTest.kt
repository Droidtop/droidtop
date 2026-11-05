package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** droidtop's words for a plugin's failure, by its code, naming the feature (Droidtop/tracker#883 §3.7). */
class PluginFailureWordsTest {
    private fun words(code: PluginErrorCode?) = PluginFailureWords.of(code, "F95zone")

    @Test
    fun `each code has droidtop's title and its one fix`() {
        assertEquals(PluginFailureWords.Words("F95zone wants you to sign in", PluginFailureWords.Fix.SIGN_IN), words(PluginErrorCode.SIGN_IN_REQUIRED))
        assertEquals(PluginFailureWords.Words("F95zone checks your browser first", PluginFailureWords.Fix.OPEN_SITE), words(PluginErrorCode.BROWSER_CHECK_REQUIRED))
        assertEquals("F95zone can't be reached", words(PluginErrorCode.OFFLINE).title)
        assertEquals("F95zone can't be reached", words(PluginErrorCode.SITE_DOWN).title)
        assertEquals(PluginFailureWords.Fix.WAIT, words(PluginErrorCode.RATE_LIMITED).fix)
        assertEquals(PluginFailureWords.Words("F95zone can't do this yet", PluginFailureWords.Fix.ALLOW), words(PluginErrorCode.PERMISSION_DENIED))
        assertEquals("F95zone didn't answer in time", words(PluginErrorCode.TIMEOUT).title)
        assertEquals(PluginFailureWords.Words("F95zone needs its update", PluginFailureWords.Fix.UPDATE), words(PluginErrorCode.NEEDS_UPDATE))
    }

    @Test
    fun `anything else, or nothing, is that it stopped, never who failed in other words`() {
        for (code in listOf(null, PluginErrorCode.FAILED, PluginErrorCode.PROVIDER_CRASHED, PluginErrorCode.INVALID_ARGS)) {
            assertEquals(PluginFailureWords.Words("F95zone stopped", PluginFailureWords.Fix.TRY_AGAIN), words(code))
        }
        val titles = PluginErrorCode.entries.map { words(it).title } + words(null).title
        assertTrue(titles.none { "plugin" in it.lowercase() || "couldn't finish" in it.lowercase() || "could not do that" in it.lowercase() })
    }
}
