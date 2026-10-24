package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Flashes of one kind gather into one card that counts up (docs/SPEC.md "Motion and visual status", Droidtop/tracker#643). */
class NoticeGroupsTest {

    private val line: (Int) -> String = { n -> "$n downloads finished" }

    private fun flash(text: String, id: Long, group: String? = null) =
        Notices.Flash(text, NoticeTone.Info, null, id, group = group, groupLine = line)

    @Test
    fun `a second finish while the first is waiting counts it up in its place`() {
        val first = flash("Hades is ready", 1, group = "done")
        val other = flash("Saved", 2)
        val waiting = Notices.merged(Notices.merged(listOf(first), other), flash("Celeste is ready", 3, group = "done"))
        assertEquals(listOf(1L, 2L), waiting.map { it.id })
        assertEquals("2 downloads finished", waiting.first().text)
        assertEquals(2, waiting.first().count)
        val third = Notices.merged(waiting, flash("Tunic is ready", 4, group = "done"))
        assertEquals("3 downloads finished", third.first().text)
    }

    @Test
    fun `flashes with no group, or of another group, queue as before`() {
        val waiting = Notices.merged(listOf(flash("Saved", 1)), flash("Saved", 2))
        assertEquals(2, waiting.size)
        val groups = Notices.merged(listOf(flash("Hades is ready", 1, group = "done")), flash("Hades did not download", 2, group = "failed"))
        assertEquals(listOf("Hades is ready", "Hades did not download"), groups.map { it.text })
    }
}
