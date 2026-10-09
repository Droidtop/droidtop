package dev.droidtop.pluginhost

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * What a plugin's panel declares it does on the companion screen (docs/plugin-api.md 3 C15, Droidtop/tracker#414,
 * slice C9): a `companion` list on its `ui.panel` entry, read from the manifest alone and shown on one line beside the
 * panel, so a person sees it before opening it.
 * - `keep_on`: the companion screen stays on while the panel shows (a chat, a stream's controls).
 * - `recording`: the plugin may raise droidtop's Recording state ([PluginRecording]) through `companion.recording`.
 * - `game`: the panel has rows for the running game, drawn on the companion's Game tab.
 */
object CompanionAbilities {
    const val KEEP_ON = "keep_on"
    const val RECORDING = "recording"
    const val GAME = "game"

    /** In the order the line says them. Unknown names are dropped, so a newer plugin's ability does nothing here. */
    val KNOWN: List<String> = listOf(GAME, RECORDING, KEEP_ON)

    fun of(entry: ProvidedPoint?): Set<String> {
        val array = entry?.let { runCatching { JSONObject(it.extra).optJSONArray("companion") }.getOrNull() } ?: return emptySet()
        return buildSet { for (i in 0 until array.length()) array.optString(i).takeIf { it in KNOWN }?.let(::add) }
    }

    /**
     * The apps a panel's Game rows are for (`"gamePackages": [...]` on its `ui.panel` entry): the Game tab asks the panel
     * only while one of them runs the game. Empty: every game.
     */
    fun gamePackages(entry: ProvidedPoint?): Set<String> {
        val array = entry?.let { runCatching { JSONObject(it.extra).optJSONArray("gamePackages") }.getOrNull() } ?: return emptySet()
        return buildSet { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
    }

    /** Whether a panel's Game rows apply while [runningPackage] runs the game. Pure. */
    fun gameRowsFor(entry: ProvidedPoint?, runningPackage: String?): Boolean =
        GAME in of(entry) && gamePackages(entry).let { it.isEmpty() || runningPackage in it }

    /** Whether [manifest]'s panel declares [ability]. */
    fun declares(manifest: PluginManifest, ability: String): Boolean =
        manifest.v2.provides.any { it.point == PANEL_POINT && ability in of(it) }

    /** The abilities line: plain words for each declared ability and the tiles a person can pin; null when there is nothing to say. */
    fun line(abilities: Set<String>, tiles: Int): String? = buildList {
        if (GAME in abilities) add("Rows on Game")
        if (RECORDING in abilities) add("Shows when it records")
        if (KEEP_ON in abilities) add("Keeps this screen on")
        if (tiles == 1) add("1 tile to pin") else if (tiles > 1) add("$tiles tiles to pin")
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    private const val PANEL_POINT = "ui.panel"
}

/**
 * droidtop's Recording state (docs/SPEC.md "The companion's tabs"): a plugin that declares the `recording` ability
 * (windowcast, or another recorder) says it started or stopped through the host API `companion.recording`, and the
 * companion's status line shows "Recording" with a timer and speaks it. droidtop records nothing itself. One state per
 * plugin; the newest recording is the one shown.
 */
object PluginRecording {
    data class Recording(val pluginId: String, val label: String, val sinceMs: Long)

    private val state = MutableStateFlow<Map<String, Recording>>(emptyMap())
    val all: StateFlow<Map<String, Recording>> = state

    /** The recording the status line shows: the most recent start. */
    fun shown(all: Map<String, Recording>): Recording? = all.values.maxByOrNull { it.sinceMs }

    /**
     * A plugin's report: [on] starts (or restarts, with [sinceMs] when the plugin says when it began, never in the
     * future) or stops its recording. Returns whether the state changed.
     */
    fun report(pluginId: String, label: String, on: Boolean, sinceMs: Long?, nowMs: Long): Boolean {
        val before = state.value
        state.value = if (on) {
            before + (pluginId to Recording(pluginId, label, (sinceMs ?: nowMs).coerceAtMost(nowMs)))
        } else {
            before - pluginId
        }
        return state.value != before
    }

    /** A plugin stopped running or was removed: whatever it was recording is no longer shown. */
    fun clear(pluginId: String) {
        state.value = state.value - pluginId
    }

    /** "Recording 1:05" or "Recording 1:02:05" for a recording that started [sinceMs]. */
    fun timer(sinceMs: Long, nowMs: Long): String {
        val total = ((nowMs - sinceMs) / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "Recording %d:%02d:%02d".format(h, m, s) else "Recording %d:%02d".format(m, s)
    }
}
