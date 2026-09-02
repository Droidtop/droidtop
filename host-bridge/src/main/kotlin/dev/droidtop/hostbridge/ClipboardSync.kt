package dev.droidtop.hostbridge

import android.os.Build

/**
 * The decision half of the host↔container clipboard bridge, kept free of
 * Android types so it can actually be tested (see ClipboardSyncTest) rather
 * than only exercised on a device.
 *
 * **One shared piece of state is the whole mechanism.** Both directions call
 * the same [accept]: Android→container when the system clipboard changes,
 * container→Android when the compositor reports a new selection. Because
 * they share [lastSynced], the echo each direction provokes in the other
 * (pushing to the container makes the compositor tell us about "a new"
 * selection; setting Android's clipboard fires our own change listener) is
 * recognised as text already in sync and dropped. That is the only thing
 * stopping a ping-pong loop, so it deliberately lives in exactly one place
 * instead of being half-solved with a suppression flag at each end.
 */
class ClipboardSync(private val maxBytes: Int = MAX_BYTES) {

    private var lastSynced: String? = null

    sealed interface Decision {
        /** Genuinely new text; hand it to the other side. */
        data class Forward(val text: String) : Decision

        /** The other side already has this — almost always our own echo. */
        data object AlreadyInSync : Decision

        /** Null, empty, or non-text (an image, a file list). */
        data object NothingToCopy : Decision

        /** Over [maxBytes]; dropped rather than truncated, so nothing lies. */
        data object TooLarge : Decision
    }

    fun accept(text: String?): Decision {
        if (text.isNullOrEmpty()) return Decision.NothingToCopy
        if (text == lastSynced) return Decision.AlreadyInSync
        if (text.toByteArray(Charsets.UTF_8).size > maxBytes) return Decision.TooLarge
        lastSynced = text
        return Decision.Forward(text)
    }

    /** Drops the memory of what was last synced — e.g. on reconnect. */
    fun reset() {
        lastSynced = null
    }

    companion object {
        /**
         * Matches `WaylandClient::kMaxClipboardBytes` in
         * host-bridge/native/src/wayland_client.h. The native side enforces
         * it too, since a container-side selection never passes through here
         * before being read off the transfer pipe; this copy is what keeps a
         * large *Android* clipboard from being pushed only to be refused.
         */
        const val MAX_BYTES: Int = 1 shl 20 // 1 MiB
    }
}

/**
 * When droidtop is allowed to READ Android's clipboard at all.
 *
 * From Android 10 (API 29) the platform restricts `getPrimaryClip` to the
 * app that currently has window focus, or the app that owns the current
 * input method — everyone else gets null, silently. droidtop satisfies the
 * second clause by shipping its own IME (`:input-keyboard`, docs/SPEC.md
 * §6a), which is one of the stated reasons that fork exists.
 *
 * Writing (`setPrimaryClip`) is NOT restricted by that rule, so the
 * container→Android direction is not gated on this; from Android 12 the
 * system additionally shows the user a toast when an app reads the
 * clipboard, which is another reason the read side is event-driven and
 * never polled.
 */
object ClipboardAccess {

    fun canRead(
        sdkInt: Int,
        hasWindowFocus: Boolean,
        ownKeyboardActive: Boolean,
    ): Boolean = sdkInt < Build.VERSION_CODES.Q || hasWindowFocus || ownKeyboardActive

    /** What to tell the user when [canRead] says no. */
    const val WHY_BLOCKED: String =
        "Android only lets the focused app or the active keyboard read the clipboard. " +
            "Copying from Android into the container works while droidtop is on screen, " +
            "or any time droidtop's own keyboard is the active one."
}
