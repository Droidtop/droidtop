package dev.droidtop.library.saves

import dev.droidtop.library.consoles.ConfigFormat
import org.json.JSONArray
import org.json.JSONObject

/**
 * An engine's save rule: the optional `saves` object of a droidtop-platforms engine row (docs/FORMAT.md "Save rules").
 * [beside] is under the game folder; [data] is the engine's per-user system location on each OS (under Enginehost,
 * the engine family's save root); [user] is the game's own path below it, with `{fact}` placeholders the reader named
 * in [facts] fills from the game's files; [hostUser] replaces [user] under Enginehost where it differs. [source] is
 * what the rule was read from: the data format refuses a rule with none.
 */
data class EngineSaves(
    val beside: List<String>,
    val data: Map<String, String>,
    val user: List<String>,
    val hostUser: List<String>,
    val facts: Map<String, String>,
    val source: String,
) {
    companion object {
        const val VERSION = 1

        /** The rule in [obj], or null when there is none, its version is not one this app knows, or it is malformed. */
        fun parse(obj: JSONObject?): EngineSaves? {
            if (obj == null || obj.optInt("v") != VERSION) return null
            return runCatching {
                EngineSaves(
                    beside = strings(obj.optJSONArray("beside")),
                    data = obj.optJSONObject("data")?.let { d -> d.keys().asSequence().associateWith { d.getString(it) } }.orEmpty(),
                    user = strings(obj.optJSONArray("user")),
                    hostUser = strings(obj.optJSONArray("hostUser")),
                    facts = obj.optJSONObject("facts")?.let { d -> d.keys().asSequence().associateWith { d.getString(it) } }.orEmpty(),
                    source = obj.getString("source"),
                )
            }.getOrNull()
        }
    }
}

/**
 * What one emulator, or one of its systems, saves: [patterns] the game's saves, [states] its save states (looked for
 * but not counted as saves unless the person turns that on), [cards] memory cards shared by every game (a set of
 * their own).
 */
data class EmulatorSavePatterns(val patterns: List<String>, val states: List<String>, val cards: List<String>)

/**
 * An emulator's save rule: the optional `saves` object of a droidtop-platforms players row (docs/FORMAT.md "Save
 * rules"). `<emuSaves>` is every folder in [folders] plus the value of [folderKey] in [configFile] when it sets one;
 * `<emuStates>` is the same for [statesFolders]. [systems] replaces the emulator-wide patterns for one system.
 */
data class EmulatorSaves(
    val folders: List<String>,
    val folderKey: String?,
    val statesFolders: List<String>,
    val statesFolderKey: String?,
    val configFile: String?,
    val configFormat: ConfigFormat,
    val all: EmulatorSavePatterns,
    val systems: Map<String, EmulatorSavePatterns>,
    val source: String,
) {
    /** The patterns that apply to [systemId]. */
    fun forSystem(systemId: String?): EmulatorSavePatterns = systems[systemId] ?: all

    companion object {
        const val VERSION = 1

        /** The rule in [obj] for the package [pkg] (`{pkg}` in a path), or null as for [EngineSaves.parse]. */
        fun parse(obj: JSONObject?, pkg: String): EmulatorSaves? {
            if (obj == null || obj.optInt("v") != VERSION) return null
            return runCatching {
                fun path(raw: String) = raw.replace("{pkg}", pkg)
                fun patternsOf(o: JSONObject) = EmulatorSavePatterns(
                    patterns = strings(o.optJSONArray("patterns")),
                    states = strings(o.optJSONArray("states")),
                    cards = strings(o.optJSONArray("cards")),
                )
                val config = obj.optJSONObject("config")
                val systems = obj.optJSONObject("systems")
                EmulatorSaves(
                    folders = strings(obj.optJSONArray("folders")).map(::path),
                    folderKey = obj.optString("folderKey").takeIf { it.isNotEmpty() },
                    statesFolders = strings(obj.optJSONArray("statesFolders")).map(::path),
                    statesFolderKey = obj.optString("statesFolderKey").takeIf { it.isNotEmpty() },
                    configFile = config?.getString("file")?.let(::path),
                    configFormat = when (config?.optString("format")) {
                        "ini" -> ConfigFormat.INI
                        else -> ConfigFormat.KEY_VALUE
                    },
                    all = patternsOf(obj),
                    systems = systems?.let { s -> s.keys().asSequence().associateWith { patternsOf(s.getJSONObject(it)) } }.orEmpty(),
                    source = obj.getString("source"),
                )
            }.getOrNull()
        }
    }
}

private fun strings(array: JSONArray?): List<String> = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }
