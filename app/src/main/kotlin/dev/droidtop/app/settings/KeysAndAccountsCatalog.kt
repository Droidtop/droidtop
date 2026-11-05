package dev.droidtop.app.settings

import android.content.Context
import dev.droidtop.library.credentials.CredentialServices
import dev.droidtop.library.credentials.CredentialStore
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.net.GitHubTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Keys and accounts" (Droidtop/tracker#12; the owner, 2026-10-28: "Add one 'Keys and accounts' page under Settings >
 * Accounts and sources. It lists every key and token droidtop holds, where each is used, and a Remove. Each service's
 * own page keeps its row."). The page lists and stores nothing of its own: each row reads the place that already holds
 * the key, and Remove goes through the same clear that service's own page uses.
 * - The GitHub sign-in: [GitHubTokenStore], cleared as the GitHub page's Sign out does.
 * - Each signed-in game store (an itch.io API key, a Steam, GOG, Epic or Amazon sign-in, a plugin's store):
 *   [PcStore.signOut], the store page's own Sign out.
 * - The scraper, achievement and download-service keys in [CredentialStore], by [CredentialServices]: a blank write
 *   removes each, which is what clearing the field on the service's own page does.
 * - Plugin signing keys: the "Keys you trust" page, which keeps its own review and remove rules.
 */
internal object KeysAndAccountsCatalog {
    const val SCREEN_ID = "keys_and_accounts"

    /** The row on Accounts and sources that opens the page. */
    fun linkRow(): NestedScreenItem = NestedScreenItem(
        id = "accounts_keys",
        title = "Keys and accounts",
        subtitle = "Every key and sign-in droidtop holds, where it is used, and Remove",
        inline = screen(),
    )

    fun screen() = CatalogScreen(
        id = SCREEN_ID,
        title = "Keys and accounts",
        subtitle = "Each stays on this device. Remove here is the same as on its own page",
        groups = { context -> groups(context) },
    )

    private suspend fun groups(context: Context): List<CatalogGroup> {
        val (github, stores, services) = withContext(Dispatchers.IO) {
            Triple(
                GitHubTokenStore.isSet(context),
                PcStore.entries.filter { it.signedIn(context) },
                CredentialServices.held { CredentialStore.get(context, it) },
            )
        }
        val signIns = buildList<CatalogItem> {
            if (github) {
                add(
                    AsyncActionItem(
                        id = "keys_github",
                        title = "GitHub",
                        subtitle = "Plugin repositories, private ones included, and catalog updates",
                        value = "Signed in",
                        confirmTitle = "Sign out of GitHub on this device?",
                        run = { ctx, _ ->
                            withContext(Dispatchers.IO) { GitHubTokenStore.clear(ctx) }
                            "Signed out of GitHub"
                        },
                    ),
                )
            }
            stores.forEach { store ->
                add(
                    AsyncActionItem(
                        id = "keys_store_${store.key}",
                        title = store.label,
                        subtitle = "Your ${store.label} library. Its games that are not installed go with it",
                        value = "Signed in",
                        confirmTitle = "Sign out of ${store.label}?",
                        run = { ctx, _ ->
                            store.signOut(ctx).fold(
                                onSuccess = { "Signed out of ${store.label}" },
                                onFailure = { "Could not sign out: ${it.message ?: "unknown error"}" },
                            )
                        },
                    ),
                )
            }
        }
        val keys = services.map { service ->
            AsyncActionItem(
                id = "keys_${service.id}",
                title = service.label,
                subtitle = service.usedFor,
                value = "Saved",
                confirmTitle = "Remove ${service.label}'s key from this device?",
                run = { ctx, _ ->
                    withContext(Dispatchers.IO) { service.keys.forEach { CredentialStore.put(ctx, it, "") } }
                    "Removed ${service.label}'s key"
                },
            )
        }
        return listOf(
            CatalogGroup(
                id = "keys_signins",
                title = "Sign-ins",
                items = signIns.ifEmpty { listOf(ActionItem(id = "keys_signins_none", title = "No sign-ins on this device")) },
            ),
            CatalogGroup(
                id = "keys_services",
                title = "Keys",
                items = keys.ifEmpty { listOf(ActionItem(id = "keys_services_none", title = "No keys on this device")) },
            ),
            CatalogGroup(
                id = "keys_plugins",
                title = "Plugins",
                items = listOf(
                    NestedScreenItem(
                        id = "keys_plugin_signing",
                        title = "Keys you trust",
                        subtitle = "The signing keys droidtop accepts plugins from, each with its own Remove",
                        registryId = AppSettingsCatalogs.SCREEN_PLUGIN_KEYS,
                    ),
                ),
            ),
        )
    }
}
