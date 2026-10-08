package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.social.SocialFriend
import dev.droidtop.library.social.SocialHub
import dev.droidtop.library.social.SocialLink
import dev.droidtop.library.social.SocialMessage
import dev.droidtop.library.social.SocialOrder
import dev.droidtop.library.social.SocialPresence
import dev.droidtop.library.social.SocialProvider
import dev.droidtop.library.social.SocialState
import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginReply
import dev.droidtop.pluginhost.ProvidedPoint
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * The replies of `social.provider@1` (docs/plugin-api.md 3 C19) read into droidtop's own social types, and
 * nothing else: a plugin's data is checked, capped and given defaults here, so a provider that sends too
 * much, too little or the wrong words never reaches a screen as anything but ordinary rows. Pure, so the
 * tests read it without a device.
 */
object PluginSocialProtocol {
    const val POINT = "social.provider"

    /** The host API a provider calls to say something changed (docs/plugin-api.md C19, `social.changed`). */
    const val CHANGED_API = "social"
    const val CHANGED_OP = "changed"

    const val MAX_FRIENDS = 2000
    const val MAX_MESSAGES = 200
    const val MAX_NAME = 100
    const val MAX_TEXT = 4000

    /** What `account` says: the connection, the person's name there and their standing (null: nothing to set). */
    data class Account(val link: SocialLink, val name: String?, val presence: SocialPresence?)

    fun account(data: JSONObject): Account = Account(
        link = SocialLink.from(data.optString("link")),
        name = data.optString("name").trim().take(MAX_NAME).takeIf { it.isNotEmpty() },
        presence = SocialPresence.parse(data.optString("presence").takeIf { data.has("presence") }),
    )

    /** `friends`: one per id (the first wins), a missing name read as "Friend", counts never negative; in the lists' order. */
    fun friends(data: JSONObject): List<SocialFriend> {
        val list = data.optJSONArray("friends") ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<SocialFriend>()
        for (i in 0 until list.length()) {
            if (out.size >= MAX_FRIENDS) break
            val f = list.optJSONObject(i) ?: continue
            val id = f.optString("id").trim().takeIf { it.isNotEmpty() } ?: continue
            if (!seen.add(id)) continue
            out += SocialFriend(
                id = id,
                name = f.optString("name").trim().take(MAX_NAME).ifEmpty { "Friend" },
                state = SocialState.from(f.optString("state")),
                activity = f.optString("activity").trim().take(MAX_NAME).takeIf { it.isNotEmpty() },
                unread = f.optInt("unread", 0).coerceAtLeast(0),
                lastMessageMs = f.optLong("lastMessageMs", 0L).coerceAtLeast(0L),
            )
        }
        return SocialOrder.sorted(out)
    }

    /** One message, or null when it has no key or no text. */
    fun message(m: JSONObject?, mineDefault: Boolean = false): SocialMessage? {
        m ?: return null
        val key = m.optString("key").trim().takeIf { it.isNotEmpty() } ?: return null
        val text = m.optString("text").trim().take(MAX_TEXT).takeIf { it.isNotEmpty() } ?: return null
        return SocialMessage(key = key, mine = m.optBoolean("mine", mineDefault), text = text, timeMs = m.optLong("timeMs", 0L).coerceAtLeast(0L))
    }

    /** `conversation`: the newest [MAX_MESSAGES] in time order, one of each key. */
    fun messages(data: JSONObject): List<SocialMessage> {
        val list = data.optJSONArray("messages") ?: return emptyList()
        val read = (0 until list.length()).mapNotNull { message(list.optJSONObject(it)) }
        return SocialOrder.merged(emptyList(), read).takeLast(MAX_MESSAGES)
    }

    /** What `social.changed` carried: which provider entry, which conversation, and a message when one arrived. */
    data class Change(val service: String?, val friendId: String?, val message: SocialMessage?)

    fun change(args: JSONObject): Change = Change(
        service = args.optString("service").trim().takeIf { it.isNotEmpty() },
        friendId = args.optString("friendId").trim().takeIf { it.isNotEmpty() },
        message = message(args.optJSONObject("message")),
    )

