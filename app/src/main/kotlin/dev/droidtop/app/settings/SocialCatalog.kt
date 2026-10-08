package dev.droidtop.app.settings

import android.content.Context
import android.text.format.DateUtils
import dev.droidtop.app.StoreSignInActivity
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.library.social.AppMessages
import dev.droidtop.library.social.AppMessagesModel
import dev.droidtop.library.social.AppMessagesPrefs
import dev.droidtop.library.social.SocialContact
import dev.droidtop.library.social.SocialHub
import dev.droidtop.library.social.SocialLink
import dev.droidtop.library.social.SocialMessage
import dev.droidtop.library.social.SocialOrder
import dev.droidtop.library.social.SocialPresence
import dev.droidtop.library.social.SocialProvider
import dev.droidtop.library.stores.StoreLibraries
import dev.droidtop.library.userFacingErrorMessage
import dev.droidtop.runtime.systemstatus.NotificationsStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Social place (docs/SPEC.md "Social" and 7j "Places", Droidtop/tracker#327): every provider's
 * friends in one list, the conversations, and each account's standing. Steam and every plugin that
 * provides `social.provider@1` are drawn the same way, by droidtop, from [SocialHub]. A catalog screen
 * like the other places, so pad and touch work the way they do everywhere and a message is typed in the
 * shell's one text dialog (the soft keyboard on a touch screen, its Done key sends). The list and each
 * conversation are `live` screens: the renderer reads them again when a friend changes or a message
 * arrives, nothing polls.
 *
 * Labels and values, no sentences: a friend's row says their name and, in the value column, "3 new",
 * the game they play, or how they are, with the service named when more than one is on.
 */
internal object SocialCatalog {
    const val SCREEN_ID = "social"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * When the plugin providers were last asked: once per visit to the place, and again after [ASK_AGAIN_MS] at
     * most, never on every rebuild a change causes (a reply is itself a change).
     */
    private val askedAtMs = AtomicLong(0L)
    private const val ASK_AGAIN_MS = 30_000L

    fun screen(): CatalogScreen = CatalogScreen(
        id = SCREEN_ID,
        title = "Social",
        subtitle = "Friends and conversations from every service",
        groups = { context -> rootGroups(context) },
        indexGroups = { emptyList() },
        onLeave = { askedAtMs.set(0L) },
        live = flow { emitAll(SocialHub.changes()) },
    )

    private suspend fun rootGroups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val last = askedAtMs.get()
        if (now - last >= ASK_AGAIN_MS && askedAtMs.compareAndSet(last, now)) SocialHub.refresh(context)
        val available = SocialHub.available(context)
        val shown = available.filter { it.presence(context) != SocialPresence.OFFLINE }
        val contacts = SocialOrder.contacts(shown)
        val badged = shown.size > 1
        buildList {
            val conversations = SocialOrder.conversations(contacts)
            if (conversations.isNotEmpty()) {
                add(
                    CatalogGroup(
                        id = "social_conversations",
                        title = "Conversations",
                        items = conversations.map { contactRow(it, "social_conv_", badged) },
                    ),
                )
            }
            val friends = SocialOrder.friends(contacts)
            if (friends.isNotEmpty()) {
                add(CatalogGroup(id = "social_friends", title = "Friends", items = friends.map { contactRow(it, "social_friend_", badged) }))
            }
            add(CatalogGroup(id = "social_accounts", title = "Accounts", items = accountRows(context, available)))
        }
    }

    private fun contactRow(contact: SocialContact, prefix: String, badged: Boolean): CatalogItem = NestedScreenItem(
        id = prefix + contact.key,
        title = contact.friend.name,
        valueLabel = { SocialOrder.value(contact, badged) },
        inline = chat(contact.provider, contact.friend.id, contact.friend.name),
    )

    /** Each provider's standing and connection; a store with friends that is not signed in offers its sign-in. */
    private fun accountRows(context: Context, available: List<SocialProvider>): List<CatalogItem> {
        val rows = ArrayList<CatalogItem>()
        for (provider in available) {
            val presence = provider.presence(context)
            if (presence != null) {
                rows += ChoiceItem(
                    id = "social_status_${provider.id}",
                    title = provider.label,
                    options = SocialPresence.entries.map { ChoiceOption(it.key, it.label) },
                    current = presence.key,
                    onSelect = { ctx, value -> scope.launch { provider.setPresence(ctx, SocialPresence.from(value)) } },
                )
            }
            if (provider === AppMessages) {
                rows += NestedScreenItem(id = "social_messages_apps", title = "Messages from apps", inline = appsScreen())
            }
            val link = provider.link.value
            // Anything but a working connection says so, and pressing the row asks again.
            if (presence != SocialPresence.OFFLINE && link != SocialLink.ONLINE) {
                rows += ActionItem(
                    id = "social_link_${provider.id}",
                    title = if (presence == null) provider.label else "Connection",
                    value = link.label,
                    run = { ctx ->
                        scope.launch {
                            if (presence != null) provider.setPresence(ctx, presence) else provider.refresh(ctx)
                        }
                    },
                )
            }
        }
        if (!NotificationsStore.isGranted(context)) {
            rows += ActionItem(
                id = "social_allow_notifications",
                title = "Allow notification access",
                icon = CatalogIcon.ANDROID_SETTINGS,
                run = { ctx -> NotificationsStore.openGrantScreen(ctx) },
            )
        }
        val signedOut = StoreLibraries.all().filter { store -> store.social != null && store.social !in available }
        for (store in signedOut) {
            rows += ActionItem(
                id = "social_sign_in_${store.id}",
                title = "Sign in to ${store.label}",
                icon = CatalogIcon.GLOBAL,
                run = { ctx -> ctx.startActivity(StoreSignInActivity.intent(ctx, store.id)) },
            )
        }
        return rows
    }

    /** The apps that have posted a conversation, and the known messaging apps, each with its switch. */
    private fun appsScreen(): CatalogScreen = CatalogScreen(
        id = "social_messages_apps",
        title = "Messages from apps",
        groups = { context ->
            withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val rows = AppMessagesPrefs.seen(context).toMutableMap()
                for (known in AppMessagesModel.KNOWN_APPS) {
                    if (known !in rows && runCatching { pm.getApplicationInfo(known, 0) }.isSuccess) rows[known] = false
                }
                val items = rows.map { (pkg, messagingStyle) ->
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    label to ToggleItem(
                        id = "social_app_$pkg",
                        title = label,
                        current = AppMessagesPrefs.chosen(context, pkg) ?: AppMessagesModel.defaultIncluded(pkg, messagingStyle),
                        onToggle = { ctx, on -> withContext(Dispatchers.IO) { AppMessages.choose(ctx, pkg, on) } },
                    )
                }.sortedBy { it.first.lowercase() }.map { it.second }
                listOf(
                    CatalogGroup(
                        id = "social_messages_apps_list",
                        title = null,
                        items = items.ifEmpty { listOf(ActionItem(id = "social_apps_empty", title = "No apps yet", run = {})) },
                    ),
                )
            }
        },
        indexGroups = { emptyList() },
    )

    /** One conversation: the typing row, then the messages newest first. */
    private fun chat(provider: SocialProvider, friendId: String, friendName: String): CatalogScreen {
        val opened = AtomicBoolean(false)
        // A send that failed says why on its own row until the next one goes through.
        var failure: String? = null
        return CatalogScreen(
            id = "social_chat_${provider.id}/$friendId",
            title = friendName,
            groups = { context ->
                withContext(Dispatchers.IO) {
                    // Reading the history and marking the conversation read happen once per visit.
                    if (opened.compareAndSet(false, true)) provider.open(friendId)
                    val messages = provider.conversation(friendId).value
                    listOf(
                        CatalogGroup(
                            id = "chat_send_$friendId",
                            title = null,
                            items = buildList {
                                // Another app's conversation can be one that only its own app answers.
                                if (provider.canSend(friendId)) {
                                    add(
                                        TextInputItem(
                                            id = "chat_send",
                                            title = "Message",
                                            value = "",
                                            onChange = { _, text ->
                                                if (text.isNotBlank()) {
                                                    failure = withContext(Dispatchers.IO) { provider.send(friendId, text) }
                                                        .exceptionOrNull()?.let(::userFacingErrorMessage)
                                                }
                                            },
                                        ),
                                    )
                                }
                                provider.sourceOf(friendId)?.let { source ->
                                    add(
                                        ActionItem(
                                            id = "chat_open_source",
                                            title = "Open in ${source.label}",
                                            run = { ctx -> scope.launch { provider.openInSource(ctx, friendId) } },
                                        ),
                                    )
                                }
                                failure?.let { add(ActionItem(id = "chat_failed", title = "Not sent", value = it, run = {})) }
                            },
                        ),
                        CatalogGroup(
                            id = "chat_messages_$friendId",
                            title = null,
                            items = if (messages.isEmpty()) {
                                listOf(ActionItem(id = "chat_empty", title = "No messages yet", run = {}))
                            } else {
                                messages.asReversed().map { message -> messageRow(context, message, friendName) }
                            },
                        ),
                    )
                }
            },
            indexGroups = { emptyList() },
            onLeave = {
                opened.set(false)
                failure = null
                provider.close(friendId)
            },
            live = provider.conversation(friendId).map { it },
        )
    }

    private fun messageRow(context: Context, message: SocialMessage, friendName: String): CatalogItem = ActionItem(
        id = "chat_msg_${message.key}",
        title = message.text,
        value = (if (message.mine) "You" else friendName) + " · " + SocialTime.text(context, message.timeMs),
        run = {},
    )
}

/** When a message was sent, as the Social place and the companion both show it. */
internal object SocialTime {
    /** The time of day for today's messages, the date and time for older ones. */
    fun text(context: Context, timeMs: Long): String {
        val flags = if (DateUtils.isToday(timeMs)) {
            DateUtils.FORMAT_SHOW_TIME
        } else {
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        }
        return DateUtils.formatDateTime(context, timeMs, flags)
    }
}
