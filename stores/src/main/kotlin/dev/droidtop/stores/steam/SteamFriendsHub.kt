package dev.droidtop.stores.steam

import android.content.Context
import dev.droidtop.library.stores.SocialFriend
import dev.droidtop.library.stores.SocialLink
import dev.droidtop.library.stores.SocialMessage
import dev.droidtop.library.stores.SocialOrder
import dev.droidtop.library.stores.SocialPresence
import dev.droidtop.library.stores.SocialState
import dev.droidtop.library.stores.StoreSocial
import `in`.dragonbra.javasteam.enums.EFriendRelationship
import `in`.dragonbra.javasteam.enums.EPersonaState
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesFriendmessagesSteamclient.CFriendMessages_AckMessage_Notification
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesFriendmessagesSteamclient.CFriendMessages_GetRecentMessages_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesFriendmessagesSteamclient.CFriendMessages_IncomingMessage_Notification
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesFriendmessagesSteamclient.CFriendMessages_SendMessage_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesFriendmessagesSteamclient.CFriendsMessages_GetActiveMessageSessions_Request
import `in`.dragonbra.javasteam.rpc.service.FriendMessages
import `in`.dragonbra.javasteam.rpc.service.FriendMessagesClient
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.FriendsListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.PersonaStateCallback
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.callback.ServiceMethodNotification
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.await
import timber.log.Timber

/**
 * Steam's friends and 1:1 chat as droidtop's [StoreSocial] (docs/SPEC.md 7g,
 * "Stores", Droidtop/tracker#313): who is on and what they play, the unread
 * messages Steam holds, the recent history of a conversation, sending, and a
 * message arriving while the connection is kept.
 *
 * Everything arrives by JavaSteam's callbacks, nothing is polled: the friends
 * list and each friend's persona (SteamFriends), incoming messages (the
 * FriendMessagesClient notification, new Steam chat), the unread counts once
 * after each log-on (FriendMessages.GetActiveMessageSessions). The state lives
 * here across reconnects so the screens keep what they show; it is cleared on
 * sign-out. Conversations are kept in memory only: Steam holds the history.
 */
object SteamFriendsHub : StoreSocial {
    private const val TAG = "SteamFriendsHub"
    private const val CHAT_MSG = 1
    private const val HISTORY = 50

    /** Steam's 64-bit id of an individual account is this plus the account id. */
    private const val INDIVIDUAL_BASE = 76561197960265728L

    private class Person(var name: String, var state: SocialState, var activity: String?)

    private val lock = Any()
    private val friendIds = LinkedHashSet<Long>()
    private val people = HashMap<Long, Person>()
    private val unreadBy = HashMap<Long, Int>()
    private val lastMessageMs = HashMap<Long, Long>()
    private val open = HashSet<Long>()
    private val conversations = HashMap<Long, MutableStateFlow<List<SocialMessage>>>()

    private val friendList = MutableStateFlow<List<SocialFriend>>(emptyList())
    private val unreadTotal = MutableStateFlow(0)
    private val linkState = MutableStateFlow(SocialLink.OFF)
    private val meName = MutableStateFlow<String?>(null)

    override val friends: StateFlow<List<SocialFriend>> get() = friendList
    override val unread: StateFlow<Int> get() = unreadTotal
    override val link: StateFlow<SocialLink> get() = linkState
    override val me: StateFlow<String?> get() = meName

    @Volatile private var messages: FriendMessages? = null
    @Volatile private var appContext: Context? = null

    /** Called for a message that arrives while its conversation is not open: friend id, name, text. Set by the app, which owns notifications. */
    @Volatile
    var onIncoming: ((friendId: String, name: String, text: String) -> Unit)? = null

    fun setLink(link: SocialLink) {
        linkState.value = link
    }

    /** Starts reading [steam]'s friends and messages through [manager]; the returned subscriptions end with the connection. */
    fun attach(manager: CallbackManager, steam: SteamClient, context: Context): List<Closeable> {
        appContext = context.applicationContext
        val unified = steam.getHandler(SteamUnifiedMessages::class.java)
        // JavaSteam routes a service's notifications by service name: both must exist.
        messages = unified?.createService(FriendMessages::class.java)
        unified?.createService(FriendMessagesClient::class.java)
        return listOf(
            manager.subscribe(FriendsListCallback::class.java) { onFriendsList(it) },
            manager.subscribe(PersonaStateCallback::class.java) { onPersona(it, steam) },
            manager.subscribe(ServiceMethodNotification::class.java) { onNotification(it) },
        )
    }

    /** The connection ended; what is known is kept for the next one. */
    fun detach() {
        messages = null
    }

    /** Forgets everything: the person signed out. */
    fun clear() {
        synchronized(lock) {
            friendIds.clear()
            people.clear()
            unreadBy.clear()
            lastMessageMs.clear()
            open.clear()
            conversations.clear()
        }
        meName.value = null
        publish()
    }

