package org.pocketworkstation.pckeyboard.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard's own strip for a phone typing into this device (docs/SPEC.md, "Type from another device", "On the
 * keyboard", Droidtop/tracker#903): shown only while a phone is paired, a password field's text never kept, "Not
 * received" in place until the next thing lands, and the router telling it of each delivery.
 */
class RemoteStripTest {
    private val model = RemoteStripModel()
    private val seen = mutableListOf<RemoteStrip>()

    init {
        model.addListener { seen += it }
    }

    @Test
    fun `hidden until a phone is paired, and hidden again when the session ends`() {
        assertFalse(model.state.live)
        model.delivered("ignored", Delivery.Sent(false))
        assertEquals(RemoteStrip.HIDDEN, model.state)
        assertTrue(seen.isEmpty())
        model.session(true)
        assertEquals(RemoteStrip(live = true), model.state)
        model.delivered("hello", Delivery.Sent(false))
        model.session(false)
        assertEquals(RemoteStrip.HIDDEN, model.state)
        model.session(true)
        assertNull("a new pairing starts with nothing sent", model.state.sent)
    }

    @Test
    fun `what the phone typed is shown, and a password field's text is never kept`() {
        model.session(true)
        model.delivered("hello", Delivery.Sent(false))
        assertEquals("hello", model.state.sent)
        model.delivered("hunter2", Delivery.Sent(true))
        assertNull(model.state.sent)
        assertFalse(seen.any { it.sent == "hunter2" })
        assertTrue(model.state.live)
    }

    @Test
    fun `Not received stays in place until the next thing lands`() {
        model.session(true)
        model.delivered("a", Delivery.Sent(false))
        model.delivered("b", Delivery.Refused(RemoteInputRouter.NO_FIELD))
        assertEquals(RemoteStrip(live = true, sent = "a", notReceived = RemoteInputRouter.NO_FIELD), model.state)
        model.delivered("c", Delivery.Sent(false))
        assertEquals(RemoteStrip(live = true, sent = "c"), model.state)
    }

    @Test
    fun `a key press keeps the last text, but not beside a password field`() {
        model.session(true)
        model.delivered("abc", Delivery.Sent(false))
        model.delivered(null, Delivery.Sent(false))
        assertEquals("abc", model.state.sent)
        model.delivered(null, Delivery.Sent(true))
        assertNull(model.state.sent)
    }

    @Test
    fun `the router tells the strip of each delivery, Enter and Backspace in a word`() {
        val field = object : RemoteTarget {
            override val secret = false
            override fun commit(text: String) = Unit
            override fun backspace(count: Int) = Unit
            override fun enter() = Unit
            override fun key(keyCode: Int) = Unit
        }
        var focused: RemoteTarget? = field
        val router = RemoteInputRouter(ownField = { focused }, ime = { null }, imeSelected = { true }, report = model::delivered)
        model.session(true)
        router.deliver(0, "hi", false)
        assertEquals("hi", model.state.sent)
        router.deliver(0, "", true)
        assertEquals("Enter", model.state.sent)
        router.deliver(1, "", false)
        assertEquals("Backspace", model.state.sent)
        focused = null
        router.deliver(0, "lost", false)
        assertEquals(RemoteStrip(live = true, sent = "Backspace", notReceived = RemoteInputRouter.NO_FIELD), model.state)
        focused = field
        router.press(61)
        assertEquals(RemoteStrip(live = true, sent = "Backspace"), model.state)
    }

    @Test
    fun `listeners hear each change once`() {
        model.session(true)
        model.session(true)
        model.delivered("x", Delivery.Sent(false))
        model.delivered("x", Delivery.Sent(false))
        assertEquals(2, seen.size)
    }
}
