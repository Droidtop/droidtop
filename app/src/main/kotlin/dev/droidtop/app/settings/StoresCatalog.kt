package dev.droidtop.app.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dev.droidtop.app.LauncherGamesActivity
import dev.droidtop.library.PcStoreNames
import dev.droidtop.library.integrations.PluginJobsScreen
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogChip
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.stores.StoreHolding
import dev.droidtop.library.stores.StoreChanges
import dev.droidtop.library.stores.StoreInstallJob
import dev.droidtop.library.stores.StoreLibraries
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.stores.StoreSignIn
import dev.droidtop.library.stores.StoreSignInKind
import dev.droidtop.library.stores.StoreSyncs
import dev.droidtop.library.userFacingErrorMessage
import dev.droidtop.runtime.windows.PcLibrary
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.runtime.windows.displayName
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The five stores droidtop can read a library from, and the one place that
 * knows how each is signed in to, signed out of, asked to sync and asked
 * whether it is signed in (docs/SPEC.md 7i and 7j "Places"). Before this
 * the signed-in check was written out three times in
 * [AppSettingsCatalogs] and each sign-in launch twice; the Stores place
 * and the accounts screen now both read this.
 *
 * Every store answers through its [StoreLibrary] ([own], docs/SPEC.md 7g
 * "Stores"); all five are droidtop's own. droidtop keeps no password: a web
 * sign-in shows the store's own page, a key sign-in takes the key the person
 * made on the store's site, and Steam signs in step by step on droidtop's
 * own screen (a QR code, or a password Steam checks and droidtop does not keep).
 */
internal enum class PcStore(val key: String, val label: String, val source: PcLibrary.Source) {
    STEAM("steam", "Steam", PcLibrary.Source.STEAM),
    GOG("gog", "GOG", PcLibrary.Source.GOG),
    EPIC("epic", "Epic Games", PcLibrary.Source.EPIC),
    AMAZON("amazon", "Amazon Games", PcLibrary.Source.AMAZON),
    ITCH("itch", "itch.io", PcLibrary.Source.ITCH),
    ;

    /** The store, or null when this build has none of that id. */
    val own: StoreLibrary? get() = StoreLibraries.byId(key)

    /** The name this store's games carry in the library (the Store filter's value). */
    val libraryName: String get() = source.displayName()

    /** How the store's own sign-in screen describes itself, for the row's tooltip. */
    val signInNote: String
        get() = when (own?.signInKind) {
            StoreSignInKind.API_KEY -> "Paste the API key you make on $label's site"
            StoreSignInKind.WEB_PAGE, null -> "Signs in on $label's own page"
            StoreSignInKind.ACCOUNT -> "Sign in with a QR code or a password, then download your games"
        }

    fun signedIn(context: Context): Boolean = runCatching { own?.signedIn(context) == true }.getOrDefault(false)

    /** Who is signed in, where the store keeps it in the open, else null. */
    fun accountName(context: Context): String? = runCatching { own?.accountName(context) }.getOrNull()

    /** The sign-in screen for a store that has one; null for a key sign-in, which is a row of the store's page. */
    fun signInIntent(context: Context): Intent? {
        val store = own ?: return null
        return when (store.signInKind) {
            StoreSignInKind.WEB_PAGE, StoreSignInKind.ACCOUNT -> dev.droidtop.app.StoreSignInActivity.intent(context, store.id)
            StoreSignInKind.API_KEY -> null
        }
    }

    /** The store's own sign-out; a failure carries the reason. */
    suspend fun signOut(context: Context): Result<Unit> {
        val store = own ?: return Result.failure(IllegalStateException("This build has no $label store"))
        return store.signOut(context).onSuccess { StoreChanges.announce(context) }
    }

    /**
     * Reads the store's library again through the one sync every store has
     * ([StoreSyncs], docs/SPEC.md 7g and 7j, Droidtop/tracker#225) and says
     * how many games it holds, or why it could not. Returns the outcome line.
     */
    suspend fun requestSync(context: Context): String {
        val own = own ?: return "This build has no $label store"
        return StoreSyncs.run(context, own).fold(
            onSuccess = { count ->
                StoreChanges.announce(context)
                "$count ${if (count == 1) "game" else "games"}"
            },
            onFailure = { exc ->
                Log.w("droidtop.stores", "Sync failed for $label", exc)
                userFacingErrorMessage(exc)
            },
        )
    }

    /** When this store's library was last read, or null. */
    fun lastSynced(context: Context): Long? = StoreSyncs.lastSynced(context, key)
}

