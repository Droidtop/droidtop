package dev.droidtop.app.settings

import dev.droidtop.library.HostOrder
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Host order (Droidtop/tracker#838): the hosts droidtop tries first when it picks an update's download itself, most
 * wanted first. A press moves a host to the top; a link droidtop serves without a page (a debrid account, a host's own
 * API) is always tried before any of them, whatever the order.
 */
object HostOrderCatalog {
    const val SCREEN_ID = "download_host_order"

    /** Bumped after a change, so the list is drawn again in its new order. */
    private val changed = MutableStateFlow(0)

    fun screen() = CatalogScreen(
        id = SCREEN_ID,
        title = "Host order",
        subtitle = "Tried first when droidtop picks a download. Debrid always comes first",
        groups = { context ->
            val order = withContext(Dispatchers.IO) { HostOrder.get(context) }
            listOf(
                CatalogGroup(
                    id = "host_order_list",
                    title = null,
                    items = order.mapIndexed { index, host ->
                        ActionItem(
                            id = "host_order_$host",
                            title = host.replaceFirstChar { it.uppercase() },
                            value = "${index + 1}",
                            subtitle = if (index == 0) "Tried first" else "Press to try it first",
                            run = { ctx ->
                                if (index > 0) {
                                    HostOrder.set(ctx, listOf(host) + order.filterNot { it == host })
                                    changed.value++
                                }
                            },
                        )
                    },
                ),
                CatalogGroup(
                    id = "host_order_reset",
                    title = null,
                    items = listOf(
                        ActionItem(
                            id = "host_order_default",
                            title = "Use the default order",
                            subtitle = "Hosts that give the file straight away first",
                            run = { ctx ->
                                HostOrder.set(ctx, HostOrder.DEFAULT)
                                changed.value++
                            },
                        ),
                    ),
                ),
            )
        },
        live = changed,
    )
}
