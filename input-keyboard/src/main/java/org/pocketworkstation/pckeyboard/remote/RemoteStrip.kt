package org.pocketworkstation.pckeyboard.remote

import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the keyboard's own strip says of a live "Type from another device" session (docs/SPEC.md, "Type from another
 * device", "On the keyboard", Droidtop/tracker#903). [live]: a phone is paired and may type, and the strip is shown;
 * otherwise it is hidden and the rest is empty. [sent]: what the phone last typed, null in a password field, where the
 * text is never kept here at all. [notReceived]: why the last thing sent went nowhere (the router's notice); the next
 * thing that lands clears it.
 */
data class RemoteStrip(val live: Boolean = false, val sent: String? = null, val notReceived: String? = null) {
    companion object {
        val HIDDEN = RemoteStrip()
    }
}

/**
 * The strip's state as the session and the router change it, with no Android in it so it is unit-tested. [session]
 * comes from the session's own state (paired, or not); [delivered] from the router, for each edit or key it was asked
 * to type. A delivery while no session is live (KDE Connect's remote keyboard on its own) changes nothing: the strip is
 * the phone session's. Listeners run on the caller's thread, which is the main thread for both.
 */
class RemoteStripModel {
    @Volatile
    var state: RemoteStrip = RemoteStrip.HIDDEN
        private set

    private val listeners = CopyOnWriteArrayList<(RemoteStrip) -> Unit>()

    fun addListener(listener: (RemoteStrip) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (RemoteStrip) -> Unit) {
        listeners.remove(listener)
    }

    /** The session is paired ([live]) or not; a new pairing starts with nothing sent. */
    fun session(live: Boolean) {
        if (live == state.live) return
        set(if (live) RemoteStrip(live = true) else RemoteStrip.HIDDEN)
    }

    /**
     * What the router did with one edit. [shown] is what the edit was ("Enter", the text...), or null for a key press,
     * which keeps the last text. A password field's text is dropped here, not hidden later.
     */
    fun delivered(shown: String?, delivery: Delivery) {
        val now = state
        if (!now.live) return
        set(
            when (delivery) {
                is Delivery.Sent -> RemoteStrip(live = true, sent = if (delivery.secret) null else shown ?: now.sent)
                is Delivery.Refused -> now.copy(notReceived = delivery.notice)
            },
        )
    }

    private fun set(next: RemoteStrip) {
        if (next == state) return
        state = next
        listeners.forEach { it(next) }
    }
}
