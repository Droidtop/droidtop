package dev.droidtop.app.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dev.droidtop.app.LauncherGamesActivity
import dev.droidtop.app.PcStoreActivity
import dev.droidtop.app.PcStoreSignInActivity
import dev.droidtop.app.SteamLoginActivity
import dev.droidtop.library.PcStoreNames
import dev.droidtop.library.integrations.PluginJobsScreen
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.stores.StoreChanges
import dev.droidtop.library.stores.StoreLibraries
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.stores.StoreSignIn
import dev.droidtop.library.stores.StoreSignInKind
import dev.droidtop.library.userFacingErrorMessage
import dev.droidtop.runtime.windows.PcLibrary
import dev.droidtop.runtime.windows.SteamAccess
import dev.droidtop.runtime.windows.displayName
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The five stores droidtop can read a library from, and the one place that
 * knows how each is signed in to, signed out of, asked to sync and asked
 * whether it is signed in (docs/SPEC.md 7i and 7j "Places"). Before this
 * the signed-in check was written out three times in
 * [AppSettingsCatalogs] and each sign-in launch twice; the Stores place
 * and the accounts screen now both read this.
 *
 * A store droidtop runs itself ([own], docs/SPEC.md 7g "Stores") answers
 * every question through its [StoreLibrary]; the stores GameNative still
 * runs keep their vendored calls until they move. droidtop never sees a
 * password: a web sign-in shows the store's own page, a key sign-in takes
 * the key the person made on the store's site.
 */
internal enum class PcStore(val key: String, val label: String, val source: PcLibrary.Source) {
    STEAM("steam", "Steam", PcLibrary.Source.STEAM),
    GOG("gog", "GOG", PcLibrary.Source.GOG),
    EPIC("epic", "Epic Games", PcLibrary.Source.EPIC),
    AMAZON("amazon", "Amazon Games", PcLibrary.Source.AMAZON),
    ITCH("itch", "itch.io", PcLibrary.Source.ITCH),
    ;

    /** The store when droidtop runs it itself, or null while GameNative still does. */
    val own: StoreLibrary? get() = StoreLibraries.byId(key)

    /** The name this store's games carry in the library (the Store filter's value). */
    val libraryName: String get() = source.displayName()

    /** Steam keeps its library current through its live session; the others are read on request. */
    val syncsOnRequest: Boolean get() = this != STEAM

    /** How the store's own sign-in screen describes itself, for the row's tooltip. */
    val signInNote: String
        get() = when (own?.signInKind) {
            StoreSignInKind.API_KEY -> "Paste the API key you make on $label's site"
            StoreSignInKind.WEB_PAGE -> "Signs in on $label's own page"
            null -> if (this == STEAM) "Sign in with a QR code or a password, then download your games" else "Signs in on $label's own page"
        }

    fun signedIn(context: Context): Boolean = runCatching {
        own?.signedIn(context) ?: when (this) {
            STEAM -> app.gamenative.utils.SteamUtils.hasStoredCredentials()
            GOG -> app.gamenative.service.gog.GOGService.hasStoredCredentials(context)
            EPIC -> app.gamenative.service.epic.EpicService.hasStoredCredentials(context)
            AMAZON -> app.gamenative.service.amazon.AmazonService.hasStoredCredentials(context)
            ITCH -> false
        }
    }.getOrDefault(false)

    /** Who is signed in, where the store keeps it in the open, else null. */
    fun accountName(context: Context): String? = runCatching {
        own?.accountName(context) ?: if (this == STEAM) app.gamenative.PrefManager.username.takeIf { it.isNotBlank() } else null
    }.getOrNull()

