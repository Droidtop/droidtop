package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Droidtop/tracker#599, Bare Witness on the console: a mirror picked from a saved record had no address, its token was
 * stale after the thread changed, and the source's job said "the thread no longer lists that link". The same mirror is
 * found in the record asked again, and opened from there.
 */
class RecordMirrorsTest {
    private fun d(section: String, host: String, link: String, url: String? = null) = UpdateSources.Download(section, host, host, link, url)

    @Test
    fun `the picked mirror is found where the new version moved it, with its address`() {
        val saved = listOf(d("Win", "MEGA", "0:MEGA"), d("Win", "PIXELDRAIN", "1:PIXELDRAIN"))
        val fresh = listOf(
            d("Win", "GOFILE", "0:GOFILE", "https://gofile.io/d/a"),
            d("Win", "MEGA", "1:MEGA"),
            d("Win", "PIXELDRAIN", "2:PIXELDRAIN", "https://pixeldrain.com/u/b"),
        )
        assertEquals("https://pixeldrain.com/u/b", RecordMirrors.sameIn(saved[1], saved, fresh)?.url)
        assertEquals("1:MEGA", RecordMirrors.sameIn(saved[0], saved, fresh)?.link)
    }

    @Test
    fun `the same host in another section is another mirror`() {
        val saved = listOf(d("Linux", "PIXELDRAIN", "0:PIXELDRAIN"), d("Win", "PIXELDRAIN", "1:PIXELDRAIN"))
        val fresh = listOf(d("Win", "PIXELDRAIN", "0:PIXELDRAIN", "https://pixeldrain.com/u/w"), d("Linux", "PIXELDRAIN", "1:PIXELDRAIN", "https://pixeldrain.com/u/l"))
        assertEquals("https://pixeldrain.com/u/w", RecordMirrors.sameIn(saved[1], saved, fresh)?.url)
        assertEquals("https://pixeldrain.com/u/l", RecordMirrors.sameIn(saved[0], saved, fresh)?.url)
    }

    @Test
    fun `alike mirrors keep their place among themselves, or their token when it still names one`() {
        val saved = listOf(d("Win", "MEGA", "0:MEGA"), d("Win", "MEGA", "1:MEGA"))
        val fresh = listOf(d("Win", "MEGA", "3:MEGA", "https://mega.nz/a"), d("Win", "MEGA", "4:MEGA", "https://mega.nz/b"))
        assertEquals("https://mega.nz/b", RecordMirrors.sameIn(saved[1], saved, fresh)?.url)
        val stillThere = listOf(d("Win", "MEGA", "1:MEGA", "https://mega.nz/b"), d("Win", "MEGA", "0:MEGA", "https://mega.nz/a"))
        assertEquals("https://mega.nz/b", RecordMirrors.sameIn(saved[1], saved, stillThere)?.url)
    }

    @Test
    fun `a mirror the fresh record no longer lists is not guessed`() {
        val saved = listOf(d("Win", "PIXELDRAIN", "0:PIXELDRAIN"))
        assertNull(RecordMirrors.sameIn(saved[0], saved, listOf(d("Win", "MEGA", "0:MEGA", "https://mega.nz/a"))))
    }
}
