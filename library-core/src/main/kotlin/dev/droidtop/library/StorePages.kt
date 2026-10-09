package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.scraper.PcStoreId
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * One store's ownership of a game (docs/SPEC.md 7m, "Ownership, and
 * "Get it on""): the store's own id for it. "Owned on Steam and GOG" is
 * built from these and nothing else. A local folder is deliberately not an
 * ownership: the library is the local copies and the card already says so.
 */
data class Ownership(val store: String, val id: String) {
    val label: String get() = storeLabel(store)
}

/** The stores droidtop reads ids for, in the order 7m lists them. */
val OWNERSHIP_STORES = listOf(PcStoreId.STEAM, PcStoreId.GOG, "epic", "amazon", "itch")

/** A store id's display name, keyed by the store half of the id. */
fun storeLabel(store: String): String = when (store) {
    PcStoreId.STEAM -> "Steam"
    PcStoreId.GOG -> "GOG"
    "epic" -> "Epic"
    "amazon" -> "Amazon"
    "itch" -> "itch.io"
    else -> store.replaceFirstChar { it.uppercase() }
}

/**
 * The store that owns this entry: a store row's own id, or the
 * `PcInfo.storeId` a folder absorbed when engine detection claimed a
 * store's install directory. Null for a folder path or an app id, which
 * say where a game is rather than what a store calls it.
 */
fun LibraryEntry.ownership(): Ownership? {
    val ref = PcStoreId.parse(pcInfo?.storeId) ?: PcStoreId.parse(id).takeUnless { id.startsWith("/") }
    return ref?.takeIf { it.store in OWNERSHIP_STORES }?.let { Ownership(it.store, it.id) }
}

/**
 * "Owned on Steam and GOG": the stores this game is owned on, in 7m's own
 * order, in one line; blank when no store owns it.
 */
fun Collection<Ownership>.ownershipLabel(): String {
    val names = map { it.store }.distinct()
        .sortedBy { OWNERSHIP_STORES.indexOf(it) }
        .map { storeLabel(it) }
    if (names.isEmpty()) return ""
    return "Owned on " + when (names.size) {
        1 -> names.single()
        2 -> "${names[0]} and ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}

/**
 * Where a game owned on no store can be bought, and where its developer
 * takes support (docs/SPEC.md 7m): read from the game's own scraped links
 * and nothing else, because a menu that opens is not a place for a lookup.
 * Each is information, never a pitch.
 */
object StorePages {
    private val SUPPORT_HOSTS = setOf("patreon.com", "subscribestar.com", "subscribestar.adult")

    /** The links that say where this game can be bought, as "Get it on <store>" rows. */
    fun getItOn(links: List<GameLink>): List<GameLink> =
        links.mapNotNull { link -> storeOf(link.url)?.let { GameLink("Get it on $it", link.url) } }
            .distinctBy { it.label }

    /** The links that say where this game's developer takes support. */
    fun support(links: List<GameLink>): List<GameLink> =
        links.filter { hostOf(it.url) in SUPPORT_HOSTS }
            .map { GameLink("Support the developer", it.url) }
            .distinctBy { it.url }

    /** The plain links that are neither, kept as the menu's own Links rows. */
    fun other(links: List<GameLink>): List<GameLink> =
        links.filter { storeOf(it.url) == null && hostOf(it.url) !in SUPPORT_HOSTS }

    private fun storeOf(url: String): String? {
        val host = hostOf(url) ?: return null
        return when {
            host.endsWith("steampowered.com") || host.endsWith("steamcommunity.com") -> "Steam"
            host.endsWith("gog.com") -> "GOG"
            host.endsWith("epicgames.com") -> "Epic"
            host.endsWith("itch.io") -> "itch.io"
            host.endsWith("dlsite.com") -> "DLsite"
            else -> null
        }
    }

    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host?.removePrefix("www.")?.lowercase() }.getOrNull()
}

/**
 * Whether the "Get it on" and "Support the developer" rows show, for one game
 * (the menu's "Hide these for this game" row) or for every game (the menu's
 * "Hide these for every game" row, and the switch on the Stores place;
 * docs/SPEC.md 7m). A preference about what droidtop shows, not a library
 * fact.
 */
object StoreLinkPrefs {
    private const val KEY_HIDDEN = "droidtop_store_links_hidden"
    private const val KEY_HIDDEN_EVERYWHERE = "droidtop_store_links_hidden_everywhere"

    private fun prefs(context: Context) = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)

    /** Whether the rows are hidden for every game. */
    fun hiddenEverywhere(context: Context): Boolean = prefs(context).getBoolean(KEY_HIDDEN_EVERYWHERE, false)

    fun setHiddenEverywhere(context: Context, hidden: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDDEN_EVERYWHERE, hidden).apply()
    }

    /** Whether the rows are hidden for the game these entry ids are, or for every game. */
    fun hidden(context: Context, ids: Collection<String>): Boolean {
        if (hiddenEverywhere(context)) return true
        val hidden = prefs(context).getStringSet(KEY_HIDDEN, null) ?: return false
        return ids.any { it in hidden }
    }

    fun hide(context: Context, ids: Collection<String>) {
        // Copied before writing: the set the preferences hand back is the
        // one they keep, and mutating it in place can lose the edit.
        val hidden = prefs(context).getStringSet(KEY_HIDDEN, null)?.toHashSet() ?: hashSetOf()
        hidden.addAll(ids)
        prefs(context).edit().putStringSet(KEY_HIDDEN, hidden).apply()
    }
}