    /** The sign-in screen for a store that has one; null for a key sign-in, which is a row of the store's page. */
    fun signInIntent(context: Context): Intent? {
        own?.let { store ->
            return when (store.signInKind) {
                StoreSignInKind.WEB_PAGE -> dev.droidtop.app.StoreSignInActivity.intent(context, store.id)
                StoreSignInKind.API_KEY -> null
            }
        }
        return when (this) {
            STEAM -> Intent(context, SteamLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            GOG -> PcStoreSignInActivity.intent(context, PcStoreSignInActivity.Store.GOG)
            EPIC -> PcStoreSignInActivity.intent(context, PcStoreSignInActivity.Store.EPIC)
            AMAZON -> PcStoreSignInActivity.intent(context, PcStoreSignInActivity.Store.AMAZON)
            ITCH -> null
        }
    }

    /** The store's own sign-out; a failure carries the reason. */
    suspend fun signOut(context: Context): Result<Unit> {
        own?.let { store ->
            return store.signOut(context).onSuccess { StoreChanges.announce(context) }
        }
        return runCatching {
            when (this) {
                // The same call Steam's own screen makes; a session that is not live has only stored preferences to clear.
                STEAM -> if (SteamAccess.isLoggedIn()) SteamAccess.logOut() else app.gamenative.PrefManager.clearSteamSessionPreferences()
                // The service's logout needs the service running; without it the stored sign-in is cleared directly.
                GOG -> app.gamenative.service.gog.GOGService.logout(context).getOrElse {
                    check(app.gamenative.service.gog.GOGService.clearStoredCredentials(context)) { "GOG would not clear its sign-in" }
                }
                EPIC -> app.gamenative.service.epic.EpicService.logout(context).getOrThrow()
                AMAZON -> app.gamenative.service.amazon.AmazonService.logout(context).getOrThrow()
                ITCH -> Unit
            }
        }
    }

    /**
     * Asks the store to re-read its library (docs/SPEC.md 7i, Droidtop/tracker#225). A store
     * droidtop runs reads it here and says how many games it holds; the GameNative services run
     * their own pass, bypassing its throttle. The time is droidtop's own note of when it asked.
     * Returns the outcome line.
     */
    suspend fun requestSync(context: Context): String {
        if (!syncsOnRequest) return "Steam keeps its library current while you are signed in"
        return withContext(Dispatchers.IO) {
            val own = own
            val line = if (own != null) {
                own.sync(context).fold(
                    onSuccess = { count ->
                        StoreChanges.announce(context)
                        "$count ${if (count == 1) "game" else "games"}"
                    },
                    onFailure = { exc ->
                        Log.w("droidtop.stores", "Sync failed for $label", exc)
                        return@withContext userFacingErrorMessage(exc)
                    },
                )
            } else {
                when (this@PcStore) {
                    GOG -> app.gamenative.service.gog.GOGService.triggerLibrarySync(context)
                    EPIC -> app.gamenative.service.epic.EpicService.triggerLibrarySync(context)
                    AMAZON -> app.gamenative.service.amazon.AmazonService.triggerLibrarySync(context)
                    STEAM, ITCH -> Unit
                }
                "Sync requested. New games appear in a moment"
            }
            context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE).edit().putLong(key, System.currentTimeMillis()).apply()
            line
        }
    }

    /** When droidtop last asked this store to sync, or null. */
    fun lastSyncRequested(context: Context): Long? =
        context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE).getLong(key, 0L).takeIf { it > 0 }

    private companion object {
        const val SYNC_PREFS = "store_library_sync"
    }
}

/** One store's games as the library has them. */
internal data class StoreCounts(val total: Int, val installed: Int)

internal fun storeCounts(games: List<PcLibrary.Game>, source: PcLibrary.Source): StoreCounts {
    val mine = games.filter { it.source == source }
    return StoreCounts(mine.size, mine.count { it.installed })
}

/** The library row's value: what is known, in words ("12 games, 3 installed"). */
internal fun countsLine(counts: StoreCounts, signedIn: Boolean): String = when {
    counts.total > 0 -> "${counts.total} ${if (counts.total == 1) "game" else "games"}, ${counts.installed} installed"
    signedIn -> "No games read from this store yet"
    else -> "Sign in to read this store's library"
}

