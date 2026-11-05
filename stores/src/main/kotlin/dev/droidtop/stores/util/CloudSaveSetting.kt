package dev.droidtop.stores.util

import android.content.Context
import dev.droidtop.library.settings.ToggleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A store's Cloud saves switch on its page in the Stores place (docs/SPEC.md 7g, "Stores", Droidtop/tracker#224): on
 * unless the person turned it off, kept in the store's own preferences file (`<store>_prefs`, `cloud_saves`, where
 * Steam always kept it). Off, a launch and an exit leave the saves alone; "Sync cloud saves" on a game's menu still
 * syncs, because the person asked. Preferences reads: never on the main thread.
 */
object CloudSaveSetting {
    private const val KEY = "cloud_saves"

    private fun prefs(context: Context, store: String) = context.applicationContext.getSharedPreferences("${store}_prefs", Context.MODE_PRIVATE)

    fun on(context: Context, store: String): Boolean = prefs(context, store).getBoolean(KEY, true)

    fun set(context: Context, store: String, on: Boolean) {
        prefs(context, store).edit().putBoolean(KEY, on).apply()
    }

    /** The row's id: `<store>_cloud_saves`. */
    fun id(store: String): String = "${store}_cloud_saves"

    /** The switch, for the store's [dev.droidtop.library.stores.StoreLibrary.settingsItems]. */
    fun item(context: Context, store: String, cloudLabel: String): ToggleItem = ToggleItem(
        id = id(store),
        title = "Cloud saves",
        subtitle = "Syncs a game's saves with $cloudLabel before it starts and after it ends",
        current = on(context, store),
        onToggle = { ctx, value -> withContext(Dispatchers.IO) { set(ctx, store, value) } },
    )
}