    private fun onFriendsList(callback: FriendsListCallback) {
        synchronized(lock) {
            if (!callback.isIncremental) friendIds.clear()
            for (friend in callback.friendList) {
                if (!friend.steamID.isIndividualAccount) continue
                val id = friend.steamID.convertToUInt64()
                if (friend.relationship == EFriendRelationship.Friend) friendIds += id else friendIds -= id
            }
        }
        publish()
    }

    private fun onPersona(callback: PersonaStateCallback, steam: SteamClient) {
        if (!callback.friendId.isIndividualAccount) return
        val id = callback.friendId.convertToUInt64()
        if (id == steam.steamID?.convertToUInt64()) {
            callback.playerName.takeIf { it.isNotBlank() }?.let { meName.value = it }
            return
        }
        synchronized(lock) {
            val person = people.getOrPut(id) { Person(callback.playerName, SocialState.OFFLINE, null) }
            if (callback.playerName.isNotBlank()) person.name = callback.playerName
            val game = callback.gameName.takeIf { it.isNotBlank() }
            person.state = stateOf(callback.personaState, inGame = game != null || callback.gamePlayedAppId != 0)
            person.activity = game
        }
        publish()
    }

    private fun onNotification(notification: ServiceMethodNotification<*>) {
        if (!notification.jobName.startsWith("FriendMessagesClient.IncomingMessage")) return
        val body = notification.body as? CFriendMessages_IncomingMessage_Notification.Builder ?: return
        // Typing and invitations come the same way; only chat lines are messages.
        if (body.chatEntryType != CHAT_MSG) return
        val text = SocialOrder.plain(body.messageNoBbcode.ifBlank { body.message })
        if (text.isBlank()) return
        val friend = body.steamidFriend
        val time = body.rtime32ServerTimestamp * 1000L
        val message = SocialMessage("${body.rtime32ServerTimestamp}.${body.ordinal}", mine = body.localEcho, text = text, timeMs = time)
        var shown = false
        synchronized(lock) {
            threadOf(friend).value = SocialOrder.merged(threadOf(friend).value, listOf(message))
            lastMessageMs[friend] = maxOf(lastMessageMs[friend] ?: 0L, time)
            if (!message.mine) {
                if (friend in open) shown = true else unreadBy[friend] = (unreadBy[friend] ?: 0) + 1
            }
        }
        publish()
        if (!message.mine) {
            if (shown) acknowledge(friend) else notifyIncoming(friend, text)
        }
    }

    private fun notifyIncoming(friend: Long, text: String) {
        // The person may have turned message notifications off (Steam's page in the Stores place).
        val context = appContext
        if (context != null && !SteamPrefs.notifyMessages(context)) return
        val name = synchronized(lock) { people[friend]?.name } ?: "Steam"
        runCatching { onIncoming?.invoke(friend.toString(), name, text) }
            .onFailure { Timber.tag(TAG).w(it, "Notifying a message failed") }
    }

    private fun acknowledge(friend: Long) {
        runCatching {
            messages?.ackMessage(
                CFriendMessages_AckMessage_Notification.newBuilder().apply {
                    steamidPartner = friend
                    timestamp = (System.currentTimeMillis() / 1000L).toInt()
                }.build(),
            )
        }
    }

    private fun threadOf(friend: Long): MutableStateFlow<List<SocialMessage>> =
        conversations.getOrPut(friend) { MutableStateFlow(emptyList()) }

    /** Recomputes the lists the screens read. */
    private fun publish() {
        val list: List<SocialFriend>
        val total: Int
        synchronized(lock) {
            list = SocialOrder.sorted(
                friendIds.map { id ->
                    val person = people[id]
                    SocialFriend(
                        id = id.toString(),
                        name = person?.name?.takeIf { it.isNotBlank() } ?: "Friend",
                        state = person?.state ?: SocialState.OFFLINE,
                        activity = person?.activity,
                        unread = unreadBy[id] ?: 0,
                        lastMessageMs = lastMessageMs[id] ?: 0L,
                    )
                },
            )
            total = list.sumOf { it.unread }
        }
        friendList.value = list
        unreadTotal.value = total
    }

    /**
     * Called by the kept connection after each log-on: the person's standing is
     * announced and the unread counts Steam holds are read. Friends are not
     * told about a connection that was only opened for a job.
     */
    suspend fun online(context: Context) {
        pushPresence(context)
        runCatching { readSessions() }.onFailure { Timber.tag(TAG).w(it, "Reading the unread messages failed") }
    }

