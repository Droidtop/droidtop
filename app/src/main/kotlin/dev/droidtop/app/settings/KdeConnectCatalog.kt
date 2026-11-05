package dev.droidtop.app.settings

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.net.kdeconnect.KdcDevice
import dev.droidtop.net.kdeconnect.KdeConnect
import dev.droidtop.net.peer.AgentNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings > Accounts and sources > KDE Connect (docs/SPEC.md 7q "KDE Connect", Droidtop/tracker#645): droidtop as a
 * KDE Connect device. The switch, the name the others see, devices added by address, and the devices: a request
 * to pair with its code, Accept and Refuse; the paired ones with Ping and Unpair; the others with Pair. A catalog
 * screen, so both Settings surfaces draw it, and live: it is built again when a device comes, goes or asks.
 */
object KdeConnectCatalog {
    const val SCREEN_ID = "kdeconnect"

    fun screen() = CatalogScreen(
        id = SCREEN_ID,
        title = "KDE Connect",
        subtitle = "Pair phones and computers running KDE Connect, or Pythia",
        groups = { context ->
            // Opening the screen is how find my device's sound is stopped.
            dev.droidtop.app.KdeConnectNotification.stopRinging(context)
            groups(context)
        },
        live = KdeConnect.changes,
        needsNetwork = true,
    )

    fun linkRow(on: Boolean) = NestedScreenItem(
        id = "accounts_kdeconnect_link",
        title = "KDE Connect",
        subtitle = "Phones and computers with the KDE Connect app, or Pythia",
        registryId = SCREEN_ID,
        valueLabel = { if (on) "on" else "off" },
        icon = CatalogIcon.DESKTOP,
    )

    private suspend fun groups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val on = KdeConnect.isOn(context)
        val devices = KdeConnect.devices()
        if (devices.any { KdeConnect.viaPlugin(it.address) != null }) {
            pluginNames = dev.droidtop.pluginhost.PluginStore.installed(context).associate { it.manifest.id to it.manifest.label }
        }
        buildList {
            add(CatalogGroup(id = "kdeconnect_switch", title = null, items = switchRows(context, on)))
            if (!on || !KdeConnect.running.value) return@buildList
            val asking = devices.filter { it.pairing == "requested_by_peer" }
            if (asking.isNotEmpty()) {
                add(CatalogGroup(id = "kdeconnect_asking", title = "Asking to pair", items = asking.flatMap { requestRows(it) }))
            }
            val paired = devices.filter { it.paired }
            if (paired.isNotEmpty()) {
                add(CatalogGroup(id = "kdeconnect_paired", title = "Paired", items = paired.flatMap { pairedRows(it) } + clipboardRow()))
            }
            val others = devices.filter { !it.paired && it.reachable && it.pairing != "requested_by_peer" }
            add(
                CatalogGroup(
                    id = "kdeconnect_available",
                    title = "Available",
                    items = others.map { availableRow(it) }.ifEmpty {
                        listOf(
                            ActionItem(
                                id = "kdeconnect_none",
                                title = "No other devices found yet",
                                subtitle = "Open KDE Connect on the phone or computer, on the same network",
                            ),
                        )
                    } + refreshRow(),
                ),
            )
            add(CatalogGroup(id = "kdeconnect_plugins", title = "What paired devices can do", items = pluginRows(context)))
        }
    }

    /** One switch per plugin (docs/SPEC.md 7q), and whether this device's own notifications go out. */
    private fun pluginRows(context: Context): List<CatalogItem> = buildList {
        PLUGIN_WORDS.forEach { (name, words) ->
            add(
                ToggleItem(
                    id = "kdeconnect_plugin_$name",
                    title = words.first,
                    subtitle = words.second,
                    current = KdeConnect.pluginOn(context, name),
                    onToggle = { ctx, value -> KdeConnect.setPluginOn(ctx, name, value) },
                ),
            )
        }
        if (!KdeConnect.pluginOn(context, "notifications")) return@buildList
        if (dev.droidtop.runtime.systemstatus.NotificationsStore.isGranted(context)) {
            add(
                ToggleItem(
                    id = "kdeconnect_send_notifications",
                    title = "Send this device's notifications",
                    subtitle = "To paired devices. Never ongoing or secret ones",
                    current = KdeConnect.sendsNotifications(context),
                    onToggle = { ctx, value -> KdeConnect.setSendsNotifications(ctx, value) },
                ),
            )
        } else {
            add(
                ActionItem(
                    id = "kdeconnect_notification_access",
                    title = "Send this device's notifications",
                    subtitle = "Needs notification access. Press to allow it",
                    run = { ctx -> dev.droidtop.runtime.systemstatus.NotificationsStore.openGrantScreen(ctx) },
                ),
            )
        }
    }

    /** Each plugin's title and subtitle, in KdeConnect.PLUGINS's order. */
    private val PLUGIN_WORDS = listOf(
        "ping" to ("Ping" to "Send pings and show the ones that come"),
        "share" to ("Share" to "Files, links and text, both ways"),
        "clipboard" to ("Clipboard" to "Copied text goes to paired devices and comes back"),
        "input" to ("Typing from other devices" to "Paired devices may type into this one"),
        "battery" to ("Battery" to "Each side shows the other's charge"),
        "findmyphone" to ("Find my device" to "Paired devices may ring this one"),
        "notifications" to ("Notifications" to "Show paired devices' notifications here"),
    )

    private fun switchRows(context: Context, on: Boolean): List<CatalogItem> = buildList {
        if (!AgentNative.available) add(ActionItem(id = "kdeconnect_missing", title = "KDE Connect isn't in this build"))
        add(
            ToggleItem(
                id = "kdeconnect_on",
                title = "KDE Connect",
                subtitle = KdeConnect.lastError?.takeIf { on && !KdeConnect.running.value }
                    ?: "Devices on this network can find this one and ask to pair",
                current = on,
                onToggle = { ctx, value -> KdeConnect.setOn(ctx, value) },
            ),
        )
        add(
            TextInputItem(
                id = "kdeconnect_name",
                title = "Name",
                subtitle = "What other devices show for this one",
                value = KdeConnect.name(context),
                onChange = { ctx, text -> KdeConnect.setName(ctx, text) },
            ),
        )
        add(
            TextInputItem(
                id = "kdeconnect_addresses",
                title = "Devices by address",
                subtitle = "IP addresses of devices this network does not find, separated by commas",
                value = KdeConnect.addresses(context).joinToString(", "),
                onChange = { ctx, text -> KdeConnect.setAddresses(ctx, text) },
            ),
        )
    }

    /** Installed plugins' names by id, read once per build of the screen (on its IO thread). */
    @Volatile
    private var pluginNames: Map<String, String> = emptyMap()

    /** "Phone, 192.168.1.9", or with a VPN plugin's address "Phone, over Tailscale" (docs/SPEC.md 7q, Tailscale). */
    private fun place(d: KdcDevice): String {
        val via = KdeConnect.viaPlugin(d.address)?.let { id -> "over " + (pluginNames[id] ?: "your VPN") }
        return listOfNotNull(d.type.replaceFirstChar { it.uppercase() }, via ?: d.address).joinToString(", ")
    }

    private fun requestRows(d: KdcDevice): List<CatalogItem> = listOf(
        AsyncActionItem(
            id = "kdeconnect_accept_${d.id}",
            title = "Accept ${d.name}",
            subtitle = "Pair only if ${d.name} shows the same code: ${d.verificationKey ?: "…"}",
            run = { _, _ -> KdeConnect.accept(d.id) ?: "Paired with ${d.name}" },
        ),
        AsyncActionItem(
            id = "kdeconnect_refuse_${d.id}",
            title = "Refuse ${d.name}",
            run = { _, _ -> KdeConnect.refuse(d.id) ?: "Refused" },
        ),
    )

    private fun pairedRows(d: KdcDevice): List<CatalogItem> = listOf(
        AsyncActionItem(
            id = "kdeconnect_paired_${d.id}",
            title = d.name,
            subtitle = if (d.reachable) "Connected. ${place(d)}. Press to ping it" else "Not reachable now",
            run = { _, _ -> if (d.reachable) KdeConnect.ping(d.id) ?: "Ping sent" else "${d.name} is not reachable now" },
        ),
    ) + (if (d.reachable) sendRows(d) else emptyList()) + listOf(
        AsyncActionItem(
            id = "kdeconnect_unpair_${d.id}",
            title = "Unpair ${d.name}",
            confirmTitle = "Unpair ${d.name}? It has to be paired again before it can reach this device.",
            confirmKeep = "Keep it paired",
            run = { _, _ -> KdeConnect.unpair(d.id) ?: "Unpaired ${d.name}" },
        ),
    )

    /** Send a file (the system's document picker; the file is read where it is, never copied) and send a text. */
    private fun sendRows(d: KdcDevice): List<CatalogItem> = listOf(
        ActionItem(
            id = "kdeconnect_touchpad_${d.id}",
            title = "Control ${d.name}",
            subtitle = "This screen and the sticks move its pointer and click",
            run = { ctx ->
                ctx.startActivity(dev.droidtop.app.KdeConnectTouchpadActivity.intent(ctx, d.id, d.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        ),
        AsyncActionItem(
            id = "kdeconnect_ring_${d.id}",
            title = "Ring ${d.name}",
            subtitle = "Plays a sound there until it is stopped, to find it",
            run = { _, _ -> KdeConnect.ring(d.id) ?: "Ringing ${d.name}" },
        ),
        DocumentPickItem(
            id = "kdeconnect_send_file_${d.id}",
            title = "Send a file to ${d.name}",
            mimeType = "*/*",
            onPicked = { ctx, uri -> KdeConnect.shareFile(ctx, d.id, uri) ?: "Sending to ${d.name}" },
        ),
        TextInputItem(
            id = "kdeconnect_type_${d.id}",
            title = "Type on ${d.name}",
            subtitle = "Typed into the field in focus there, then Enter",
            value = "",
            onChange = { _, text -> if (text.isNotEmpty()) KdeConnect.type(d.id, text, enter = true) },
        ),
        TextInputItem(
            id = "kdeconnect_send_text_${d.id}",
            title = "Send text to ${d.name}",
            value = "",
            onChange = { _, text -> if (text.isNotBlank()) KdeConnect.shareText(d.id, text) },
        ),
    )

    /** Sends this device's clipboard to the paired devices now, for when it changed while droidtop was not in front. */
    private fun clipboardRow() = AsyncActionItem(
        id = "kdeconnect_send_clipboard",
        title = "Send clipboard",
        subtitle = "This device's clipboard to the paired devices. Never what is marked sensitive",
        run = { ctx, _ -> dev.droidtop.app.KdeConnectNotification.sendClipboard(ctx) },
    )

    private fun availableRow(d: KdcDevice) = AsyncActionItem(
        id = "kdeconnect_pair_${d.id}",
        title = d.name,
        subtitle = if (d.pairing == "requested") {
            "Asked to pair. Check ${d.name} shows ${d.verificationKey ?: "the same code"}, and accept there"
        } else {
            "Pair. ${place(d)}"
        },
        run = { _, _ -> KdeConnect.pair(d.id) ?: "Asked ${d.name} to pair" },
    )

    private fun refreshRow() = AsyncActionItem(
        id = "kdeconnect_refresh",
        title = "Look again",
        subtitle = "Tells the devices on this network that this one is here",
        run = { _, _ -> KdeConnect.refresh() ?: "Asked again" },
    )
}
