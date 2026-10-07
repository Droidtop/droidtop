package dev.droidtop.stores

import dev.droidtop.library.stores.StoreLibraries
import dev.droidtop.stores.amazon.AmazonStore
import dev.droidtop.stores.itch.ItchStore

/**
 * The stores this module runs (docs/SPEC.md 7g, "Stores"), registered with
 * [StoreLibraries] once at process start, before anything reads the library
 * or restores an install job.
 */
object BuiltInStores {
    fun register() {
        StoreLibraries.register(AmazonStore())
        StoreLibraries.register(ItchStore())
    }
}
