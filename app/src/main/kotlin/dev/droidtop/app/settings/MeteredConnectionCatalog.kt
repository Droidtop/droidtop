package dev.droidtop.app.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.droidtop.app.WifiNamePermissionActivity
import dev.droidtop.library.integrations.PluginJobsScreen
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.Notices
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.net.MeteredGrants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/**
 * What a download held for Wi-Fi offers (docs/SPEC.md 12a, "A metered connection is allowed, never a job"): the
 * person allows the connection, never one download. The owner, 2026-10-23: "I don't want the user to be able to enable
 * downloads for a single game, because that can be deceptive". Every choice here lets every download use the
 * connection, and Android's own metered switch stays the person's: droidtop opens it and never changes it ("The last
 * thing we'll fuck around with is something that can cost the user money").
 */
object MeteredConnectionCatalog {
    const val SCREEN_ID = PluginJobsScreen.METERED_SCREEN_ID

    fun screen() = CatalogScreen(
        id = SCREEN_ID,
        title = "Downloads on this connection",
        subtitle = "This connection is metered: it may cost you money or use up a data allowance",
        groups = { context -> groups(context) },
        live = combine(MeteredGrants.once, MeteredGrants.always) { once, always -> once to always },
    )

    private suspend fun groups(context: Context): List<CatalogGroup> {
        val connection = withContext(Dispatchers.IO) {
            MeteredGrants.load(context)
            MeteredGrants.connectionNow(context)
        } ?: return listOf(CatalogGroup(id = "metered_none", title = "", items = listOf(TextBlockItem(id = "metered_none_text", text = "There is no connection right now. Downloads wait for one."))))
        val allowed = MeteredGrants.allows(connection)
        val items = buildList {
            add(
                TextBlockItem(
                    id = "metered_explain",
                    text = if (allowed) {
                        "Downloads may use ${connection.label}. Every download uses it, not only one game's."
                    } else {
                        "Downloads are waiting because ${connection.label} is metered. Whatever you choose applies to every download on it, never to one game."
                    },
                ),
            )
            if (!allowed) {
                add(
                    ActionItem(
                        id = "metered_once",
                        title = "Allow downloads until you disconnect",
                        subtitle = "Every waiting download starts now. When you disconnect, downloads wait again",
                        run = { MeteredGrants.allowOnce(connection) },
                    ),
                )
            }
            when (connection.kind) {
                MeteredGrants.Kind.WIFI -> {
                    // Android's own switch is the recommended way: it is per network, and it is the person's.
                    add(
                        ActionItem(
                            id = "metered_wifi_settings",
                            title = "This network is not metered",
                            subtitle = "Opens Android's settings for this Wi-Fi to set it not metered. droidtop never changes it",
                            run = { ctx -> ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                        ),
                    )
                    if (!allowed) add(alwaysOnWifi(context, connection))
                }
                MeteredGrants.Kind.MOBILE -> if (!allowed && connection.key != null) {
                    add(
                        ActionItem(
                            id = "metered_always_sim",
                            title = "Always allow downloads on ${connection.label}",
                            subtitle = "Every download uses this SIM's data from now on. You can take it back in Download rules",
                            confirmTitle = "Let every download use ${connection.label} from now on? This may cost you money.",
                            run = { ctx -> MeteredGrants.allowAlways(ctx, connection) },
                        ),
                    )
                }
                MeteredGrants.Kind.OTHER -> Unit
            }
        }
        return listOf(CatalogGroup(id = "metered_choices", title = connection.label, items = items))
    }

    /**
     * Always on a Wi-Fi network needs its name, and Android tells an app the name only with the location permission. The
     * owner, 2026-10-23: "make it explicit that it is only for overriding bandwidth usage on a metered connection, and
     * we explicitly DO NOT recommend they do that". Last in the list, and never the focused row.
     */
    private fun alwaysOnWifi(context: Context, connection: MeteredGrants.Connection): ActionItem =
        if (connection.key != null) {
            ActionItem(
                id = "metered_always_wifi",
                title = "Always allow downloads on ${connection.label}",
                subtitle = "Every download uses this Wi-Fi from now on. You can take it back in Download rules",
                confirmTitle = "Let every download use ${connection.label} from now on? It is metered: this may cost you money.",
                run = { ctx -> MeteredGrants.allowAlways(ctx, connection) },
            )
        } else {
            ActionItem(
                id = "metered_always_wifi_permission",
                title = "Always allow on this Wi-Fi",
                subtitle = "Needs location, only to know this Wi-Fi. Not recommended: use \"not metered\" above",
                confirmTitle = "Ask Android for the location permission, only to remember this Wi-Fi for downloads? Not recommended.",
                run = { ctx ->
                    if (MeteredGrants.canNameWifi(ctx)) {
                        Notices.flash("Android did not say which Wi-Fi this is. Location may be switched off", context = ctx)
                    } else {
                        ctx.startActivity(Intent(ctx, WifiNamePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
            )
        }

    /** The always grants, for Download rules to list and take back. */
    fun alwaysGroup(): CatalogGroup? {
        val always = MeteredGrants.always.value.takeIf { it.isNotEmpty() } ?: return null
        return CatalogGroup(
            id = "download_rules_metered_always",
            title = "Metered connections you allowed",
            items = always.map { (key, label) ->
                ActionItem(
                    id = "download_rules_metered_$key",
                    title = label,
                    subtitle = "Every download may use it. Select to take this back",
                    confirmTitle = "Stop letting downloads use $label?",
                    run = { ctx -> MeteredGrants.remove(ctx, key) },
                )
            },
        )
    }
}