    /** The `args` of a call to a provider entry: every call names the entry ([service]) it is for. */
    fun args(service: String, vararg kv: Pair<String, Any>): JSONObject =
        JSONObject().put("service", service).apply { kv.forEach { (k, v) -> put(k, v) } }

    /** The words an error reply carries, for a "Not sent" row. */
    fun failure(reply: PluginReply, label: String): String =
        reply.message?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME * 2) ?: "$label did not take the message"
}

/**
 * One plugin's `social.provider@1` entry as a [SocialProvider] (docs/SPEC.md "Social"). It keeps what the plugin
 * last said in memory, the same way Steam's hub does, so a screen draws at once and a plugin that is slow or
 * failing keeps showing the last list. It asks the plugin only when a social screen opens ([refresh]), when the
 * person does something (open, send, change standing) and when the plugin says something changed ([changed]).
 */
class PluginSocialProvider internal constructor(
    @Volatile internal var record: PluginRecord,
    entry: ProvidedPoint,
    private val appContext: Context,
) : SocialProvider {
    val pluginId: String = record.manifest.id
    val service: String = entry.id ?: "default"

    override val id: String = "plugin:$pluginId/$service"

    @Volatile
    override var label: String = entry.label ?: record.manifest.label
        internal set

    private val linkState = MutableStateFlow(SocialLink.CONNECTING)
    private val meName = MutableStateFlow<String?>(null)
    private val friendList = MutableStateFlow<List<SocialFriend>>(emptyList())
    private val unreadTotal = MutableStateFlow(0)
    private val conversations = ConcurrentHashMap<String, MutableStateFlow<List<SocialMessage>>>()
    private val open = ConcurrentHashMap.newKeySet<String>()
    private val refreshing = Mutex()

    @Volatile private var standing: SocialPresence? = null

    override val link: StateFlow<SocialLink> get() = linkState
    override val me: StateFlow<String?> get() = meName
    override val friends: StateFlow<List<SocialFriend>> get() = friendList
    override val unread: StateFlow<Int> get() = unreadTotal

    override fun available(context: Context): Boolean = record.runnable()

    override fun presence(context: Context): SocialPresence? = standing

    private suspend fun call(op: String, args: JSONObject, userInitiated: Boolean): PluginReply {
        val policy = PluginCrashPolicy(appContext)
        return try {
            policy.handle(
                record,
                newCall(PluginSocialProtocol.POINT, op, SURFACE, args, BUDGET_MS),
                timeoutMs = BUDGET_MS,
                crashOnTimeout = false,
                userInitiated = userInitiated,
            )
        } finally {
            policy.shutdown()
        }
    }

    override suspend fun refresh(context: Context) {
        refreshing.withLock {
            val account = call("account", PluginSocialProtocol.args(service), userInitiated = false)
            if (account.ok) {
                val a = PluginSocialProtocol.account(account.data)
                linkState.value = a.link
                meName.value = a.name
                standing = a.presence
            } else {
                linkState.value = SocialLink.OFF
            }
            val reply = call("friends", PluginSocialProtocol.args(service), userInitiated = false)
            if (reply.ok) publish(PluginSocialProtocol.friends(reply.data))
        }
    }

    /** Publishes [list] with every open conversation read, so a message read here never counts as new. */
    private fun publish(list: List<SocialFriend>) {
        val shown = list.map { if (it.id in open && it.unread > 0) it.copy(unread = 0) else it }
        friendList.value = shown
        unreadTotal.value = shown.sumOf { it.unread }
    }

    override suspend fun setPresence(context: Context, presence: SocialPresence) {
        val reply = call("presence", PluginSocialProtocol.args(service, "presence" to presence.key), userInitiated = true)
        if (reply.ok) standing = presence
        refresh(context)
    }

    private fun threadOf(friendId: String): MutableStateFlow<List<SocialMessage>> =
        conversations.getOrPut(friendId) { MutableStateFlow(emptyList()) }

    override fun conversation(friendId: String): StateFlow<List<SocialMessage>> = threadOf(friendId)

    override suspend fun open(friendId: String) {
        open += friendId
        publish(friendList.value)
        readConversation(friendId, read = true)
    }

    private suspend fun readConversation(friendId: String, read: Boolean) {
        val reply = call("conversation", PluginSocialProtocol.args(service, "friendId" to friendId, "read" to read), userInitiated = read)
        if (!reply.ok) return
        val thread = threadOf(friendId)
        thread.value = SocialOrder.merged(thread.value, PluginSocialProtocol.messages(reply.data))
    }

    override fun close(friendId: String) {
        open -= friendId
    }

    override suspend fun send(friendId: String, text: String): Result<Unit> {
        val line = text.trim()
        if (!SocialOrder.sendable(line)) return Result.failure(IllegalArgumentException("A message can be up to ${SocialOrder.MAX_MESSAGE} characters"))
        val reply = call("send", PluginSocialProtocol.args(service, "friendId" to friendId, "text" to line), userInitiated = true)
        if (!reply.ok) return Result.failure(IllegalStateException(PluginSocialProtocol.failure(reply, label)))
        val sent = PluginSocialProtocol.message(reply.data.optJSONObject("message"), mineDefault = true)
            ?: SocialMessage("local-" + UUID.randomUUID(), mine = true, text = line, timeMs = System.currentTimeMillis())
        val thread = threadOf(friendId)
        thread.value = SocialOrder.merged(thread.value, listOf(sent))
        return Result.success(Unit)
    }

    /**
     * The plugin said something changed (`social.changed`): its account and friends are read again, an open
     * conversation it names is read again, and a message for a conversation that is not open is shown as a
     * notification when the plugin may notify ([mayNotify]).
     */
    internal suspend fun changed(change: PluginSocialProtocol.Change) {
        refresh(appContext)
        val friendId = change.friendId ?: return
        if (friendId in open) {
            readConversation(friendId, read = true)
            return
        }
        val message = change.message ?: return
        val thread = threadOf(friendId)
        thread.value = SocialOrder.merged(thread.value, listOf(message))
        if (!message.mine && mayNotify()) {
            val name = friendList.value.firstOrNull { it.id == friendId }?.name ?: label
            SocialHub.incoming(this, friendId, name, message.text)
        }
    }

    /** A plugin's message shows as a notification only with `notify.post` declared and granted (docs/plugin-api.md C19). */
    private fun mayNotify(): Boolean {
        val snapshot = PluginGrants.forContext(appContext).read(pluginId)
        return PluginGrants.stateOf(record, snapshot, "notify.post") == GrantState.GRANTED
    }

    private companion object {
        const val SURFACE = "social"
        const val BUDGET_MS = 5_000L
    }
}

