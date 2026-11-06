package dev.droidtop.runtime.systemstatus

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.library.settings.words
import dev.droidtop.runtime.R

/**
 * What Do Not Disturb lets through (Droidtop/tracker#786 S8): Android's priority categories, read and written with
 * the notification-policy access the Do Not Disturb row already asks for. Who may call or message (the senders) and
 * the visual effects are kept as they are. Reads and writes go to the system server: off the main thread.
 */
object DndCategories {
    /** One category: its row id, title, `NotificationManager.Policy` flag and the Android version that has it. */
    data class Category(val id: String, val title: Int, val flag: Int, val minSdk: Int)

    private const val PREFIX = "pref_dnd_allows_"

    // The flags' values are Android's own (API 23 to 30), written out so the table stays pure and testable.
    val ALL = listOf(
        Category(PREFIX + "alarms", R.string.dnd_allows_alarms, 32, 28),
        Category(PREFIX + "media", R.string.dnd_allows_media, 64, 28),
        Category(PREFIX + "system", R.string.dnd_allows_touch_sounds, 128, 28),
        Category(PREFIX + "reminders", R.string.dnd_allows_reminders, 1, 23),
        Category(PREFIX + "events", R.string.dnd_allows_events, 2, 23),
        Category(PREFIX + "conversations", R.string.dnd_allows_conversations, 256, 30),
        Category(PREFIX + "messages", R.string.dnd_allows_messages, 4, 23),
        Category(PREFIX + "calls", R.string.dnd_allows_calls, 8, 23),
        Category(PREFIX + "repeat_callers", R.string.dnd_allows_repeat_callers, 16, 23),
    )

    /** The categories this Android version has. Pure. */
    fun offered(sdk: Int): List<Category> = ALL.filter { sdk >= it.minSdk }

    /** [categories] with [flag] turned [on] or off. Pure. */
    fun with(categories: Int, flag: Int, on: Boolean): Int = if (on) categories or flag else categories and flag.inv()

    /** One row per category, or none without the access (the Do Not Disturb row then offers Allow). */
    fun items(context: Context): List<CatalogItem> {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return emptyList()
        if (!nm.isNotificationPolicyAccessGranted) return emptyList()
        val current = runCatching { nm.notificationPolicy.priorityCategories }.getOrNull() ?: return emptyList()
        return offered(Build.VERSION.SDK_INT).map { category ->
            ToggleItem(
                id = category.id,
                title = words(category.title),
                current = current and category.flag != 0,
                onToggle = { ctx, on -> set(ctx, category.flag, on) },
            )
        }
    }

    /** Turns one category on or off, keeping the senders and visual effects; false without the access. */
    fun set(context: Context, flag: Int, on: Boolean): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.isNotificationPolicyAccessGranted) return false
        return runCatching {
            val old = nm.notificationPolicy
            val categories = with(old.priorityCategories, flag, on)
            nm.notificationPolicy = when {
                Build.VERSION.SDK_INT >= 30 -> NotificationManager.Policy(
                    categories, old.priorityCallSenders, old.priorityMessageSenders,
                    old.suppressedVisualEffects, old.priorityConversationSenders,
                )
                Build.VERSION.SDK_INT >= 28 -> NotificationManager.Policy(
                    categories, old.priorityCallSenders, old.priorityMessageSenders, old.suppressedVisualEffects,
                )
                // Android 8 has no constructor that takes the visual effects; it keeps them unset, as before.
                else -> NotificationManager.Policy(categories, old.priorityCallSenders, old.priorityMessageSenders)
            }
        }.isSuccess
    }
}
