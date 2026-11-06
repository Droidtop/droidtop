package dev.droidtop.stores

import dev.droidtop.library.stores.StoreLibraries
import dev.droidtop.stores.amazon.AmazonStore
import dev.droidtop.stores.battlenet.BattleNetStore
import dev.droidtop.stores.ea.EAStore
import dev.droidtop.stores.epic.EpicStore
import dev.droidtop.stores.gog.GOGStore
import dev.droidtop.stores.humble.HumbleStore
import dev.droidtop.stores.indiegala.IndiegalaStore
import dev.droidtop.stores.itch.ItchStore
import dev.droidtop.stores.legacy.LegacyStore
import dev.droidtop.stores.steam.SteamStore
import dev.droidtop.stores.ubisoft.UbisoftStore
import dev.droidtop.stores.xbox.XboxStore
import dev.droidtop.stores.zoom.ZoomStore

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
        StoreLibraries.register(HumbleStore())
        StoreLibraries.register(IndiegalaStore())
        StoreLibraries.register(BattleNetStore())
        StoreLibraries.register(EAStore())
        StoreLibraries.register(UbisoftStore())
        StoreLibraries.register(XboxStore())
        StoreLibraries.register(LegacyStore())
        StoreLibraries.register(ZoomStore())
    }

    /**
     * Offers the Steam account to plugins that hold `steam.account` (docs/plugin-api.md 3 G8, Droidtop/tracker#379):
     * the broker's `steam.*` ops run on the registered Steam store's session ([SteamPluginAccount]).
     */
    fun offerSteamToPlugins(context: android.content.Context) {
        val steam = StoreLibraries.byId("steam") as? SteamStore ?: return
        dev.droidtop.pluginhost.SteamAccounts.provider = dev.droidtop.stores.steam.SteamPluginAccount(context.applicationContext, steam)
    }
}
