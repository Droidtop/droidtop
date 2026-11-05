package dev.droidtop.stores.steam

import android.content.Context
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.library.social.SocialPresence
import dev.droidtop.stores.util.CloudSaveSetting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The rows Steam adds to its page in the Stores place (docs/SPEC.md 7g,
 * "Stores", Droidtop/tracker#313): the person's standing (Online, Invisible,
 * or Offline, which closes the connection), cloud saves, and whether a
 * message notifies. The explanation of each is its tooltip, not a line of text.
 */
internal object SteamSettings {
    const val ID_STATUS = "steam_status"
    const val ID_NOTIFY = "steam_notify_messages"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun items(context: Context): List<CatalogItem> = listOf(
        ChoiceItem(
            id = ID_STATUS,
            title = "Status",
            subtitle = "Online and Invisible keep Steam connected for friends and chat; Offline disconnects",
            options = SocialPresence.entries.map { ChoiceOption(it.key, it.label) },
            current = SteamPrefs.presence(context).key,
            onSelect = { ctx, value -> scope.launch { SteamFriendsHub.setPresence(ctx, SocialPresence.from(value)) } },
        ),
        CloudSaveSetting.item(context, "steam", "Steam Cloud"),
        ToggleItem(
            id = ID_NOTIFY,
            title = "Message notifications",
            subtitle = "A notification for a new Steam message",
            current = SteamPrefs.notifyMessages(context),
            onToggle = { ctx, on -> withContext(Dispatchers.IO) { SteamPrefs.setNotifyMessages(ctx, on) } },
        ),
    )
}
