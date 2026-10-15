package dev.droidtop.library.saves

import dev.droidtop.library.consoles.ConfigText
import java.io.File

/** One place a game's saves may be, and the templates to look for inside it. */
data class SavePlace(val label: String, val patterns: List<String>, val roots: Map<String, List<File>>)

/**
 * Where to look for one game's saves on this device, worked out from its rule (docs/SPEC.md 7o "Saves"). Pure: the
 * facts, the Enginehost root and the Wine prefix come in as arguments, so what a rule means is tested without a
 * device. [unknown] is what the rule needs and this game does not give (a fact that cannot be read); [rule] is the
 * one line saying which rule it is.
 */
data class SavePlan(
    val rule: String,
    val places: List<SavePlace>,
    val unknown: List<String>,
    /** Save states, kept apart: looked for, never counted as the game's saves. */
    val states: List<SavePlace> = emptyList(),
    /** Memory cards shared by every game on the emulator: a set of their own. */
    val cards: List<SavePlace> = emptyList(),
    /** Folders the rule points at that droidtop is not allowed to list (another app's own folder). */
    val blocked: List<String> = emptyList(),
)

object SavePlans {
    /** Enginehost's name for a game's folder where it names one (enginehost SaveFolders.sanitize), so the same game finds the same folder. */
    fun hostFolderName(folderName: String): String {
        val cleaned = folderName.trim().map { if (it in "/\\:*?\"<>|\u0000" || it.code < 32) '_' else it }.joinToString("")
            .trimEnd('.', ' ')
        return cleaned.ifBlank { "game" }
    }

    /**
     * An engine game's plan. [engineRoot] is Enginehost's save root for the game's family (null when it has none on
     * this device); [wineRoots] is the Windows token folders of the game's Wine prefix (null when it has none);
     * [fact] reads one value out of the game's files by reader name.
     */
    fun engine(rule: EngineSaves, gameFolder: File, engineRoot: File?, wineRoots: Map<String, File>?, fact: (String) -> String?): SavePlan {
        val values = HashMap<String, String?>()
        for ((name, reader) in rule.facts) values[name] = fact(reader)
        values["game_folder"] = hostFolderName(gameFolder.name)
        val unknown = LinkedHashSet<String>()
        val places = ArrayList<SavePlace>()

        if (rule.beside.isNotEmpty()) {
            places += SavePlace("the game's folder", rule.beside.map { SaveTemplates.BASE + "/" + it }, mapOf(SaveTemplates.BASE to listOf(gameFolder)))
        }

        fun users(templates: List<String>, prefix: String): List<String> {
            val out = ArrayList<String>()
            val missing = ArrayList<String>()
            for (template in templates) {
                val filled = SaveTemplates.fill(template, values)
                if (filled == null) {
                    missing += SaveTemplates.placeholders(template)
                        .filter { values[it].isNullOrBlank() }
                        .mapNotNull { rule.facts[it]?.let(::factWords) }
                } else {
                    out += "$prefix/$filled"
                }
            }
            // Alternatives (a custom folder or the project's name): only when none can be filled is a name missing.
            if (out.isEmpty()) unknown += missing.ifEmpty { listOf("its save folder's name") }
            return out
        }

        if (engineRoot != null) {
            val templates = rule.hostUser.ifEmpty { rule.user }
            if (templates.isNotEmpty() && (rule.hostUser.isNotEmpty() || rule.data.isNotEmpty())) {
                val patterns = users(templates, SaveTemplates.ENGINE_DATA)
                if (patterns.isNotEmpty()) places += SavePlace("Enginehost's save folder", patterns, mapOf(SaveTemplates.ENGINE_DATA to listOf(engineRoot)))
            } else if (rule.data.isNotEmpty() && rule.user.isEmpty()) {
                unknown += "the folder the game names for its saves"
            }
        }

        val windows = rule.data["windows"]
        if (wineRoots != null && windows != null && rule.user.isNotEmpty()) {
            val head = SaveTemplates.split(windows)
            if (head != null) {
                val patterns = users(rule.user, windows)
                if (patterns.isNotEmpty()) places += SavePlace("its Windows setup", patterns, wineRoots.mapValues { listOf(it.value) })
            }
        }

        val label = rule.source.substringBefore(';').substringBefore(" (").take(80)
        return SavePlan(rule = label, places = places, unknown = unknown.toList())
    }

