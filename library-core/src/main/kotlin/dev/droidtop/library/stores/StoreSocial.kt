package dev.droidtop.library.stores

import kotlinx.coroutines.flow.StateFlow

/*
 * Friends and chat of a store (docs/SPEC.md 7g, "Stores", Droidtop/tracker#313).
 * A store that stays connected while the person is signed in (Steam) shows who
 * is online and carries 1:1 messages; the Friends place and the Quick Menu draw
 * these types and know nothing of the store behind them.
 */

/** The person's own standing with the store's friends. [OFFLINE] also means "do not stay connected". */
enum class SocialPresence(val key: String, val label: String) {
    ONLINE("online", "Online"),
    INVISIBLE("invisible", "Invisible"),
    OFFLINE("offline", "Offline"),
    ;

    companion object {
        fun from(key: String?): SocialPresence = entries.firstOrNull { it.key == key } ?: ONLINE
    }
}

/** What a friend is doing, coarsely. */
enum class SocialState(val label: String) {
    OFFLINE("Offline"),
    ONLINE("Online"),
    AWAY("Away"),
    BUSY("Busy"),
    IN_GAME("In game"),
}

/** One friend as the list draws them. [activity] is the game they are in, when they are in one. */
data class SocialFriend(
    val id: String,
    val name: String,
    val state: SocialState,
    val activity: String?,
    val unread: Int,
    /** When the last message in the conversation was sent (epoch ms); 0 when there is none. */
    val lastMessageMs: Long,
)

/** One message of a conversation; [key] is unique within it and orders by time. */
data class SocialMessage(val key: String, val mine: Boolean, val text: String, val timeMs: Long)

/** Where the store's connection stands. */
enum class SocialLink(val label: String) {
    OFF("Offline"),
    CONNECTING("Connecting"),
    ONLINE("Connected"),
    RECONNECTING("Reconnecting"),
}

interface StoreSocial {
    /** Whether the connection is up, coming up, or being retried. */
    val link: StateFlow<SocialLink>

    /** The person's own display name once the store has said it. */
    val me: StateFlow<String?>

    /** The friends, in the order [SocialOrder] gives. */
    val friends: StateFlow<List<SocialFriend>>

    /** Unread messages over every conversation. */
    val unread: StateFlow<Int>

    /** The person's standing, kept in the store's own settings. Disk read: not on the main thread. */
    fun presence(context: android.content.Context): SocialPresence

    /** Changes the standing; going [SocialPresence.OFFLINE] ends the connection, any other starts it. */
    suspend fun setPresence(context: android.content.Context, presence: SocialPresence)

    /** The messages of one conversation, oldest first, as far as they are known. */
    fun conversation(friendId: String): StateFlow<List<SocialMessage>>

    /**
     * The person opened the conversation: its recent history is fetched, it is
     * marked read, and while it is open its new messages do not notify.
     */
    suspend fun open(friendId: String)

    /** The conversation was left. */
    fun close(friendId: String)

    /** Sends [text] to the friend; the message joins the conversation when the store has taken it. */
    suspend fun send(friendId: String, text: String): Result<Unit>
}

/** Every store's social side that is on. */
object StoreSocials {
    fun all(): List<Pair<StoreLibrary, StoreSocial>> = StoreLibraries.all().mapNotNull { store -> store.social?.let { store to it } }
}

/** The pure rules of the friends list and the conversation, so a screen and a test share them. */
object SocialOrder {
    private fun rank(state: SocialState): Int = when (state) {
        SocialState.IN_GAME -> 0
        SocialState.ONLINE -> 1
        SocialState.BUSY -> 2
        SocialState.AWAY -> 3
        SocialState.OFFLINE -> 4
    }

    /** Friends with unread messages first, then who is on (in a game, online, busy, away), offline last; by name inside each. */
    fun sorted(friends: Collection<SocialFriend>): List<SocialFriend> =
        friends.sortedWith(
            compareByDescending<SocialFriend> { it.unread > 0 }
                .thenBy { rank(it.state) }
                .thenBy { it.name.lowercase() },
        )

    /** The value column of a friend's row: the unread count, else the game, else the state. */
    fun value(friend: SocialFriend): String = when {
        friend.unread > 0 -> "${friend.unread} new"
        friend.state == SocialState.IN_GAME -> friend.activity?.takeIf { it.isNotBlank() } ?: SocialState.IN_GAME.label
        else -> friend.state.label
    }

    /** [messages] with [added] in time order, one of each key. */
    fun merged(messages: List<SocialMessage>, added: Collection<SocialMessage>): List<SocialMessage> =
        (messages + added).distinctBy { it.key }.sortedWith(compareBy<SocialMessage> { it.timeMs }.thenBy { it.key })

    private val BBCODE = Regex("\\[/?[a-zA-Z]+(?:=[^\\]]*)?]")

    /** A message's text without the BBCode Steam wraps links, bold and the like in. */
    fun plain(text: String): String = BBCODE.replace(text, "").trim()

    /** Whether the person's text is worth sending: not blank, and not longer than Steam takes in one message. */
    fun sendable(text: String): Boolean = text.isNotBlank() && text.length <= MAX_MESSAGE

    const val MAX_MESSAGE = 2000
}