/** Reads every signed-in store's library again, one after the other; one line naming each store's outcome. */
internal suspend fun syncAllStores(context: Context): String {
    val signedIn = withContext(Dispatchers.IO) { PcStore.entries.filter { it.signedIn(context) } }
    if (signedIn.isEmpty()) return "No store is signed in"
    val lines = mutableListOf<String>()
    for (store in signedIn) lines += "${store.label}: ${store.requestSync(context)}"
    return lines.joinToString(". ")
}

/** One store's games as the library has them. */
internal data class StoreCounts(val total: Int, val installed: Int, val family: Int = 0, val free: Int = 0)

/** The account's own games of [source]; a family's and unplayed free ones are counted apart. */
internal fun storeCounts(games: List<PcLibrary.Game>, source: PcLibrary.Source): StoreCounts {
    val all = games.filter { it.source == source }
    val mine = all.filter { it.holding == StoreHolding.OWNED }
    return StoreCounts(
        mine.size,
        mine.count { it.installed },
        all.count { it.holding == StoreHolding.FAMILY },
        all.count { it.holding == StoreHolding.FREE },
    )
}

/** The library row's value: what is known, in words ("12 games, 3 installed"); short, so it fits the value column. */
internal fun countsLine(counts: StoreCounts, signedIn: Boolean): String = when {
    counts.total > 0 -> "${counts.total} ${if (counts.total == 1) "game" else "games"}, ${counts.installed} installed"
    signedIn -> "No games read from this store yet"
    else -> "Sign in to read this store's library"
}