    private fun factWords(reader: String): String = when (reader) {
        SaveFacts.RENPY_SAVE_DIRECTORY -> "its Ren'Py save directory name"
        SaveFacts.GODOT_NAME, SaveFacts.GODOT_CUSTOM_DIR -> "the project's name"
        SaveFacts.LOVE_IDENTITY -> "its LOVE identity"
        else -> "a name in the game's files"
    }

    /**
     * An emulator game's plan. [rom] is the game file; [configText] the emulator's own config when droidtop could
     * read it (its folder keys name the real folders); [isListable] says whether a folder can be listed at all
     * (another app's own folder cannot, without all-files access).
     */
    fun emulator(rule: EmulatorSaves, systemId: String?, rom: File, configText: String?, isListable: (File) -> Boolean = { true }): SavePlan {
        fun configured(key: String?): File? {
            if (key == null || configText == null) return null
            val value = ConfigText.get(configText, rule.configFormat, null, key)?.trim()?.trimEnd('/') ?: return null
            return value.takeIf { it.startsWith("/") && it != "default" }?.let(::File)
        }
        val saveFolders = (listOfNotNull(configured(rule.folderKey)) + rule.folders.map(::File)).distinct()
        val stateFolders = (listOfNotNull(configured(rule.statesFolderKey)) + rule.statesFolders.map(::File)).distinct()
        val blocked = (saveFolders + stateFolders).filter { !isListable(it) && it.path.contains("/Android/data/") }
        val romFolder = rom.parentFile
        val values = mapOf("rom_stem" to rom.nameWithoutExtension, "rom_name" to rom.name)

        fun place(label: String, templates: List<String>, folders: List<File>, token: String): SavePlace? {
            val patterns = templates.mapNotNull { template ->
                val head = template.replaceFirst("{rom_dir}/", SaveTemplates.BASE + "/")
                SaveTemplates.fill(head, values)
            }
            if (patterns.isEmpty()) return null
            val roots = HashMap<String, List<File>>()
            roots[token] = folders
            romFolder?.let { roots[SaveTemplates.BASE] = listOf(it) }
            return SavePlace(label, patterns, roots)
        }

        val set = rule.forSystem(systemId)
        val places = listOfNotNull(place("the emulator's save folder", set.patterns, saveFolders, SaveTemplates.EMU_SAVES))
        val states = listOfNotNull(place("the emulator's save-state folder", set.states, stateFolders, SaveTemplates.EMU_STATES))
        val cards = listOfNotNull(
            place("the emulator's memory cards", set.cards, saveFolders, SaveTemplates.EMU_SAVES),
        )
        return SavePlan(
            rule = rule.source.substringBefore(':').take(60) + " save rule",
            places = places,
            unknown = emptyList(),
            states = states,
            cards = cards,
            blocked = blocked.map { it.path },
        )
    }

    /** A plan for a rule that is only templates, the way a paired computer states a game's saves (its `save_spec` answer). */
    fun templates(rule: String, patterns: List<String>, gameFolder: File?, wineRoots: Map<String, File>?): SavePlan {
        val roots = HashMap<String, List<File>>()
        wineRoots?.forEach { (token, folder) -> roots[token] = listOf(folder) }
        gameFolder?.let { roots[SaveTemplates.BASE] = listOf(it) }
        val usable = patterns.filter { SaveTemplates.split(it) != null }
        // A rule in Windows places cannot be searched on a device where the game has no Windows setup.
        val needsPrefix = wineRoots == null && usable.any { SaveTemplates.split(it)?.token != SaveTemplates.BASE }
        val unknown = if (needsPrefix) listOf("which Windows setup the game runs in (it has none on this device yet)") else emptyList()
        val places = if (usable.isEmpty()) emptyList() else listOf(SavePlace("its Windows setup and game folder", usable, roots))
        return SavePlan(rule, places, unknown)
    }

    /** The same layout as the agent core's `prefix_roots` and `SaveLayout.windowsDirs`. */
    fun prefixRoots(prefix: dev.droidtop.library.stores.WinePrefixLocation): Map<String, File> {
        val driveC = File(prefix.prefixDir, "drive_c")
        val home = File(File(driveC, "users"), prefix.user)
        return mapOf(
            "<home>" to home,
            "<winAppData>" to File(home, "AppData/Roaming"),
            "<winLocalAppData>" to File(home, "AppData/Local"),
            "<winLocalAppDataLow>" to File(home, "AppData/LocalLow"),
            "<winDocuments>" to File(home, "Documents"),
            "<winPublic>" to File(File(driveC, "users"), "Public"),
            "<winProgramData>" to File(driveC, "ProgramData"),
            "<winDir>" to File(driveC, "windows"),
        )
    }
}
