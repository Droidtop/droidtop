package dev.droidtop.pluginhost

import org.json.JSONObject

/**
 * The desktop's clipboard as droidtop's clipboard bridge holds it (docs/SPEC.md 6d): the text last copied on either
 * side, Android or the container. Set by the bridge while it runs ([DesktopPluginBridge.clipboard]); its calls are made
 * off the main thread and return at once.
 */
interface DesktopClipboard {
    /** The text on the clipboard; null with none, or when Android marked it sensitive (a password manager's copy). */
    fun text(): String?

    /** Puts [text] on both sides' clipboards, through the bridge's one echo check; false when it was not taken. */
    fun setText(text: String): Boolean
}

/**
 * The clipboard bridge for plugins (docs/plugin-api.md 1.12, Droidtop/tracker#221): `clipboard.get_text` under
 * `clipboard.read` (dangerous, so every read is in the activity log; and only during a call the person started, as
 * catalogue D6 and threat T5 decided, so a plugin cannot watch what is copied) and `clipboard.set_text` under `clipboard.write`
 * (always in the activity log too), on the desktop's clipboard through the bridge's own sync, so a plugin cannot set
 * up a loop with the container and is held to the bridge's size cap. With no desktop running both are UNSUPPORTED.
 */
object HostClipboardApis {
    const val READ = "clipboard.read"
    const val WRITE = "clipboard.write"

    /** The bridge's cap (host-bridge's ClipboardSync.MAX_BYTES): longer text is refused, never cut. */
    const val MAX_BYTES = 1 shl 20

    val ops: List<HostOp> = listOf(
        HostOp("clipboard", "get_text", permission = READ, userOnly = true) { env, _, _ ->
            JSONObject().put("text", clipboard(env).text() ?: JSONObject.NULL)
        },
        HostOp(
            "clipboard", "set_text", permission = WRITE, alwaysAudit = true,
            target = { "${it.optString("text").length} characters" },
        ) { env, _, args ->
            val text = args.optString("text")
            if (text.isEmpty()) throw BrokerException(PluginErrorCode.INVALID_ARGS, "text is required")
            if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
                throw BrokerException(PluginErrorCode.INVALID_ARGS, "the clipboard takes at most ${MAX_BYTES / 1024} KiB of text")
            }
            JSONObject().put("set", clipboard(env).setText(text))
        },
    )

    private fun clipboard(env: BrokerEnvironment): DesktopClipboard =
        env.desktopClipboard() ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "the clipboard bridge runs with Desktop mode, and it is not running")
}