    /** Tells Steam the person's standing (online or invisible); Offline never gets here, it closes the connection. */
    fun pushPresence(context: Context) {
        val state = when (SteamPrefs.presence(context)) {
            SocialPresence.INVISIBLE -> EPersonaState.Invisible
            else -> EPersonaState.Online
        }
        SteamSession.friends?.setPersonaState(state)
    }

    private suspend fun readSessions() {
        val service = messages ?: return
        val answer = service.getActiveMessageSessions(
            CFriendsMessages_GetActiveMessageSessions_Request.newBuilder().apply {
                lastmessageSince = 0
                onlySessionsWithMessages = true
            }.build(),
        ).await()
        if (answer.result != EResult.OK) return
        synchronized(lock) {
            for (session in answer.body.messageSessionsList) {
                val id = INDIVIDUAL_BASE + (session.accountidFriend.toLong() and 0xFFFFFFFFL)
                unreadBy[id] = if (id in open) 0 else session.unreadMessageCount
                lastMessageMs[id] = maxOf(lastMessageMs[id] ?: 0L, session.lastMessage * 1000L)
            }
        }
        publish()
    }

    override fun presence(context: Context): SocialPresence = SteamPrefs.presence(context)

    override suspend fun setPresence(context: Context, presence: SocialPresence) {
        SteamPrefs.setPresence(context, presence)
        SteamConnection.refresh(context)
    }

    override fun conversation(friendId: String): StateFlow<List<SocialMessage>> {
        val id = friendId.toLongOrNull() ?: return MutableStateFlow(emptyList())
        return synchronized(lock) { threadOf(id) }
    }

    override suspend fun open(friendId: String) {
        val id = friendId.toLongOrNull() ?: return
        synchronized(lock) {
            open += id
            unreadBy[id] = 0
        }
        publish()
        runCatching { readHistory(id) }.onFailure { Timber.tag(TAG).w(it, "Reading the history of $friendId failed") }
        acknowledge(id)
    }

    override fun close(friendId: String) {
        friendId.toLongOrNull()?.let { id -> synchronized(lock) { open -= id } }
    }

    private suspend fun readHistory(friend: Long) {
        val service = messages ?: return
        val mine = SteamSession.steamId64 ?: return
        val answer = service.getRecentMessages(
            CFriendMessages_GetRecentMessages_Request.newBuilder().apply {
                steamid1 = mine
                steamid2 = friend
                count = HISTORY
                bbcodeFormat = false
            }.build(),
        ).await()
        if (answer.result != EResult.OK) return
        val myAccount = mine and 0xFFFFFFFFL
        val read = answer.body.messagesList.map { line ->
            SocialMessage(
                key = "${line.timestamp}.${line.ordinal}",
                mine = (line.accountid.toLong() and 0xFFFFFFFFL) == myAccount,
                text = SocialOrder.plain(line.message),
                timeMs = line.timestamp * 1000L,
            )
        }.filter { it.text.isNotBlank() }
        synchronized(lock) {
            threadOf(friend).value = SocialOrder.merged(threadOf(friend).value, read)
            read.maxOfOrNull { it.timeMs }?.let { lastMessageMs[friend] = maxOf(lastMessageMs[friend] ?: 0L, it) }
        }
        publish()
    }

    override suspend fun send(friendId: String, text: String): Result<Unit> = runCatching {
        val id = friendId.toLongOrNull() ?: error("That friend is not on Steam")
        val line = text.trim()
        require(SocialOrder.sendable(line)) { "A message can be up to ${SocialOrder.MAX_MESSAGE} characters" }
        val service = messages ?: error("Not connected to Steam")
        val answer = service.sendMessage(
            CFriendMessages_SendMessage_Request.newBuilder().apply {
                steamid = id
                chatEntryType = CHAT_MSG
                message = line
                containsBbcode = false
                echoToSender = false
                clientMessageId = UUID.randomUUID().toString()
            }.build(),
        ).await()
        check(answer.result == EResult.OK) { "Steam did not take the message (${answer.result})" }
        val time = answer.body.serverTimestamp * 1000L
        synchronized(lock) {
            threadOf(id).value = SocialOrder.merged(
                threadOf(id).value,
                listOf(SocialMessage("${answer.body.serverTimestamp}.${answer.body.ordinal}", mine = true, text = line, timeMs = time)),
            )
            lastMessageMs[id] = maxOf(lastMessageMs[id] ?: 0L, time)
        }
        publish()
    }

    /** A friend's standing as droidtop shows it. */
    fun stateOf(state: EPersonaState, inGame: Boolean): SocialState = when {
        state == EPersonaState.Offline || state == EPersonaState.Invisible -> SocialState.OFFLINE
        inGame -> SocialState.IN_GAME
        state == EPersonaState.Busy -> SocialState.BUSY
        state == EPersonaState.Away || state == EPersonaState.Snooze -> SocialState.AWAY
        else -> SocialState.ONLINE
    }
}
