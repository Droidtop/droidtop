package dev.droidtop.library.settings

import android.content.Context

/**
 * The Standard launcher's half of plugins in every mode (docs/plugin-api.md 1.9). `:shell-default` cannot depend on
 * the library that knows plugins, so `:app` installs a [Provider] at start and the launcher asks it here: the home
 * screen's long-press menu gets "Plugins", the same list of panels Gaming's Quick Menu has. What the launcher reads is
 * answered from memory, because the menu is built on the main thread; the provider refreshes off it.
 */
object PluginShellHooks {
    /** One entry of the home screen's menu: its words, and what pressing it does. */
    class MenuEntry(val label: String, val run: (Context) -> Unit)

    interface Provider {
        fun homeMenu(context: Context): List<MenuEntry>
    }

    @Volatile var provider: Provider? = null

    @JvmStatic fun homeMenu(context: Context): List<MenuEntry> = provider?.homeMenu(context).orEmpty()

    /** Runs an entry from Java. */
    @JvmStatic fun run(entry: MenuEntry, context: Context) {
        entry.run(context)
    }
}
