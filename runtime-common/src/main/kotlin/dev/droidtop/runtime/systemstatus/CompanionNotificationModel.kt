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

    /**
     * Status, not a message (Droidtop/tracker#786 addendum 2, item 6: "A notification that is ongoing, a foreground
     * service, NO_CLEAR, or carries a progress extra is status").
     */
    fun isStatus(ongoing: Boolean, foregroundService: Boolean, noClear: Boolean, hasProgress: Boolean): Boolean =
        ongoing || foregroundService || noClear || hasProgress

    /** Android's own packages, whose status notifications are charging, USB and wireless debugging. */
    private val SYSTEM_PACKAGES = setOf("android", "com.android.systemui")

    /**
     * A status notification droidtop's own indicators already show, so no surface lists it: Android's own (charging,
     * wireless debugging) and a VPN app's while it provides the VPN ([vpnProvider]), which the status line's VPN mark
     * says ("Charging, wireless debugging and VPN are already shown by droidtop's own indicators, so their
     * notifications are dropped").
     */
    fun dropped(status: Boolean, packageName: String, vpnProvider: Boolean): Boolean =
        status && (packageName in SYSTEM_PACKAGES || vpnProvider)

    /** A progress extra as a fraction: 0 to 1, -1 while indeterminate, null without one. */
    fun progress(now: Int, max: Int, indeterminate: Boolean): Float? = when {
        indeterminate -> -1f
        max > 0 -> (now.toFloat() / max).coerceIn(0f, 1f)
        else -> null
    }

    /**
     * A row's one short line and the longer text behind its expand. The line is the app's text (or the first line of
     * the big text when it has none), without the app's own touch instructions ([body]); the detail is the big text
     * or the inbox lines, only when it says more than the line. Android's whole big text was printed before, which
     * status apps fill with technical detail ("internal context data", the owner, on the RP5 capture).
     */
    fun lines(text: String?, bigText: String?, textLines: List<String>): Pair<String, String?> {
        val longer = bigText?.takeIf { it.isNotBlank() } ?: textLines.filter { it.isNotBlank() }.joinToString("\n").takeIf { it.isNotBlank() }
        val source = text?.takeIf { it.isNotBlank() } ?: longer?.lineSequence()?.firstOrNull { it.isNotBlank() }.orEmpty()
        val line = body(source.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
        val detail = longer?.let(::body)?.takeIf { it.isNotBlank() && it != line && it != body(source) }
        return line to detail
    }

    /**
     * Text without the app's own sentences telling a finger what to do ("Tap to turn off wireless debugging",
     * "Tap for more options."), which are wrong advice on a pad and noise on a touch row (Droidtop/tracker#674,
     * #786). The rest is the app's, unchanged. One rule for every surface; it was the Quick Menu's alone.
     */
    fun body(text: String): String =
        text.split(Regex("(?<=[.!?])\\s+"))
            .filterNot { TOUCH_INSTRUCTION.containsMatchIn(it.trim()) }
            .joinToString(" ")
            .trim()

    private val TOUCH_INSTRUCTION = Regex("^(tap|touch|click)\\b", RegexOption.IGNORE_CASE)

    /** The rows a list shows as notifications: everything that is not status. */
    fun rows(items: List<NotificationsStore.Item>): List<NotificationsStore.Item> = items.filter { !it.status }

    /** What is running in the background, listed apart from the rows and never as a message. */
    fun status(items: List<NotificationsStore.Item>): List<NotificationsStore.Item> = items.filter { it.status }

    /** How many Clear all would clear: zero means no Clear all is offered. */
    fun clearCount(items: List<NotificationsStore.Item>): Int = items.count { it.clearable }

    /** The apps a folded heading shows by icon: each once, newest first, at most [max]. */
    fun headerApps(items: List<NotificationsStore.Item>, max: Int = 5): List<NotificationsStore.Item> =
        items.distinctBy { it.packageName }.take(max)

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
