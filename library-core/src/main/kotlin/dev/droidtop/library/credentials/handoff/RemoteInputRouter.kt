package dev.droidtop.library.credentials.handoff

/** Where one person-typed edit lands: a field that can take text, backspaces and Enter. */
interface RemoteTarget {
    /** Whether the field takes a password: the console never shows what went in, and the phone's box is masked. */
    val secret: Boolean

    fun commit(text: String)

    fun backspace(count: Int)

    fun enter()
}

/** Where an edit is delivered, or why it cannot be. */
sealed interface Route {
    /** (a) The focused field is droidtop's own and droidtop's keyboard serves it: the path its own keys take. */
    class OwnField(val target: RemoteTarget) : Route

    /** (b) Droidtop's input method is the active one and has an editor: its `InputConnection`. */
    class Ime(val target: RemoteTarget) : Route

    /** (c) Droidtop's input method is not the active one: nothing can be typed into another app's field. */
    data object SwitchKeyboard : Route

    /** Droidtop's input method is active but no field has focus. */
    data object NoField : Route
}

/** Runs a block on the thread the fields live on and returns its result; [Direct] runs it where it is called. */
interface FieldThread {
    fun <T> call(block: () -> T): T

    object Direct : FieldThread {
        override fun <T> call(block: () -> T): T = block()
    }
}

sealed interface Delivery {
    data class Sent(val secret: Boolean) : Delivery

    /** Nothing was typed; [notice] is for the console and the phone. */
    data class Refused(val notice: String) : Delivery
}

/**
 * The one choice of where text from another device goes (docs/SPEC.md, "Type from another device"), with no Android
 * in it so it is unit-tested: droidtop's own field first, else droidtop's input method's connection, else a notice.
 * [fieldThread] puts a delivery on the thread the fields live on (the main thread); the server's own thread never touches a
 * view or a connection.
 */
class RemoteInputRouter(
    private val ownField: () -> RemoteTarget?,
    private val ime: () -> RemoteTarget?,
    private val imeSelected: () -> Boolean,
    private val fieldThread: FieldThread = FieldThread.Direct,
) {
    fun route(): Route {
        ownField()?.let { return Route.OwnField(it) }
        ime()?.let { return Route.Ime(it) }
        return if (imeSelected()) Route.NoField else Route.SwitchKeyboard
    }

    /** Applies [back] backspaces, then [text], then Enter if [enter], all to the one field the route names. */
    fun deliver(back: Int, text: String, enter: Boolean): Delivery = fieldThread.call {
        when (val route = route()) {
            is Route.OwnField -> apply(route.target, back, text, enter)
            is Route.Ime -> apply(route.target, back, text, enter)
            Route.SwitchKeyboard -> Delivery.Refused(SWITCH_KEYBOARD)
            Route.NoField -> Delivery.Refused(NO_FIELD)
        }
    }

    /** Whether the field in focus is a password, for the phone's box; false when nothing is. */
    fun secret(): Boolean = fieldThread.call {
        when (val route = route()) {
            is Route.OwnField -> route.target.secret
            is Route.Ime -> route.target.secret
            else -> false
        }
    }

    private fun apply(target: RemoteTarget, back: Int, text: String, enter: Boolean): Delivery {
        if (back > 0) target.backspace(back)
        if (text.isNotEmpty()) target.commit(text)
        if (enter) target.enter()
        return Delivery.Sent(target.secret)
    }

    companion object {
        const val SWITCH_KEYBOARD = "Switch to droidtop's keyboard to type from another device"
        const val NO_FIELD = "Tap a text field on this device first"

        private const val TYPE_MASK_CLASS = 0xf
        private const val TYPE_MASK_VARIATION = 0xff0
        private const val CLASS_TEXT = 0x1
        private const val CLASS_NUMBER = 0x2
        private const val TEXT_PASSWORD = 0x80
        private const val TEXT_VISIBLE_PASSWORD = 0x90
        private const val TEXT_WEB_PASSWORD = 0xe0
        private const val NUMBER_PASSWORD = 0x10

        /** Whether an `EditorInfo.inputType` is a password field's (text, visible, web or numeric PIN). */
        fun isPasswordInputType(inputType: Int): Boolean {
            val variation = inputType and TYPE_MASK_VARIATION
            return when (inputType and TYPE_MASK_CLASS) {
                CLASS_TEXT -> variation == TEXT_PASSWORD || variation == TEXT_VISIBLE_PASSWORD || variation == TEXT_WEB_PASSWORD
                CLASS_NUMBER -> variation == NUMBER_PASSWORD
                else -> false
            }
        }

        /** What the console shows of what was sent: the text, or bullets for a password. */
        fun forConsole(text: String, secret: Boolean): String = if (secret) "••••" else text
    }
}