/** "Synced 5 min ago": when the library was last read, in the coarsest unit that is honest. */
internal fun syncedAgo(nowMs: Long, thenMs: Long?): String {
    if (thenMs == null) return "Not synced yet"
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
        live = storeJobChanges,
    )

    /**
     * What the Stores pages follow live: which store installs are under way and how far they have
     * got, so a store's row and page show an install moving (DroidDeck's busy bar) without
     * re-reading the pages for every other job.
     */
    private val storeJobChanges: Flow<Any?> = PluginJobsCenter.entries()
        .map { all -> all.filter { it.nativeKind == StoreInstallJob.KIND && !it.done }.map { Triple(it.jobId, it.percent, it.paused) } }
        .distinctUntilChanged()

    /** [store]'s installs that are running or paused, from the one jobs list. */
    private fun storeJobs(store: PcStore): List<PluginJobsCenter.Entry> {
        val label = store.own?.label ?: return emptyList()
        return PluginJobsCenter.entries().value.filter { it.nativeKind == StoreInstallJob.KIND && !it.done && it.pluginLabel == label }
    }

    /** A row's progress bar for [jobs]: the first running install's, an empty track while its size is unknown. */
    private fun busyProgress(jobs: List<PluginJobsCenter.Entry>): Float? =
        jobs.firstOrNull { !it.paused }?.let { if (it.percent >= 0) it.percent / 100f else -1f }

    private suspend fun rootGroups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val signedInByStore = PcStore.entries.associateWith { it.signedIn(context) }
        val installing = PcStore.entries.sumOf { store -> storeJobs(store).count { !it.paused } }
        val anySignedIn = signedInByStore.values.any { it }
        listOfNotNull(
            CatalogGroup(
                id = "stores_list",
                title = null,
                // The page's facts at a glance (DroidDeck's store header chips).
                chips = listOfNotNull(
                    signedInByStore.values.count { it }.let { n -> CatalogChip("$n of ${PcStore.entries.size} signed in", ok = n > 0) },
                    CatalogChip("$installing downloading").takeIf { installing > 0 },
                ),
                items = PcStore.entries.map { store ->
                    val signedIn = signedInByStore[store] == true
                    NestedScreenItem(
                        id = "store_${store.key}",
                        title = store.label,
                        // How to sign in is only worth saying while the person is not signed in; the value column
                        // already says "Signed in" (Droidtop/tracker#367).
                        subtitle = if (signedIn) null else store.signInNote,
                        inline = storePage(store),
                        valueLabel = { if (signedIn) "Signed in" else "Not signed in" },
                        icon = CatalogIcon.GLOBAL,
                        progress = busyProgress(storeJobs(store)),
                    )
                },
            ),
            CatalogGroup(
                id = "stores_sync_group",
                title = null,
                items = listOf(
                    AsyncActionItem(
                        id = "stores_sync_all",
                        title = "Sync all libraries",
                        subtitle = "Re-reads every signed-in store for games added since the last read",
                        run = { ctx, _ -> syncAllStores(ctx) },
                    ),
                ),
            ).takeIf { anySignedIn },
        )
    }

    private fun storePage(store: PcStore) = CatalogScreen(
        id = "store_page_${store.key}",
        title = store.label,
        subtitle = "Account, library and downloads",
        groups = { context -> pageGroups(context, store) },
        // The search index must not read every store's tables to list a few static rows.
        indexGroups = { emptyList() },
        live = storeJobChanges,
    )

    /**
     * A store page's header chips: who is signed in, when droidtop last asked for a sync, and how
     * many installs are under way. Facts, never controls; the library's own counts stay its row's
     * value, so nothing is said twice.
     */
    private fun pageChips(context: Context, store: PcStore, signedIn: Boolean, jobs: List<PluginJobsCenter.Entry>): List<CatalogChip> = buildList {
        add(
            if (signedIn) {
                CatalogChip(store.accountName(context)?.let { "Signed in as $it" } ?: "Signed in", ok = true)
            } else {
                CatalogChip("Not signed in")
            },
        )
        if (signedIn) add(CatalogChip(syncedAgo(System.currentTimeMillis(), store.lastSyncRequested(context))))
        val running = jobs.count { !it.paused }
        if (running > 0) add(CatalogChip("$running downloading"))
        val paused = jobs.size - running
        if (paused > 0) add(CatalogChip("$paused paused"))
    }

    private suspend fun pageGroups(context: Context, store: PcStore): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val signedIn = store.signedIn(context)
        val counts = storeCounts(runCatching { PcLibrary.storeGames(context) }.getOrDefault(emptyList()), store.source)
        val jobs = storeJobs(store)

        // Who is signed in is the page's first chip; the account's rows are what can be done about it.
        val account = if (signedIn) {
            listOf(
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

        val dlc = if (signedIn) runCatching { store.own?.dlcCount(context) }.getOrNull() else null
        val library = buildList {
            add(
                ActionItem(
                    id = "store_${store.key}_library",
                    title = "Library",
                    // PC Games shows a game owned twice (two editions, a copy in each group) as one card, so its tabs can count fewer.
                    // When droidtop last asked for a sync is the page's chip.
                    subtitle = "PC Games shows editions and copies of one game as one card, so its tabs can count fewer",
                    value = countsLine(counts, signedIn),
                    run = {},
                ),
            )
            // Each count its own row, so every row fits its value column.
            if (dlc != null && dlc > 0) {
                add(ActionItem(id = "store_${store.key}_dlc", title = "DLC", subtitle = "DLC you hold for games in your library", value = "$dlc", run = {}))
            }
            if (counts.family > 0) {
                add(
                    ActionItem(
                        id = "store_${store.key}_family",
                        title = PcStoreNames.groupOf(store.label, StoreHolding.FAMILY),
                        subtitle = "Games another account lends you, listed apart from yours",
                        value = "${counts.family} ${if (counts.family == 1) "game" else "games"}",
                        run = {},
                    ),
                )
            }
            if (counts.free > 0) {
                add(
                    ActionItem(
                        id = "store_${store.key}_free",
                        title = PcStoreNames.groupOf(store.label, StoreHolding.FREE),
                        subtitle = "Free games on the account, not counted as yours until played, listed apart",
                        value = "${counts.free} ${if (counts.free == 1) "game" else "games"}",
                        run = {},
                    ),
                )
            }
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
            if (signedIn) {
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

        // The store's own settings (Steam's status, cloud saves and message notifications), once signed in.
        val own = if (signedIn) runCatching { store.own?.settingsItems(context) }.getOrNull().orEmpty() else emptyList()
        listOfNotNull(
            CatalogGroup(
                id = "store_${store.key}_account_group",
                title = "Account",
                items = account,
                chips = pageChips(context, store, signedIn, jobs),
            ),
            CatalogGroup(id = "store_${store.key}_settings_group", title = "Settings", items = own).takeIf { own.isNotEmpty() },
            CatalogGroup(id = "store_${store.key}_library_group", title = "Library", items = library),
            CatalogGroup(
                id = "store_${store.key}_downloads_group",
                title = "Downloads",
                // This store's installs under way, as the Downloads place draws them (DroidDeck's busy bar),
                // then the way to the whole list. Every store installs through the one jobs list.
                items = jobs.map { PluginJobsScreen.jobRow(it) } + NestedScreenItem(
                    id = "store_${store.key}_downloads",
                    title = "All downloads",
                    subtitle = "Everything downloading or waiting, with Pause, Resume and Cancel",
                    registryId = PluginJobsScreen.ID,
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
