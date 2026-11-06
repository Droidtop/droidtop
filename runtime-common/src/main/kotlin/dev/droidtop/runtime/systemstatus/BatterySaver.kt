package dev.droidtop.runtime.systemstatus

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.library.settings.words
import dev.droidtop.runtime.R
import dev.droidtop.runtime.tasks.RiskyActions
import dev.droidtop.runtime.tasks.RiskyClass
import dev.droidtop.runtime.tasks.RiskyPrompts
import dev.droidtop.runtime.tasks.TaskManager

/**
 * The one battery saver (Droidtop/tracker#786 S10): a switch where droidtop may change it (the helper app and Risky
 * actions > "Change system settings": `settings put global low_power`, then read back), else Android's own Battery
 * Saver screen, saying which switch would let droidtop do it. Every place that offers battery saver goes through here.
 */
object BatterySaver {
    const val ID = "pref_battery_saver"

    /** Whether droidtop may switch it itself. Asks the helper: off the main thread. */
    fun canWrite(): Boolean =
        RiskyActions.allows(RiskyClass.SYSTEM_SETTINGS) && runCatching { TaskManager.shell.capabilities().shellCommand }.getOrDefault(false)

    /** Battery saver as the row: a switch, or the way to Android's screen. Reads state: off the main thread. */
    fun item(context: Context): CatalogItem {
        val on = SystemControls.powerSaveOn(context)
        if (canWrite()) {
            return ToggleItem(
                id = ID,
                title = words(R.string.battery_saver),
                subtitle = words(R.string.battery_saver_saves_power),
                current = on,
                // Never a switch that silently does nothing (tracker#581): when Android did not follow, its screen opens.
                onToggle = { ctx, next ->
                    val done = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { set(ctx, next) }
                    if (!done) SettingsLaunch.start(ctx, SystemControls.batterySaverSettingsIntent())
                },
            )
        }
        val helper = runCatching { TaskManager.shell.capabilities().shellCommand }.getOrDefault(false)
        return ActionItem(
            id = ID,
            title = words(R.string.battery_saver),
            subtitle = words(R.string.gaming_opens_the_system_battery_saver_screen) +
                if (helper) ". " + RiskyPrompts.turnOnHint(RiskyClass.SYSTEM_SETTINGS) else "",
            value = if (on) "On" else "Off",
            run = { ctx -> SettingsLaunch.start(ctx, SystemControls.batterySaverSettingsIntent()) },
        )
    }

    /**
     * Turns battery saver [on] or off through the helper and waits up to [settleMs] for Android to say so. True only
     * when it did; false sends the caller to Android's screen. Blocks: off the main thread.
     */
    fun set(context: Context, on: Boolean, settleMs: Long = 2_000): Boolean {
        if (SystemControls.powerSaveOn(context) == on) return true
        if (!canWrite()) return false
        val out = TaskManager.shell.exec(listOf("settings", "put", "global", "low_power", if (on) "1" else "0"))
        if (out?.exit != 0) return false
        val until = System.currentTimeMillis() + settleMs
        while (System.currentTimeMillis() < until) {
            if (SystemControls.powerSaveOn(context) == on) return true
            Thread.sleep(100)
        }
        return false
    }

    /** Turns it on from a shortcut (Home's low-battery line): in place where droidtop may, else Android's screen. */
    fun turnOnOrOpen(context: Context): Boolean {
        if (set(context, true)) return true
        SettingsLaunch.start(context, SystemControls.batterySaverSettingsIntent())
        return false
    }
}