/**
 * The running plugins that provide `social.provider@1`, one [PluginSocialProvider] per entry, kept across
 * [sync]s so what each one last said survives (docs/SPEC.md "Social"). Which plugins provide the point is read
 * from manifests, never by loading a plugin.
 */
object PluginSocialProviders {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val byId = ConcurrentHashMap<String, PluginSocialProvider>()

    /** Brings the providers in line with the running plugins and hands them to [SocialHub]. Reads manifests: off the main thread. */
    @Synchronized
    fun sync(context: Context): List<PluginSocialProvider> {
        val app = context.applicationContext
        val found = providersOf(app, PluginSocialProtocol.POINT).distinctBy { (record, entry) -> record.manifest.id + "/" + (entry.id ?: "default") }
        val live = found.map { (record, entry) ->
            val key = "plugin:${record.manifest.id}/${entry.id ?: "default"}"
            byId[key]?.also {
                it.record = record
                it.label = entry.label ?: record.manifest.label
            } ?: PluginSocialProvider(record, entry, app).also { byId[key] = it }
        }
        val keep = live.mapTo(HashSet()) { it.id }
        byId.keys.retainAll(keep)
        SocialHub.setPluginProviders(live)
        return live
    }

    /**
     * The broker's `social.changed` for [pluginId], on a binder thread: never blocks it. The broker has already
     * checked that the plugin declares `social.provider` and that the person has not switched it off.
     */
    fun changed(context: Context, pluginId: String, args: JSONObject): Boolean {
        val change = PluginSocialProtocol.change(args)
        scope.launch {
            val mine = byId.values.filter { it.pluginId == pluginId }.ifEmpty { sync(context).filter { it.pluginId == pluginId } }
            mine.filter { change.service == null || it.service == change.service }.forEach { provider ->
                runCatching { provider.changed(change) }
            }
        }
        return true
    }
}
