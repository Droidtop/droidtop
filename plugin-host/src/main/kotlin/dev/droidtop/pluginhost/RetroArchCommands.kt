package dev.droidtop.pluginhost

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * RetroArch's network commands (docs/plugin-api.md 3 B, `retroarch.command`; Droidtop/tracker#414 slice C10): one
 * line of text in a UDP datagram to RetroArch on this device, port 55355 (`DEFAULT_NETWORK_CMD_PORT`, command.h), which
 * RetroArch reads only while its `network_cmd_enable` is on. A contained plugin has no network of its own, so droidtop
 * sends the line for it, to the loopback address only, and only commands from [ALLOWED]: RetroArch's own hotkey names
 * (command.h `map[]`) that act on the running game. Quit is not one: droidtop's own Quit ends a game.
 */
object RetroArchCommands {
    const val PORT = 55355

    /** How long droidtop waits for RetroArch's answer to `GET_STATUS` after a command. */
    const val STATUS_WAIT_MS = 500L

    val ALLOWED: Set<String> = setOf(
        "SAVE_STATE", "LOAD_STATE", "STATE_SLOT_PLUS", "STATE_SLOT_MINUS",
        "FAST_FORWARD", "FAST_FORWARD_HOLD", "SLOWMOTION", "REWIND", "PAUSE_TOGGLE", "FRAMEADVANCE", "RESET",
        "SHADER_TOGGLE", "SHADER_NEXT", "SHADER_PREV",
        "FPS_TOGGLE", "STATISTICS_TOGGLE", "SCREENSHOT", "MENU_TOGGLE", "MUTE", "VOLUME_UP", "VOLUME_DOWN",
        "DISK_EJECT_TOGGLE", "DISK_NEXT", "DISK_PREV", "CHEAT_TOGGLE",
    )

    /** What `GET_STATUS` said: PLAYING or PAUSED with the core's system and the content's name, or CONTENTLESS. */
    data class Status(val state: String, val system: String?, val content: String?)

    /**
     * RetroArch's `GET_STATUS` reply (command.c `command_get_status`): `GET_STATUS PLAYING snes,Game` with a newline,
     * `GET_STATUS CONTENTLESS`, or `GET_STATUS ERROR`. Null for anything else. Pure.
     */
    fun parseStatus(reply: String?): Status? {
        val text = reply?.trim()?.takeIf { it.startsWith("GET_STATUS ") }?.removePrefix("GET_STATUS ") ?: return null
        val state = text.substringBefore(' ')
        if (state.isEmpty()) return null
        val rest = text.substringAfter(' ', "").takeIf { it.isNotEmpty() }
        return Status(state, rest?.substringBefore(','), rest?.substringAfter(',', "")?.takeIf { it.isNotEmpty() })
    }

    /** Sends [line] to RetroArch on this device; with [replyMs] above zero, waits that long for one answer. Blocks: never on the main thread. */
    fun send(line: String, replyMs: Long, port: Int = PORT): String? = DatagramSocket().use { socket ->
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
        if (replyMs <= 0) return@use null
        socket.soTimeout = replyMs.toInt()
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            socket.receive(packet)
            String(packet.data, 0, packet.length, Charsets.UTF_8)
        } catch (_: SocketTimeoutException) {
            null
        }
    }
}
