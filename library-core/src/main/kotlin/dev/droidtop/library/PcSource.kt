package dev.droidtop.library

import dev.droidtop.library.stores.StoreLibraries
import java.io.File

/**
 * Where a PC game came from (docs/SPEC.md 7g, "Stores", and 7j "Filters"):
 * a store, one of the person's game folders, or a Wine shortcut made by hand
 * in a prefix. The one vocabulary the Source facet, the focus line, the
 * capsule badge, a store page's Open library and the PC library's own rows
 * share, so a store is named once (by its [dev.droidtop.library.stores.StoreLibrary])
 * and no reader keeps a list of stores of its own.
 *
 * [id] is what a filter or a saved view keeps; [label] is what is drawn, read
 * when it is drawn, so a store that renames itself needs no migration.
 */
sealed class PcSource {
    abstract val id: String

    /** A store's rows, by the store's short id ("gog"): every registered store alike, built in or plugged in. */
    data class Store(val storeId: String) : PcSource() {
        override val id: String get() = storeId
    }

    /** The games found under one of the person's game folders, by the folder's path ([rootId]); "" for a folder outside them. */
    data class Folder(val rootId: String) : PcSource() {
        override val id: String get() = FOLDER_PREFIX + rootId
    }

    /** A shortcut the person made in a Wine prefix by hand. */
    data object WineShortcut : PcSource() {
        override val id: String get() = WINE_ID
    }

    /** The name a person reads: the store's own label, the folder's name, "Wine shortcuts". */
    fun label(): String = when (this) {
        is Store -> StoreLibraries.byId(storeId)?.label ?: storeId.replaceFirstChar { it.uppercase() }
        is Folder -> rootId.takeIf { it.isNotBlank() }?.let { root -> File(root).name.ifBlank { root } } ?: FOLDER_LABEL
        WineShortcut -> WINE_LABEL
    }

    /** "GOG", "Folder: Games", "Wine shortcut": the words a focus line or a page row reads. */
    fun detail(): String = when (this) {
        is Store -> label()
        is Folder -> if (rootId.isBlank()) FOLDER_LABEL else "$FOLDER_LABEL: ${label()}"
        WineShortcut -> "Wine shortcut"
    }

    companion object {
        private const val FOLDER_PREFIX = "folder:"
        private const val FOLDER_KEY = "folder"
        private const val WINE_ID = "wine"
        private const val FOLDER_LABEL = "Folder"
        private const val WINE_LABEL = "Wine shortcuts"

        /**
         * The catalog item id prefix of a store page's "Open library" row; the
         * store's id follows. The Gaming shell fulfils it for every registered
         * store (docs/SPEC.md 7j, "Places").
         */
        const val LIBRARY_ITEM_PREFIX = "store_library:"

        /**
         * The catalog item id of a store page's holding row ("Shared with you ·
         * 573"): [holdingItemId]. The Gaming shell fulfils it with PC Games
         * filtered to that store and that holding (docs/SPEC.md 7j, "Places").
         */
        const val HOLDING_ITEM_PREFIX = "store_holding:"

        fun holdingItemId(storeId: String, holding: dev.droidtop.library.stores.StoreHolding): String =
            "$HOLDING_ITEM_PREFIX$storeId:${holding.name}"

        /** The id of the Game sources row that opens PC Games on every imported game (the "Imported from" filter). */
        const val IMPORTED_ITEM_ID = "game_sources_imported"

        /** The source an [id] names; an id this build has no store for stays a store, drawn by its id. */
        fun fromId(id: String): PcSource = when {
            id == WINE_ID -> WineShortcut
            id.startsWith(FOLDER_PREFIX) -> Folder(id.removePrefix(FOLDER_PREFIX))
            else -> Store(id)
        }

        /**
         * Where [entry] came from, or null for an entry that is not a PC game.
         * A store row (or a folder engine detection claimed from a store, which
         * carries the store's id) is its store, and so is a folder holding a
         * store's marker ([PcInfo.marker]); a game under one of [roots] is
         * that root, the most specific when one root is inside another; a
         * hand-made Wine shortcut is [WineShortcut]. Pure and cheap (string
         * work over a handful of roots), so a list may ask it per row.
         */
        fun of(entry: LibraryEntry, roots: List<String> = emptyList()): PcSource? {
            val pc = entry.pcInfo
            storeIdOf(pc?.storeId ?: entry.id)?.let { return Store(it) }
            // A folder a store installed outside droidtop reads as that store (its marker, 7g "Store markers").
            pc?.marker?.let { return Store(it.storeId) }
            if (pc != null && pc.storeId == null && pc.installPath == null && entry.id.startsWith("/") &&
                entry.kind == LibraryEntryKind.WINE_PROFILE
            ) {
                return WineShortcut
            }
            val path = pc?.installPath?.takeIf { it.startsWith("/") } ?: entry.id.takeIf { it.startsWith("/") }
            if (path == null && pc == null) return null
            return Folder(path?.let { rootOf(it, roots) }.orEmpty())
        }

        /**
         * The store half of a library key ("gog:1207658691" is gog); null for a
         * path, a folder game ("folder:...") or a key with no store half.
         */
        fun storeIdOf(key: String?): String? {
            if (key == null || key.startsWith("/")) return null
            val prefix = key.substringBefore(':', "")
            return prefix.takeIf { it.isNotEmpty() && it != FOLDER_KEY }
        }

        /** The most specific of [roots] holding [path], or null. */
        fun rootOf(path: String, roots: List<String>): String? =
            roots.filter { root -> path == root || path.startsWith(root.trimEnd('/') + "/") }.maxByOrNull { it.length }

        /**
         * The order sources are listed in (the Source facet, the strip): the
         * stores in the registry's order (one this build does not have after
         * them), then the folders by name, then Wine shortcuts.
         */
        val ORDER: Comparator<PcSource> = compareBy<PcSource>(
            { source ->
                when (source) {
                    is Store -> StoreLibraries.all().indexOfFirst { it.id == source.storeId }.let { if (it < 0) Int.MAX_VALUE / 2 else it }
                    is Folder -> Int.MAX_VALUE - 1
                    WineShortcut -> Int.MAX_VALUE
                }
            },
            { it.label().lowercase() },
        )
    }
}
