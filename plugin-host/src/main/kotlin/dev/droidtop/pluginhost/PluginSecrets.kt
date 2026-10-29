package dev.droidtop.pluginhost

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * droidtop's sheet for a plugin's `secret` facet (docs/plugin-api.md 1.10): the value the plugin gave when the person
 * pressed Show, drawn by droidtop with Copy, for as long as the sheet is open. Nothing here keeps it: closing the sheet
 * drops the only reference, and it is never put in a message, a notice, a log, the activity log or a backup.
 */
object PluginSecrets {
    /** What the sheet shows. Not a data class, so the value never ends up in a `toString`. */
    class Shown(val pluginLabel: String, val label: String, val value: String)

    private val _shown = MutableStateFlow<Shown?>(null)
    val shown: StateFlow<Shown?> = _shown

    @Volatile private var surface: (() -> Boolean)? = null

    /** :app sets this at start: opens the sheet's window, which reads [shown]; false when it could not be opened. */
    fun setSurface(open: (() -> Boolean)?) {
        surface = open
    }

    /** Shows [value] on droidtop's sheet; false when nothing can show it, and then nothing is kept. */
    fun show(pluginLabel: String, label: String, value: String): Boolean {
        val open = surface ?: return false
        val shown = Shown(pluginLabel, label, value)
        _shown.value = shown
        val opened = runCatching { open() }.getOrDefault(false)
        if (!opened) close(shown)
        return opened
    }

    /** The sheet was closed: the value is let go. */
    fun close(shown: Shown) {
        _shown.compareAndSet(shown, null)
    }
}
