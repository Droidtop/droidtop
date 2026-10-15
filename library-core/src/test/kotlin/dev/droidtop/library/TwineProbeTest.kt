package dev.droidtop.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [looksLikeTwine]: the story element anywhere, or the format banner inside the first 8 KB. */
class TwineProbeTest {
    @Test
    fun `the story data element marks a Twine page whatever its case or position`() {
        assertTrue(looksLikeTwine("<html><TW-STORYDATA name=\"x\"></TW-STORYDATA>"))
        assertTrue(looksLikeTwine("a".repeat(20_000) + "<tw-storydata>"))
    }

    @Test
    fun `the story format banner counts only inside the first 8 KB`() {
        assertTrue(looksLikeTwine("/* Twine story format SugarCube 2 */"))
        assertTrue(looksLikeTwine("a".repeat(8 * 1024 - 12) + "story format"))
        assertFalse(looksLikeTwine("a".repeat(8 * 1024) + "story format"))
    }

    @Test
    fun `an ordinary page is not Twine`() {
        assertFalse(looksLikeTwine("<html><body>hello</body></html>"))
        assertFalse(looksLikeTwine(""))
    }
}
