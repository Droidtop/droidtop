package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * One Desktop window as a plugin sees it (docs/plugin-api.md 1.12, catalogue E5): [id] is droidtop's own id for it
 * (host-bridge's toplevel id, stable while the window is open), never the compositor's; [output] is the compositor's
 * name for the screen it is on, "" when none.
 */
data class DesktopWindow(val id: Long, val appId: String, val title: String, val output: String, val focused: Boolean)

/**
 * What the running Desktop session lets droidtop do with its windows for plugins. The session sets it when its
 * compositor connects and clears it when it ends ([DesktopPluginBridge]); calls are made off the main thread.
 */
interface DesktopWindowControl {
    fun windows(): List<DesktopWindow>

    /** Raises and focuses the window; false when it is gone. */
    fun focus(id: Long): Boolean

    /** Asks the window to close itself (the app may ask to save first); false when it is gone. */
    fun close(id: Long): Boolean
}

/** The one place the Desktop session hands its windows to the plugin API. Null while no desktop runs. */
object DesktopPluginBridge {
    @Volatile
    var windows: DesktopWindowControl? = null
}

/**
 * E5, Windowing (docs/plugin-api.md 1.12, Droidtop/tracker#221): a plugin lists, focuses and closes Desktop windows,
 * served from host-bridge's window list, nothing in the container. `windows.list` needs `windows.read`; `windows.focus`
 * and `windows.close` need `windows.control`. Both are dangerous, so every call is in the activity log. A permission
 * may be narrowed to apps (`"apps": ["org.mozilla.firefox"]`): droidtop then shows and acts on those apps' windows only.
 */
object HostWindowsApis {
    const val READ = "windows.read"
    const val CONTROL = "windows.control"

    val ops: List<HostOp> = listOf(
        HostOp("windows", "list", permission = READ) { env, record, _ ->
            val shown = control(env).windows().filter { allowed(record, READ, it.appId) }
            JSONObject().put("windows", JSONArray(shown.map(::toJson)))
        },
        HostOp("windows", "focus", permission = CONTROL, target = { it.optLong("id", -1L).toString() }) { env, record, args ->
            act(env, record, args) { control, id -> control.focus(id) }
        },
        HostOp("windows", "close", permission = CONTROL, target = { it.optLong("id", -1L).toString() }) { env, record, args ->
            act(env, record, args) { control, id -> control.close(id) }
        },
    )

    private fun control(env: BrokerEnvironment): DesktopWindowControl =
        env.desktopWindows() ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "Desktop mode is not running")

    /** A window the plugin may act on, by id: one it could see under `windows.control`'s apps, or NOT_FOUND. */
    private fun act(env: BrokerEnvironment, record: PluginRecord, args: JSONObject, run: (DesktopWindowControl, Long) -> Boolean): JSONObject {
        val id = args.optLong("id", -1L).takeIf { it >= 0 } ?: throw BrokerException(PluginErrorCode.INVALID_ARGS, "id must be a window id from windows.list")
        val control = control(env)
        val window = control.windows().firstOrNull { it.id == id }?.takeIf { allowed(record, CONTROL, it.appId) }
            ?: throw BrokerException(PluginErrorCode.NOT_FOUND, "no window $id this plugin may act on")
        return JSONObject().put("done", run(control, window.id))
    }

    /** `{id, appId, title, output, focused}`. Pure. */
    fun toJson(window: DesktopWindow): JSONObject = JSONObject()
        .put("id", window.id)
        .put("appId", window.appId)
        .put("title", window.title)
        .put("output", window.output)
        .put("focused", window.focused)

    /** True when [record]'s [permission] reaches [appId]: no `apps` list, or `*`, reaches every app. */
    fun allowed(record: PluginRecord, permission: String, appId: String): Boolean {
        val declared = record.manifest.v2.permissions.firstOrNull { it.id == permission } ?: return false
        return appsOf(declared)?.let { "*" in it || appId in it } ?: true
    }

    /** The apps [declared] is narrowed to, or null when it is not narrowed. Pure. */
    fun appsOf(declared: DeclaredPermission): Set<String>? {
        val list = runCatching { JSONObject(declared.extra).optJSONArray("apps") }.getOrNull() ?: return null
        return buildSet { for (i in 0 until list.length()) list.optString(i).trim().takeIf { it.isNotEmpty() }?.let { add(it) } }
    }
}

/**
 * The window events (`window.opened`, `window.closed`, `window.focused`, docs/plugin-api.md 1.12) between two of the
 * session's window lists. A title change alone is no event. Pure.
 */
object WindowEvents {
    data class Event(val event: PluginEvent, val window: DesktopWindow)

    fun between(before: List<DesktopWindow>, after: List<DesktopWindow>): List<Event> {
        val old = before.associateBy { it.id }
        val now = after.associateBy { it.id }
        return buildList {
            before.filter { it.id !in now }.forEach { add(Event(PluginEvent.WINDOW_CLOSED, it)) }
            after.filter { it.id !in old }.forEach { add(Event(PluginEvent.WINDOW_OPENED, it)) }
            after.filter { it.focused && old[it.id]?.focused != true }.forEach { add(Event(PluginEvent.WINDOW_FOCUSED, it)) }
        }
    }

    /** An event's args, as a plugin reads them ([PluginArgs]: plain strings). */
    fun args(window: DesktopWindow): Map<String, String> = mapOf(
        "id" to window.id.toString(),
        "appId" to window.appId,
        "title" to window.title,
        "output" to window.output,
    )
}
