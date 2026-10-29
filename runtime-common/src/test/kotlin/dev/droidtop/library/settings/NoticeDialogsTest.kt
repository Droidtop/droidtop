package dev.droidtop.library.settings

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The danger dialog's contract and the flash's Recent list (docs/SPEC.md 7k, "Asking before a change, and the
 * flash"; Droidtop/tracker#485, final design tests 15 and 18, the parts that hold without a screen): with no
 * surface to ask on the answer is Cancel; the surface on top draws; questions come one at a time; a flash lands
 * in Recent unless private, Recent keeps 50, and reading it clears the dot.
 */
class NoticeDialogsTest {
    private val danger = DangerConfirm(Notice(NoticeTone.Danger, "Allow full access?", recommendation = "We recommend Cancel."), "Install anyway")
    private val hosts = mutableListOf<Any>()

    private fun attach(): Any = Any().also { NoticeDialogs.attachHost(it); hosts += it }

    @After
    fun detach() {
        hosts.forEach { NoticeDialogs.detachHost(it) }
        Notices.markRead()
    }

    @Test
    fun `with no surface attached the answer is Cancel`() = runBlocking {
        assertEquals(ConfirmAnswer.Cancel, NoticeDialogs.confirmDanger(danger))
    }

    @Test
    fun `waiting for a host ends when one attaches, and gives up without one`() = runBlocking {
        assertFalse(NoticeDialogs.awaitHost(50))
        val waiting = async { NoticeDialogs.awaitHost(5_000) }
        yield()
        attach()
        assertTrue(waiting.await())
        assertTrue(NoticeDialogs.awaitHost(100))
    }

    @Test
    fun `the last host attached draws, and its answer comes back`() = runBlocking {
        val first = attach()
        val second = attach()
        assertTrue(NoticeDialogs.drawsHere(second))
        assertFalse(NoticeDialogs.drawsHere(first))
        val answer = async { NoticeDialogs.confirmDanger(danger) }
        val request = withTimeout(1_000) {
            while (NoticeDialogs.pending.value == null) yield()
            NoticeDialogs.pending.value!!
        }
        assertTrue(request.danger)
        assertEquals("Cancel", request.cancelLabel)
        assertEquals("Install anyway", request.proceedLabel)
        NoticeDialogs.answer(request, ConfirmAnswer.Proceed)
        assertEquals(ConfirmAnswer.Proceed, answer.await())
        assertNull(NoticeDialogs.pending.value)
    }

    @Test
    fun `a second question waits for the first`() = runBlocking {
        attach()
        val first = async { NoticeDialogs.confirmDanger(danger) }
        val shown = withTimeout(1_000) {
            while (NoticeDialogs.pending.value == null) yield()
            NoticeDialogs.pending.value!!
        }
        val second = async { NoticeDialogs.confirm(Notice(NoticeTone.Caution, "Turn on?"), proceedLabel = "Turn on") }
        yield()
        assertTrue(NoticeDialogs.pending.value === shown)
        NoticeDialogs.answer(shown, ConfirmAnswer.Cancel)
        assertEquals(ConfirmAnswer.Cancel, first.await())
        val next = withTimeout(1_000) {
            while (NoticeDialogs.pending.value == null || NoticeDialogs.pending.value === shown) yield()
            NoticeDialogs.pending.value!!
        }
        assertFalse(next.danger)
        NoticeDialogs.answer(next, ConfirmAnswer.Proceed)
        assertEquals(ConfirmAnswer.Proceed, second.await())
    }

    @Test
    fun `a flash lands in Recent unless private, and Recent keeps the last 50`() {
        Notices.markRead()
        Notices.flash("Saved")
        assertEquals("Saved", Notices.recent.value.first().text)
        assertEquals(1, Notices.unread.value)
        Notices.flash("token abc123", private = true)
        assertTrue(Notices.recent.value.none { it.text.contains("abc123") })
        assertTrue(Notices.flashes.value.any { it.text == "token abc123" })
        repeat(60) { Notices.flash("Copied $it") }
        assertEquals(Notices.RECENT_MAX, Notices.recent.value.size)
        assertEquals("Copied 59", Notices.recent.value.first().text)
        Notices.markRead()
        assertEquals(0, Notices.unread.value)
        Notices.flashes.value.forEach(Notices::shown)
        assertTrue(Notices.flashes.value.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a flash is never danger`() {
        Notices.flash("Allow full access?", NoticeTone.Danger)
    }
}
