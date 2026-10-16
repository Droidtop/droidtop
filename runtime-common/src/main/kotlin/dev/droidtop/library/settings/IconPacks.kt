package dev.droidtop.library.settings

import android.content.Context

/**
 * Icon packs (Droidtop/tracker#508), as the one settings catalog offers them. Gaming's Apps tiles and Standard's
 * drawer draw from the same icon cache, which applies the pack Standard's launcher picks (`IconPackManager`), so there
 * is one choice and one mechanism: this hook lets the catalog's Appearance group show and change that choice. The
 * launcher registers [source] at start; with none, the row is not shown.
 */
object IconPacks {
    interface Source {
        /** Installed packs as (package, label), the system icons first with an empty package. */
        fun installed(context: Context): List<Pair<String, String>>

        /** The package of the pack in use; empty for the system icons. */
        fun selected(context: Context): String

        /** Uses [pack] (empty for the system icons) and redraws every icon that comes from it. */
        fun select(context: Context, pack: String)
    }

    @Volatile
    var source: Source? = null
}
