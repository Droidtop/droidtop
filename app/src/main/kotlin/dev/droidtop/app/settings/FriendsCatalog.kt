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
import dev.droidtop.library.stores.SocialFriend
import dev.droidtop.library.stores.SocialLink
import dev.droidtop.library.stores.SocialMessage
import dev.droidtop.library.stores.SocialOrder
import dev.droidtop.library.stores.SocialPresence
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.stores.StoreSocial
import dev.droidtop.library.stores.StoreSocials
import dev.droidtop.library.userFacingErrorMessage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Friends place (docs/SPEC.md 7g "Stores" and 7j "Places", Droidtop/tracker#313):
 * who is online on the stores that have friends, and the 1:1 conversations
 * with them. A catalog screen like the other places, so pad and touch work
 * the way they do everywhere and a message is typed in the shell's one text
 * dialog (the soft keyboard on a touch screen, its Done key sends). The
 * list and each conversation are `live` screens: the renderer reads them
 * again when a friend changes or a message arrives, nothing polls.
 *
 * Labels and values, no sentences: a friend's row says their name and, in the
 * value column, "3 new", the game they play, or how they are. A conversation
 * has the typing row first and the newest message under it.
 */
internal object FriendsCatalog {
    const val SCREEN_ID = "friends"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun screen(): CatalogScreen = CatalogScreen(
        id = SCREEN_ID,
        title = "Friends",
        subtitle = "Who is online and your conversations",
        groups = { context -> rootGroups(context) },
        indexGroups = { emptyList() },
        live = flow { emitAll(changes()) },
    )

    /** Fires when any store's friends or connection changed. */
    private fun changes(): Flow<Any?> {
        val flows = StoreSocials.all().flatMap { (_, social) -> listOf<Flow<Any?>>(social.friends, social.link) }
        return if (flows.isEmpty()) MutableSharedFlow() else combine(flows) { it.toList() }
    }

    private suspend fun rootGroups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val signedIn = StoreSocials.all().filter { (store, _) -> store.signedIn(context) }
        if (signedIn.isEmpty()) {
            return@withContext listOf(
                CatalogGroup(
                    id = "friends_sign_in",
                    title = null,
                    items = StoreSocials.all().map { (store, _) ->
                        ActionItem(
                            id = "friends_sign_in_${store.id}",
                            title = "Sign in to ${store.label}",
                            value = "Not signed in",
                            icon = CatalogIcon.GLOBAL,
                            run = { ctx -> ctx.startActivity(StoreSignInActivity.intent(ctx, store.id)) },
                        )
                    }.ifEmpty { listOf(ActionItem(id = "friends_none", title = "No store with friends", run = {})) },
                ),
            )
        }
        signedIn.flatMap { (store, social) -> storeGroups(context, store, social, headed = signedIn.size > 1) }
    }

    private fun storeGroups(context: Context, store: StoreLibrary, social: StoreSocial, headed: Boolean): List<CatalogGroup> {
        val presence = social.presence(context)
        val friends = social.friends.value
        val link = social.link.value
        val status = buildList<CatalogItem> {
            add(
                ChoiceItem(
                    id = "friends_status_${store.id}",
                    title = "Status",
                    options = SocialPresence.entries.map { ChoiceOption(it.key, it.label) },
                    current = presence.key,
                    onSelect = { ctx, value -> scope.launch { social.setPresence(ctx, SocialPresence.from(value)) } },
                ),
            )
            // Anything but a working connection says so, and pressing the row asks again.
            if (presence != SocialPresence.OFFLINE && link != SocialLink.ONLINE) {
                add(
                    ActionItem(
                        id = "friends_link_${store.id}",
                        title = "Connection",
                        value = link.label,
                        run = { ctx -> scope.launch { social.setPresence(ctx, presence) } },
                    ),
                )
            }
        }
        val list = if (presence == SocialPresence.OFFLINE) {
            listOf(ActionItem(id = "friends_offline_${store.id}", title = "Offline", value = "Choose Online to see friends", run = {}))
        } else if (friends.isEmpty()) {
            listOf(ActionItem(id = "friends_empty_${store.id}", title = "No friends yet", value = link.label.takeIf { link != SocialLink.ONLINE }, run = {}))
        } else {
            friends.map { friend ->
                NestedScreenItem(
                    id = "friend_${store.id}_${friend.id}",
                    title = friend.name,
                    valueLabel = { SocialOrder.value(friend) },
                    inline = chat(store, social, friend),
                )
            }
        }
        return listOf(
            CatalogGroup(id = "friends_status_group_${store.id}", title = store.label.takeIf { headed }, items = status),
            CatalogGroup(id = "friends_list_${store.id}", title = "Friends".takeIf { friends.isNotEmpty() && presence != SocialPresence.OFFLINE }, items = list),
        )
    }

    /** One conversation: the typing row, then the messages newest first. */
    private fun chat(store: StoreLibrary, social: StoreSocial, friend: SocialFriend): CatalogScreen {
        val opened = AtomicBoolean(false)
        // A send that failed says why on its own row until the next one goes through.
        var failure: String? = null
        return CatalogScreen(
            id = "friend_chat_${store.id}_${friend.id}",
            title = friend.name,
            groups = { context ->
                withContext(Dispatchers.IO) {
                    // Reading the history and marking the conversation read happen once per visit.
                    if (opened.compareAndSet(false, true)) social.open(friend.id)
                    val messages = social.conversation(friend.id).value
                    listOf(
                        CatalogGroup(
                            id = "chat_send_${friend.id}",
                            title = null,
                            items = buildList {
                                add(
                                    TextInputItem(
                                        id = "chat_send",
                                        title = "Message",
                                        value = "",
                                        onChange = { _, text ->
                                            if (text.isNotBlank()) {
                                                failure = withContext(Dispatchers.IO) { social.send(friend.id, text) }
                                                    .exceptionOrNull()?.let(::userFacingErrorMessage)
                                            }
                                        },
                                    ),
                                )
                                failure?.let { add(ActionItem(id = "chat_failed", title = "Not sent", value = it, run = {})) }
                            },
                        ),
                        CatalogGroup(
                            id = "chat_messages_${friend.id}",
                            title = null,
                            items = if (messages.isEmpty()) {
                                listOf(ActionItem(id = "chat_empty", title = "No messages yet", run = {}))
                            } else {
                                messages.asReversed().map { message -> messageRow(context, message, friend.name) }
                            },
                        ),
                    )
                }
            },
            indexGroups = { emptyList() },
            onLeave = {
                opened.set(false)
                failure = null
                social.close(friend.id)
            },
            live = social.conversation(friend.id).map { it },
        )
    }

    private fun messageRow(context: Context, message: SocialMessage, friendName: String): CatalogItem = ActionItem(
        id = "chat_msg_${message.key}",
        title = message.text,
        value = (if (message.mine) "You" else friendName) + " · " + whenText(context, message.timeMs),
        run = {},
    )

    /** The time of day for today's messages, the date and time for older ones. */
    private fun whenText(context: Context, timeMs: Long): String {
        val flags = if (DateUtils.isToday(timeMs)) {
            DateUtils.FORMAT_SHOW_TIME
        } else {
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        }
        return DateUtils.formatDateTime(context, timeMs, flags)
    }
}
