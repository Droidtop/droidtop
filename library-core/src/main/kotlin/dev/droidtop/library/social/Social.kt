package dev.droidtop.library.social

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/*
 * Friends, presence and 1:1 chat from any service (docs/SPEC.md "Social", Droidtop/tracker#327).
 * A provider is one account on one service: Steam, which droidtop runs itself, or a plugin that
 * provides `social.provider@1` (docs/plugin-api.md C19). The Social place, the companion's Social
 * tab, the Quick Menu tile and the message notifications draw these types through [SocialHub] and
 * know nothing of the service behind them.
 */

/** The person's own standing on a service. [OFFLINE] also means "do not stay connected". */
enum class SocialPresence(val key: String, val label: String) {
    ONLINE("online", "Online"),
    INVISIBLE("invisible", "Invisible"),
    OFFLINE("offline", "Offline"),
    ;

    companion object {
        fun from(key: String?): SocialPresence = entries.firstOrNull { it.key == key } ?: ONLINE

        /** The standing [key] names, or null for anything else (a plugin's reply is not trusted to be one of ours). */
        fun parse(key: String?): SocialPresence? = entries.firstOrNull { it.key == key }
    }
}

/** What a friend is doing, coarsely. [key] is the word the plugin protocol uses. */
enum class SocialState(val key: String, val label: String) {
    OFFLINE("offline", "Offline"),
    ONLINE("online", "Online"),
    AWAY("away", "Away"),
    BUSY("busy", "Busy"),
    IN_GAME("in_game", "In game"),
    ;

    companion object {
        fun from(key: String?): SocialState = entries.firstOrNull { it.key == key } ?: OFFLINE
    }
}

/** One friend as the lists draw them. [activity] is the game they are in ("playing X"), when they are in one. */
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

/** Where a provider's connection stands. [key] is the word the plugin protocol uses. */
enum class SocialLink(val key: String, val label: String) {
    OFF("offline", "Offline"),
    CONNECTING("connecting", "Connecting"),
    ONLINE("online", "Connected"),
    RECONNECTING("reconnecting", "Reconnecting"),
    ;

    companion object {
        fun from(key: String?): SocialLink = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/**
 * One account on one service. Steam is built in (`SteamFriendsHub`); every running plugin that provides
 * `social.provider@1` is one more ([dev.droidtop.library.integrations.PluginSocialProvider]).
 */
interface SocialProvider {
    /** Unique over every provider: `steam`, or `plugin:<plugin id>/<entry id>`. Part of every contact's key. */
    val id: String

    /** The service's name, as the lists badge a friend with it. */
    val label: String

    /** Whether this provider has an account to show: the store is signed in, the plugin is running. Disk read: not on the main thread. */
    fun available(context: Context): Boolean

    /** Whether the connection is up, coming up, or being retried. */
    val link: StateFlow<SocialLink>

    /** The person's own display name once the service has said it. */
    val me: StateFlow<String?>

    /** The friends, in the order [SocialOrder] gives. */
    val friends: StateFlow<List<SocialFriend>>

    /** Unread messages over every conversation. */
    val unread: StateFlow<Int>

    /** The person's standing, or null when the service has none to set (no status choice is drawn). Disk read: not on the main thread. */
    fun presence(context: Context): SocialPresence?

    /** Changes the standing; for a store, going [SocialPresence.OFFLINE] ends the connection and any other starts it. */
    suspend fun setPresence(context: Context, presence: SocialPresence)

    /** The messages of one conversation, oldest first, as far as they are known. */
    fun conversation(friendId: String): StateFlow<List<SocialMessage>>

    /**
     * The person opened the conversation: its recent history is fetched, it is
     * marked read, and while it is open its new messages do not notify.
     */
    suspend fun open(friendId: String)

    /** The conversation was left. */
    fun close(friendId: String)

    /** Sends [text] to the friend; the message joins the conversation when the service has taken it. */
    suspend fun send(friendId: String, text: String): Result<Unit>

    /** Asks the service again, when it is not pushed to (a plugin). Called when a social screen opens, never on a timer. */
    suspend fun refresh(context: Context) {}
}

/** A friend with the provider they came from: the unit every merged list draws. */
data class SocialContact(val provider: SocialProvider, val friend: SocialFriend) {
    /** Unique over every provider: two services never collide, and one person on two services is two contacts. */
    val key: String get() = provider.id + "/" + friend.id
}

/** The pure rules of the lists and the conversation, so every screen and the tests share them. */
object SocialOrder {
    private fun rank(state: SocialState): Int = when (state) {
        SocialState.IN_GAME -> 0
        SocialState.ONLINE -> 1
        SocialState.BUSY -> 2
        SocialState.AWAY -> 3
        SocialState.OFFLINE -> 4
    }

    private val byFriend: Comparator<SocialFriend> =
        compareByDescending<SocialFriend> { it.unread > 0 }
            .thenBy { rank(it.state) }
            .thenBy { it.name.lowercase() }

    /** Friends with unread messages first, then who is on (in a game, online, busy, away), offline last; by name inside each. */
    fun sorted(friends: Collection<SocialFriend>): List<SocialFriend> = friends.sortedWith(byFriend)

    /**
     * Every provider's friends as one list, in the [sorted] order, the provider's label last so the same name on two
     * services keeps a stable place. A provider that is not on contributes nothing (the caller passes only those).
     */
    fun contacts(providers: Collection<SocialProvider>): List<SocialContact> =
        providers.flatMap { provider -> provider.friends.value.map { SocialContact(provider, it) } }
            .sortedWith(compareBy(byFriend) { c: SocialContact -> c.friend }.thenBy { it.provider.label.lowercase() })

    /** The contacts that have a conversation: unread ones first, then the newest. */
    fun conversations(contacts: Collection<SocialContact>): List<SocialContact> =
        contacts.filter { it.friend.unread > 0 || it.friend.lastMessageMs > 0L }
            .sortedWith(compareByDescending<SocialContact> { it.friend.unread > 0 }.thenByDescending { it.friend.lastMessageMs })

    /** Unread messages over every provider: the Quick Menu tile's and the companion tab's number. */
    fun unread(providers: Collection<SocialProvider>): Int = providers.sumOf { it.unread.value.coerceAtLeast(0) }

    /** The value column of a friend's row: the unread count, else the game, else the state. */
    fun value(friend: SocialFriend): String = when {
        friend.unread > 0 -> "${friend.unread} new"
        friend.state == SocialState.IN_GAME -> friend.activity?.takeIf { it.isNotBlank() } ?: SocialState.IN_GAME.label
        else -> friend.state.label
    }

    /** [value] with the service named, for a list that holds more than one provider. */
    fun value(contact: SocialContact, badged: Boolean): String =
        if (badged) value(contact.friend) + " · " + contact.provider.label else value(contact.friend)

    /** [messages] with [added] in time order, one of each key. */
    fun merged(messages: List<SocialMessage>, added: Collection<SocialMessage>): List<SocialMessage> =
        (messages + added).distinctBy { it.key }.sortedWith(compareBy<SocialMessage> { it.timeMs }.thenBy { it.key })

    private val BBCODE = Regex("\\[/?[a-zA-Z]+(?:=[^\\]]*)?]")

    /** A message's text without the BBCode Steam wraps links, bold and the like in. */
    fun plain(text: String): String = BBCODE.replace(text, "").trim()

    /** Whether the person's text is worth sending: not blank, and not longer than one message may be. */
    fun sendable(text: String): Boolean = text.isNotBlank() && text.length <= MAX_MESSAGE

    const val MAX_MESSAGE = 2000
}
