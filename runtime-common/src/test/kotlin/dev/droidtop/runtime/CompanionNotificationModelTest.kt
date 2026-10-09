package dev.droidtop.runtime

import dev.droidtop.runtime.systemstatus.NotificationRows
import dev.droidtop.runtime.systemstatus.NotificationRows.MessageText
import dev.droidtop.runtime.systemstatus.NotificationsStore
import dev.droidtop.runtime.systemstatus.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The companion's notification rows and Now playing card (CompanionNotificationModel.kt). */
class CompanionNotificationModelTest {
    private fun item(key: String, canReply: Boolean = false, message: Boolean = false, clearable: Boolean = true) =
        NotificationsStore.Item(
            key = key, packageName = "com.discord", appLabel = "Discord", title = "Sam", text = "gg",
            postTime = 0L, contentIntent = null, clearable = clearable, canReply = canReply, message = message,
        )

    @Test fun `a notification with a typed reply gets a Reply row`() {
        assertEquals(listOf(NotificationRows.Action.REPLY, NotificationRows.Action.DISMISS), NotificationRows.actions(item("a", canReply = true)))
        assertEquals(listOf(NotificationRows.Action.DISMISS), NotificationRows.actions(item("b")))
        assertTrue(NotificationRows.actions(item("c", clearable = false)).isEmpty())
    }

    @Test fun `Social shows only the messages`() {
        val items = listOf(item("a", message = true), item("b"), item("c", message = true))
        assertEquals(listOf("a", "c"), NotificationRows.messages(items).map { it.key })
    }

    @Test fun `message text follows the setting and the lock screen`() {
        assertTrue(NotificationRows.showsText(MessageText.WHEN_UNLOCKED, locked = false, lockScreenShowsPrivate = false))
        assertFalse(NotificationRows.showsText(MessageText.WHEN_UNLOCKED, locked = true, lockScreenShowsPrivate = false))
        assertTrue(NotificationRows.showsText(MessageText.WHEN_UNLOCKED, locked = true, lockScreenShowsPrivate = true))
        assertTrue(NotificationRows.showsText(MessageText.ALWAYS, locked = true, lockScreenShowsPrivate = false))
        assertFalse(NotificationRows.showsText(MessageText.NEVER, locked = false, lockScreenShowsPrivate = true))
        assertEquals(MessageText.WHEN_UNLOCKED, MessageText.of("nonsense"))
    }

    @Test fun `a media session maps to the card`() {
        val playing = NowPlaying.from("Song", "Band", "Spotify", state = 3, actions = 16L or 32L or 2L)!!
        assertTrue(playing.playing && playing.canPrevious && playing.canNext && playing.canPlayPause)
        val paused = NowPlaying.from("Song", " ", "Spotify", state = 2, actions = 4L)!!
        assertFalse(paused.playing || paused.canPrevious || paused.canNext)
        assertTrue(paused.canPlayPause)
        assertNull(paused.artist)
        assertNull(NowPlaying.from(null, "Band", "Spotify", 3, 0L))
    }
}
