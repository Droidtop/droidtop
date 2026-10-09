package dev.droidtop.runtime.systemstatus

/**
 * The companion's notification rows and Now playing card as plain rules (docs/SPEC.md "The companion's tabs",
 * Droidtop/tracker#414): what each row offers, which rows are messages, when a row's text shows, and what a media
 * session's state means for the card. No Android here beyond constants, so it is tested without a device.
 */
object NotificationRows {
    enum class Action { REPLY, OPEN, DISMISS }

    /** "Show message text": when unlocked (following Android's lock-screen setting while locked), always, or never. */
    enum class MessageText(val key: String, val label: String) {
        WHEN_UNLOCKED("unlocked", "When unlocked (default)"),
        ALWAYS("always", "Always"),
        NEVER("never", "Never, tap to show");

        companion object {
            fun of(key: String?): MessageText = entries.firstOrNull { it.key == key } ?: WHEN_UNLOCKED
        }
    }

    /** Reply where the app takes a typed reply, Open where it has a tap action, Dismiss where Android lets it go. */
    fun actions(item: NotificationsStore.Item): List<Action> = buildList {
        if (item.canReply) add(Action.REPLY)
        if (item.contentIntent != null) add(Action.OPEN)
        if (item.clearable) add(Action.DISMISS)
    }

    /** The rows Social shows: the messages, newest first as the store keeps them. */
    fun messages(items: List<NotificationsStore.Item>): List<NotificationsStore.Item> = items.filter { it.message }

    /**
     * Whether a row shows its text, or only the app and the sender (tap to show). [lockScreenShowsPrivate] is
     * Android's own "show sensitive content" on the lock screen, which the default follows while locked.
     */
    fun showsText(setting: MessageText, locked: Boolean, lockScreenShowsPrivate: Boolean): Boolean = when (setting) {
        MessageText.ALWAYS -> true
        MessageText.NEVER -> false
        MessageText.WHEN_UNLOCKED -> !locked || lockScreenShowsPrivate
    }
}

/** Now playing: what the newest media session says, as the card draws it. */
data class NowPlaying(
    val title: String,
    val artist: String?,
    val app: String,
    val playing: Boolean,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val canPlayPause: Boolean,
) {
    companion object {
        private const val STATE_PLAYING = 3 // PlaybackState.STATE_PLAYING
        private const val STATE_BUFFERING = 6 // PlaybackState.STATE_BUFFERING
        private const val ACTION_PAUSE = 2L
        private const val ACTION_PLAY = 4L
        private const val ACTION_PREVIOUS = 16L
        private const val ACTION_NEXT = 32L
        private const val ACTION_PLAY_PAUSE = 512L

        /** The card for a session: null when it names nothing to show (no title). */
        fun from(title: String?, artist: String?, app: String, state: Int?, actions: Long): NowPlaying? {
            val name = title?.trim().orEmpty()
            if (name.isEmpty()) return null
            val playing = state == STATE_PLAYING || state == STATE_BUFFERING
            return NowPlaying(
                title = name,
                artist = artist?.trim()?.takeIf { it.isNotEmpty() },
                app = app,
                playing = playing,
                canPrevious = (actions and ACTION_PREVIOUS) != 0L,
                canNext = (actions and ACTION_NEXT) != 0L,
                canPlayPause = (actions and (ACTION_PLAY_PAUSE or if (playing) ACTION_PAUSE else ACTION_PLAY)) != 0L,
            )
        }
    }
}
