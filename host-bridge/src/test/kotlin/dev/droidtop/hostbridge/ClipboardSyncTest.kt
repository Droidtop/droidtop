package dev.droidtop.hostbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSyncTest {

    @Test
    fun `new text is forwarded`() {
        val sync = ClipboardSync()
        assertEquals(ClipboardSync.Decision.Forward("hello"), sync.accept("hello"))
    }

    @Test
    fun `repeating the same text is not forwarded twice`() {
        val sync = ClipboardSync()
        sync.accept("hello")
        assertEquals(ClipboardSync.Decision.AlreadyInSync, sync.accept("hello"))
    }

    /**
     * The loop this bridge would otherwise have: pushing Android's clipboard
     * into the container makes the compositor announce "a new selection",
     * which would be pushed straight back, forever. Both directions share
     * one [ClipboardSync] precisely so the echo is recognised.
     */
    @Test
    fun `echo from the other direction is suppressed`() {
        val sync = ClipboardSync()

        // Android -> container
        assertEquals(ClipboardSync.Decision.Forward("copied"), sync.accept("copied"))
        // the compositor reports the selection we just set
        assertEquals(ClipboardSync.Decision.AlreadyInSync, sync.accept("copied"))
        // ...and setting Android's clipboard would fire our own change listener
        assertEquals(ClipboardSync.Decision.AlreadyInSync, sync.accept("copied"))
    }

    @Test
    fun `container text still forwards after an Android push of different text`() {
        val sync = ClipboardSync()
        sync.accept("from android")
        assertEquals(ClipboardSync.Decision.Forward("from container"), sync.accept("from container"))
        // and back again
        assertEquals(ClipboardSync.Decision.Forward("from android"), sync.accept("from android"))
    }

    @Test
    fun `null and empty are not clipboard content`() {
        val sync = ClipboardSync()
        assertEquals(ClipboardSync.Decision.NothingToCopy, sync.accept(null))
        assertEquals(ClipboardSync.Decision.NothingToCopy, sync.accept(""))
    }

    /**
     * A clipboard read that Android refused arrives as a null clip, not as
     * an error. It must not be mistaken for "the user cleared the clipboard"
     * and pushed to the container as an empty selection.
     */
    @Test
    fun `a refused read does not clear what was already synced`() {
        val sync = ClipboardSync()
        sync.accept("still valid")
        assertEquals(ClipboardSync.Decision.NothingToCopy, sync.accept(null))
        assertEquals(ClipboardSync.Decision.AlreadyInSync, sync.accept("still valid"))
    }

    @Test
    fun `oversized text is dropped rather than truncated`() {
        val sync = ClipboardSync(maxBytes = 8)
        assertEquals(ClipboardSync.Decision.TooLarge, sync.accept("123456789"))
        // and nothing was remembered, so a later small value still forwards
        assertEquals(ClipboardSync.Decision.Forward("ok"), sync.accept("ok"))
    }

    /** The limit is bytes of UTF-8, not characters — an emoji is four. */
    @Test
    fun `the size limit counts utf-8 bytes`() {
        val sync = ClipboardSync(maxBytes = 4)
        assertEquals(ClipboardSync.Decision.Forward("😀"), sync.accept("😀"))
        assertEquals(ClipboardSync.Decision.TooLarge, sync.accept("😀a"))
    }

    @Test
    fun `reset forgets what was synced`() {
        val sync = ClipboardSync()
        sync.accept("hello")
        sync.reset()
        assertEquals(ClipboardSync.Decision.Forward("hello"), sync.accept("hello"))
    }

    @Test
    fun `focused app may read the clipboard`() {
        assertTrue(ClipboardAccess.canRead(sdkInt = 34, hasWindowFocus = true, ownKeyboardActive = false))
    }

    /**
     * The reason `:input-keyboard` is forked in rather than recommended
     * (docs/SPEC.md §6a): being the current IME is the other way an app is
     * allowed to read the clipboard on Android 10+.
     */
    @Test
    fun `owning the active keyboard may read the clipboard unfocused`() {
        assertTrue(ClipboardAccess.canRead(sdkInt = 34, hasWindowFocus = false, ownKeyboardActive = true))
    }

    @Test
    fun `unfocused without the keyboard may not read the clipboard`() {
        assertFalse(ClipboardAccess.canRead(sdkInt = 34, hasWindowFocus = false, ownKeyboardActive = false))
    }

    @Test
    fun `before Android 10 there is no restriction to satisfy`() {
        assertTrue(ClipboardAccess.canRead(sdkInt = 28, hasWindowFocus = false, ownKeyboardActive = false))
    }
}