/** "Synced 5 min ago": when droidtop last asked for a sync, in the coarsest unit that is honest. */
internal fun syncedAgo(nowMs: Long, thenMs: Long?): String {
    if (thenMs == null) return "Not synced from here yet"
    val minutes = (nowMs - thenMs).coerceAtLeast(0L) / 60_000L
    val days = minutes / (60 * 24)
    return when {
        minutes < 1 -> "Synced just now"
        minutes < 60 -> "Synced $minutes min ago"
        minutes < 60 * 24 -> "Synced ${minutes / 60} h ago"
        else -> "Synced $days ${if (days == 1L) "day" else "days"} ago"
    }
}

/**
 * The Stores place (docs/SPEC.md 7j "Places", Droidtop/tracker#258): one
 * page per store with who is signed in, its library (a count, a way into
 * it, a sync and when it last ran) and its downloads. A catalog screen
 * like the other management screens, so the Gaming shell draws it in
 * place from the left menu and both Settings surfaces reach the same
 * rows from Accounts and sources.
 */
internal object StoresCatalog {
    const val SCREEN_ID = "stores"

    /**
     * What the last key sign-in of a store said when it failed, until the
     * next attempt: a key row cannot answer by itself, so the account row
     * says it (docs/SPEC.md 7k, "a live status takes the value column").
     */
    private val keyFailures = ConcurrentHashMap<String, String>()

    fun screen() = CatalogScreen(
        id = SCREEN_ID,
        title = "Stores",
        subtitle = "Each store: who is signed in, its library, and syncing it",
        groups = { context -> rootGroups(context) },
    )

