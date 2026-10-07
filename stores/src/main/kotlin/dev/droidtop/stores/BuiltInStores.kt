package dev.droidtop.stores

import dev.droidtop.library.stores.StoreLibraries
import dev.droidtop.stores.amazon.AmazonStore
import dev.droidtop.stores.epic.EpicStore
import dev.droidtop.stores.gog.GOGStore
import dev.droidtop.stores.itch.ItchStore
import dev.droidtop.stores.steam.SteamStore

/**
 * The stores this module runs (docs/SPEC.md 7g, "Stores"), registered with
 * [StoreLibraries] once at process start, before anything reads the library
 * or restores an install job. Steam first, the order the Stores place lists them.
 */
object BuiltInStores {
    fun register() {
        StoreLibraries.register(SteamStore())
        StoreLibraries.register(GOGStore())
        StoreLibraries.register(EpicStore())
        StoreLibraries.register(AmazonStore())
        StoreLibraries.register(ItchStore())
    }
}
