package dev.droidtop.app.settings

import android.content.Context
import dev.droidtop.library.scraper.ScraperKeyService
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.stores.StoreChanges
import dev.droidtop.library.stores.StoreLibraries
import dev.droidtop.library.stores.StoreSignInKind
import dev.droidtop.net.GitHubTokenStore
import dev.droidtop.pluginhost.UserOriginKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings > Accounts and sources > Keys and accounts (docs/SPEC.md "Accounts and sources", Droidtop/tracker#12; the
 * owner, 2026-10-28: "Add one 'Keys and accounts' page under Settings > Accounts and sources. It lists every key and
 * token droidtop holds, where each is used, and a Remove. Each service's own page keeps its row."). One row per key or
 * sign-in droidtop holds, saying where it is used; pressing it removes it after a question, through the same code its
 * own page removes it with. The page keeps nothing itself: what it lists is read from where each key lives.
 */
internal const val SCREEN_KEYS_AND_ACCOUNTS = "keys_and_accounts"

/** One key on the page: what it is, where it is used, and how it is removed. */
internal data class HeldKey(val id: String, val title: String, val usedFor: String, val remove: suspend (Context) -> String)

/** The Accounts and sources row that opens the page, with how many keys droidtop holds. */
internal fun AppSettingsCatalogs.keysAndAccountsRow(count: Int): NestedScreenItem = NestedScreenItem(
    id = "accounts_keys",
    title = "Keys and accounts",
    subtitle = "Every key, token and sign-in droidtop holds, where each is used, and Remove",
    inline = keysAndAccountsScreen(),
    valueLabel = { if (count == 0) "None" else "$count held" },
)

internal fun AppSettingsCatalogs.keysAndAccountsScreen() = CatalogScreen(
    id = SCREEN_KEYS_AND_ACCOUNTS,
    title = "Keys and accounts",
    subtitle = "What droidtop holds for you. Removing one here is the same as removing it on its own page",
    groups = { context ->
        keysGroups(withContext(Dispatchers.IO) { heldKeys(context) })
    },
)

/** The page's groups for [held]: one per kind, in the order first met, one row per key; a plain line when there is none. Pure. */
internal fun keysGroups(held: List<Pair<String, HeldKey>>): List<CatalogGroup> =
    held.groupBy { it.first }.map { (group, keys) ->
        CatalogGroup(id = "keys_${group.lowercase().replace(' ', '_')}", title = group, items = keys.map { keyRow(it.second) })
    }.ifEmpty {
        listOf(CatalogGroup(id = "keys_none", title = null, items = listOf(ActionItem(id = "keys_none_row", title = "droidtop holds no keys or sign-ins"))))
    }

/** One key's row: its name, where it is used, and Remove, asked first. Pure. */
internal fun keyRow(key: HeldKey): CatalogItem = AsyncActionItem(
    id = "keys_${key.id}",
    title = key.title,
    subtitle = key.usedFor,
    value = "Remove",
    confirmTitle = "Remove ${key.title}?",
    confirmKeep = "Keep it",
    run = { ctx, _ -> key.remove(ctx) },
)

/**
 * Every key droidtop holds, by group, in the order the page shows them. Disk reads (preferences, the encrypted key
 * store, the trusted-keys file): never on the main thread.
 */
internal fun AppSettingsCatalogs.heldKeys(context: Context): List<Pair<String, HeldKey>> = buildList {
    // Store sign-ins: every store's own, removed by its Sign out.
    StoreLibraries.all().filter { runCatching { it.signedIn(context) }.getOrDefault(false) }.forEach { store ->
        val what = if (store.signInKind == StoreSignInKind.API_KEY) "${store.label} API key" else "${store.label} sign-in"
        add(
            "Game sources" to HeldKey("store_${store.id}", what, "${store.label}: your library, installs and what the store keeps for you") { ctx ->
                store.signOut(ctx).fold(
                    onSuccess = {
                        StoreChanges.announce(ctx)
                        "Signed out of ${store.label}"
                    },
                    onFailure = { "${store.label} would not sign out: ${it.message ?: it.javaClass.simpleName}" },
                )
            },
        )
    }
    if (GitHubTokenStore.isSet(context)) {
        val login = GitHubTokenStore.login(context)
        add(
            "Accounts" to HeldKey("github", if (login != null) "GitHub ($login)" else "GitHub token", "Plugin catalogs, private plugin repositories and GitHub downloads") { ctx ->
                GitHubTokenStore.clear(ctx)
                "Signed out of GitHub"
            },
        )
    }
    // Scraper, achievement, debrid and file-host keys: each service's own fields, cleared the way its setup clears them.
    ScraperKeyService.entries.filter { service -> service.read(context).values.any { it.isNotBlank() } }.forEach { service ->
        add(
            "Service keys" to HeldKey("service_${service.name.lowercase()}", service.title, service.gets) { ctx ->
                service.write(ctx, service.fields.associate { it.id to "" })
                "Removed the ${service.title} key"
            },
        )
    }
    // Plugin signers the person chose to trust; the official one is droidtop's own and is not a key of theirs.
    val file = UserOriginKeys.storeFile(context)
    UserOriginKeys.load(file).values.sortedBy { it.origin }.forEach { entry ->
        add(
            "Plugin signing keys" to HeldKey("origin_${entry.origin}", entry.origin, "Plugins signed by \"${entry.origin}\" run and update") { ctx ->
                stopTrustingOrigin(ctx, entry.origin)
            },
        )
    }
    UserOriginKeys.appEntries(file).keys.sorted().forEach { packageName ->
        add(
            "Plugin signing keys" to HeldKey("app_$packageName", packageName, "The plugin this app carries runs") { ctx ->
                stopTrustingApp(ctx, packageName)
            },
        )
    }
}
