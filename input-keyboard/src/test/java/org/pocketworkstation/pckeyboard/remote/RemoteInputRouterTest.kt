package org.pocketworkstation.pckeyboard.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where text from another device lands (docs/SPEC.md, "Type from another device", Droidtop/tracker#645): droidtop's own
 * field first, else droidtop's input method's editor, else a notice; password fields; KDE Connect's key names. Moved
 * with the router from `:library-core`'s RemoteInputTest when the keyboard and input module took it (Droidtop/tracker#903).
 */
class RemoteInputRouterTest {
    private val own = FakeTarget()
    private val ime = FakeTarget()
    private var ownFocused = false
    private var imeFocused = false
    private var imeSelected = false

    private class FakeTarget(override val secret: Boolean = false) : RemoteTarget {
        val log = mutableListOf<String>()

        override fun commit(text: String) { log += "text:$text" }

        override fun backspace(count: Int) { log += "back:$count" }

        override fun enter() { log += "enter" }

        override fun key(keyCode: Int) { log += "key:$keyCode" }
    }

    private val router = RemoteInputRouter(
        ownField = { own.takeIf { ownFocused } },
        ime = { ime.takeIf { imeFocused } },
        imeSelected = { imeSelected },
    )

    @Test
    fun `a droidtop field is typed into first, even while droidtop's input method has an editor`() {
        ownFocused = true
        imeFocused = true
        imeSelected = true
        assertTrue(router.route() is Route.OwnField)
        assertEquals(Delivery.Sent(false), router.deliver(0, "a", false))
        assertEquals(listOf("text:a"), own.log)
        assertEquals(emptyList<String>(), ime.log)
    }

    @Test
    fun `otherwise droidtop's input method connection is used`() {
        imeFocused = true
        imeSelected = true
        assertTrue(router.route() is Route.Ime)
        router.deliver(1, "b", true)
        assertEquals(listOf("back:1", "text:b", "enter"), ime.log)
        assertEquals(emptyList<String>(), own.log)
    }

    @Test
    fun `otherwise the console is told to switch keyboard`() {
        assertEquals(Route.SwitchKeyboard, router.route())
        assertEquals(Delivery.Refused("Switch to droidtop's keyboard to type from another device"), router.deliver(0, "c", false))
        assertEquals(emptyList<String>(), own.log + ime.log)
        imeSelected = true
        assertEquals(Route.NoField, router.route())
    }

    @Test
    fun `password input types are recognised`() {
        assertTrue(RemoteInputRouter.isPasswordInputType(0x81))
        assertTrue(RemoteInputRouter.isPasswordInputType(0x91))
        assertTrue(RemoteInputRouter.isPasswordInputType(0xe1))
        assertTrue(RemoteInputRouter.isPasswordInputType(0x12))
        assertFalse(RemoteInputRouter.isPasswordInputType(0x01))
        assertFalse(RemoteInputRouter.isPasswordInputType(0x21))
        assertFalse(RemoteInputRouter.isPasswordInputType(0x02))
    }

    @Test
    fun `a named key is pressed in the field the route names, or refused without one`() {
        val own = FakeTarget()
        val router = RemoteInputRouter(ownField = { own }, ime = { null }, imeSelected = { true })
        assertEquals(Delivery.Sent(false), router.press(RemoteKeys.keyCode("left")!!))
        assertEquals(listOf("key:21"), own.log)
        val none = RemoteInputRouter(ownField = { null }, ime = { null }, imeSelected = { true })
        assertEquals(Delivery.Refused(RemoteInputRouter.NO_FIELD), none.press(61))
    }

    @Test
    fun `kde connect's key names as android key codes`() {
        assertEquals(61, RemoteKeys.keyCode("tab"))
        assertEquals(112, RemoteKeys.keyCode("delete"))
        assertEquals(111, RemoteKeys.keyCode("escape"))
        assertEquals(131, RemoteKeys.keyCode("f1"))
        assertEquals(142, RemoteKeys.keyCode("f12"))
        // Backspace and Enter keep the text path; modifiers alone are not typed.
        assertNull(RemoteKeys.keyCode("backspace"))
        assertNull(RemoteKeys.keyCode("control"))
    }
}
