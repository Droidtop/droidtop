package dev.droidtop.app

import android.content.Context
import dev.droidtop.hostbridge.DesktopWindows
import dev.droidtop.hostbridge.HostBridge
import dev.droidtop.hostbridge.Toplevel
import dev.droidtop.library.integrations.notifyWindowEvents
import dev.droidtop.pluginhost.DesktopPluginBridge
import dev.droidtop.pluginhost.DesktopWindow
import dev.droidtop.pluginhost.DesktopWindowControl
import dev.droidtop.pluginhost.WindowEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The Desktop session's windows for plugins (docs/plugin-api.md 1.12, Droidtop/tracker#221): the one place a session
 * hands its windows to the plugin API ([DesktopPluginBridge]) and turns changes of its window list into the window
 * events. Everything comes from host-bridge's window list (wlr-foreign-toplevel-management) and the bar's own window
 * actions ([DesktopWindows]); nothing is started in the container, and nothing needs root.
 */
class DesktopPluginWindows(
    private val context: Context,
    private val bridge: HostBridge,
    private val actions: DesktopWindows,
) : DesktopWindowControl {
    private var watching: Job? = null

    override fun windows(): List<DesktopWindow> = bridge.toplevels().map(::toWindow)

    // Focus is the bar's own raise: a window the bar put away comes back first, as a tap on its row does.
    override fun focus(id: Long): Boolean {
        val toplevel = bridge.toplevels().firstOrNull { it.id == id } ?: return false
        runBlocking { actions.raise(toplevel) }
        return true
    }

    override fun close(id: Long): Boolean = bridge.closeToplevel(id)

    /** Publishes these windows to plugins and starts telling them of changes, on [scope] (the session's IO scope). */
    fun start(scope: CoroutineScope) {
        DesktopPluginBridge.windows = this
        watching = scope.launch {
            var last = windows()
            bridge.toplevelChanges.collect {
                val now = windows()
                notifyWindowEvents(context, WindowEvents.between(last, now))
                last = now
            }
        }
    }

    /** Takes these windows away from plugins: the session is ending. */
    fun stop() {
        watching?.cancel()
        watching = null
        if (DesktopPluginBridge.windows === this) DesktopPluginBridge.windows = null
    }

    private fun toWindow(toplevel: Toplevel) = DesktopWindow(toplevel.id, toplevel.appId, toplevel.title, toplevel.output, toplevel.activated)
}

/**
 * The clipboard bridge's text for plugins (docs/plugin-api.md 1.12, Droidtop/tracker#221): the one place a running
 * [dev.droidtop.hostbridge.ClipboardBridge] is handed to the plugin API, and its changes become `clipboard.changed`.
 * A plugin reads and sets the clipboard through the bridge's own sync, so it can never loop with the container.
 */
object DesktopPluginClipboard {
    /** Publishes [bridge] to plugins; call it after the bridge starts. */
    fun publish(context: Context, bridge: dev.droidtop.hostbridge.ClipboardBridge) {
        val app = context.applicationContext
        bridge.onChanged = { dev.droidtop.library.integrations.notifyClipboardChanged(app) }
        DesktopPluginBridge.clipboard = object : dev.droidtop.pluginhost.DesktopClipboard {
            override fun text(): String? = bridge.textForPlugins()

            override fun setText(text: String): Boolean = bridge.setFromPlugin(text)
        }
    }

    /** Takes the clipboard away from plugins; call it before the bridge stops. */
    fun withdraw(bridge: dev.droidtop.hostbridge.ClipboardBridge?) {
        bridge?.onChanged = null
        DesktopPluginBridge.clipboard = null
    }
}