    private suspend fun rootGroups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
        listOf(
            CatalogGroup(
                id = "stores_list",
                title = null,
                items = PcStore.entries.map { store ->
                    val signedIn = store.signedIn(context)
                    NestedScreenItem(
                        id = "store_${store.key}",
                        title = store.label,
                        subtitle = store.signInNote,
                        inline = storePage(store),
                        valueLabel = { if (signedIn) "Signed in" else "Not signed in" },
                        icon = CatalogIcon.GLOBAL,
                    )
                },
            ),
        )
    }

    private fun storePage(store: PcStore) = CatalogScreen(
        id = "store_page_${store.key}",
        title = store.label,
        subtitle = "Account, library and downloads",
        groups = { context -> pageGroups(context, store) },
        // The search index must not read every store's tables to list a few static rows.
        indexGroups = { emptyList() },
    )

    private suspend fun pageGroups(context: Context, store: PcStore): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val signedIn = store.signedIn(context)
        val counts = storeCounts(runCatching { PcLibrary.storeGames(context) }.getOrDefault(emptyList()), store.source)

        val account = if (signedIn) {
            listOf(
                ActionItem(
                    id = "store_${store.key}_account",
                    title = "Signed in",
                    subtitle = "This device holds a sign-in for ${store.label}",
                    value = store.accountName(context),
                    run = {},
                ),
                AsyncActionItem(
                    id = "store_${store.key}_signout",
                    title = "Sign out",
                    subtitle = "Removes the sign-in and this store's games that are not installed. Installed games stay",
                    confirmTitle = "Sign out of ${store.label}?",
                    run = { ctx, _ ->
                        store.signOut(ctx).fold(
                            onSuccess = { "Signed out of ${store.label}" },
                            onFailure = { "Could not sign out: ${it.message ?: "unknown error"}" },
                        )
                    },
                ),
            )
        } else {
            signInRows(context, store)
        }

        val library = buildList {
            add(
                ActionItem(
                    id = "store_${store.key}_library",
                    title = "Library",
                    subtitle = if (store.syncsOnRequest) {
                        syncedAgo(System.currentTimeMillis(), store.lastSyncRequested(context))
                    } else {
                        "Steam keeps its library current while you are signed in"
                    },
                    value = countsLine(counts, signedIn),
                    run = {},
                ),
            )
            if (counts.total > 0) {
                add(
                    // The Gaming shell fulfils this by id (PcStoreNames.LIBRARY_ITEM_PREFIX): PC Games opened
                    // on this store's filter. Anywhere else the games screen opens unfiltered.
                    ActionItem(
                        id = "${PcStoreNames.LIBRARY_ITEM_PREFIX}${store.libraryName}",
                        title = "Open library",
                        subtitle = "Shows only ${store.label} games in PC Games",
                        run = { ctx ->
                            ctx.startActivity(
                                Intent(ctx, LauncherGamesActivity::class.java)
                                    .setAction(LauncherGamesActivity.ACTION_SHOW_GAMES)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    ),
                )
            }
            if (signedIn && store.syncsOnRequest) {
                add(
                    AsyncActionItem(
                        id = "store_${store.key}_sync",
                        title = "Sync library",
                        subtitle = "Re-reads ${store.label} for games added since the last read",
                        run = { ctx, _ -> store.requestSync(ctx) },
                    ),
                )
            }
        }

        listOf(
            CatalogGroup(id = "store_${store.key}_account_group", title = "Account", items = account),
            CatalogGroup(id = "store_${store.key}_library_group", title = "Library", items = library),
            CatalogGroup(
                id = "store_${store.key}_downloads_group",
                title = "Downloads",
                items = listOf(
                    if (store.own != null) {
                        // A store droidtop runs installs through the one jobs list: the Downloads place.
                        NestedScreenItem(
                            id = "store_${store.key}_downloads",
                            title = "Downloads",
                            subtitle = "What is downloading or waiting, with Pause, Resume and Cancel",
                            registryId = PluginJobsScreen.ID,
                        )
                    } else {
                        ActionItem(
                            id = "store_${store.key}_downloads",
                            title = "Downloads",
                            subtitle = "What is downloading or waiting from this store",
                            run = { ctx -> ctx.startActivity(PcStoreActivity.intent(ctx, entryId = null)) },
                        )
                    },
                ),
            ),
        )
    }

    /** The rows a store that is not signed in shows: its sign-in screen, or the key row and where to make a key. */
    private fun signInRows(context: Context, store: PcStore): List<CatalogItem> {
        val own = store.own
        // A key sign-in starts nothing, so asking it for its key page is free.
        val signIn = own?.takeIf { it.signInKind == StoreSignInKind.API_KEY }?.signIn(context)
        if (own != null && signIn is StoreSignIn.ApiKey) {
            return listOf(
                ActionItem(
                    id = "store_${store.key}_account",
                    title = "Account",
                    subtitle = "This device holds no sign-in for ${store.label}",
                    value = keyFailures[store.key] ?: "Not signed in",
                    run = {},
                ),
                TextInputItem(
                    id = "store_${store.key}_api_key",
                    title = "API key",
                    subtitle = "The key you make on ${store.label}'s API keys page. droidtop checks it with ${store.label} and keeps it on this device",
                    value = "",
                    secret = true,
                    onChange = { ctx, text ->
                        if (text.isBlank()) return@TextInputItem
                        withContext(Dispatchers.IO) { own.completeSignIn(ctx, text.trim()) }.fold(
                            onSuccess = {
                                keyFailures.remove(store.key)
                                StoreChanges.announce(ctx)
                            },
                            onFailure = { keyFailures[store.key] = userFacingErrorMessage(it) },
                        )
                    },
                ),
                ActionItem(
                    id = "store_${store.key}_key_page",
                    title = "Make a key",
                    subtitle = "Opens ${store.label}'s API keys page in the browser",
                    run = { ctx ->
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(signIn.keyPage)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                ),
            )
        }
        return listOf(
            ActionItem(
                id = "store_${store.key}_signin",
                title = "Sign in",
                subtitle = store.signInNote,
                run = { ctx -> store.signInIntent(ctx)?.let(ctx::startActivity) },
            ),
        )
    }
}
