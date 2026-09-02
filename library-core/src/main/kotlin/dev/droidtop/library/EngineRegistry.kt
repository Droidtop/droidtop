package dev.droidtop.library

import java.io.File
import org.json.JSONObject

/**
 * The engine REGISTRY model + parser for engines-database.json v4 (docs/
 * SPEC.md §7e2b, direction 2026-08-31: detection, launch routing, and
 * enginehost vocabulary all live in the database so new engines and
 * contexts ship as a data update, not an app rebuild).
 *
 * A row's detection is an ordered list of [DetectRule]s: rules OR each
 * other, the conditions inside one rule AND together, and the first
 * engine in FILE ORDER with a matching rule wins. Byte-magic probes
 * JSON cannot express (Godot's GDPC trailer, Unity's depth-limited
 * player search, Twine's html-head scan) stay code, referenced by name
 * through [DetectCondition.Builtin] -- the database still decides
 * whether and where they apply.
 */
data class EngineDef(
    val id: String,
    /**
     * The compiled-in engine this row maps to, or null for a row this
     * app version doesn't know yet (a future database against an older
     * app). Null rows still parse -- they are skipped at detection time
     * rather than failing the whole registry.
     */
    val engine: GameEngine?,
    val detect: List<DetectRule>,
    val strategies: List<GameLaunchStrategy>,
    val enginehost: EnginehostTarget?,
)

data class DetectRule(val all: List<DetectCondition>)

sealed interface DetectCondition {
    data class DirExists(val path: String) : DetectCondition
    data class FileExists(val path: String) : DetectCondition
    data class AnyFileNameContains(val value: String) : DetectCondition
    data class AnyFileExtension(val value: String) : DetectCondition
    data class AnyFileExtensionDeep(val value: String, val maxDepth: Int) : DetectCondition
    data class AnyFileNameIn(val values: Set<String>) : DetectCondition
    data class DirNamePrefixCount(val prefix: String, val min: Int) : DetectCondition
    data class FileHeadRegex(val path: String, val regex: Regex) : DetectCondition
    data class Builtin(val name: String) : DetectCondition
}

object EngineRegistryParser {

    /** The database's engine ids to this app's compiled-in engines. */
    val ENGINE_IDS: Map<String, GameEngine> = mapOf(
        "renpy" to GameEngine.RENPY,
        "rpgmaker-mv" to GameEngine.RPG_MAKER_MV,
        "rpgmaker-mz" to GameEngine.RPG_MAKER_MZ,
        "rpgmaker-vxace" to GameEngine.RPG_MAKER_VX_ACE,
        "rpgmaker-vx" to GameEngine.RPG_MAKER_VX,
        "rpgmaker-xp" to GameEngine.RPG_MAKER_XP,
        "rpgmaker-2000-2003" to GameEngine.RPG_MAKER_2000_2003,
        "kirikiri" to GameEngine.KIRIKIRI,
        "august" to GameEngine.AUGUST,
        "buriko" to GameEngine.BURIKO,
        "catsystem2" to GameEngine.CATSYSTEM2,
        "cmvs" to GameEngine.CMVS,
        // Generation-specific CMVS rows (v5): same compiled engine, but
        // the row carries the ps3/ps2 context enginehost needs.
        "cmvs-ps3" to GameEngine.CMVS,
        "cmvs-ps2" to GameEngine.CMVS,
        "flash-air" to GameEngine.FLASH_AIR,
        "twine" to GameEngine.TWINE,
        "godot" to GameEngine.GODOT,
        // Late compiled-files fallback row (v5) -- same engine, looser
        // evidence, deliberately ordered after every richer signature.
        "renpy-fallback" to GameEngine.RENPY,
        "unreal" to GameEngine.UNREAL,
        "unity" to GameEngine.UNITY,
    )

    /**
     * Parses a v4 registry. Throws on structural garbage (the caller's
     * validate-before-replace contract); an individual unknown condition
     * type or engine id is skipped, never fatal, so a NEWER database
     * still loads on an older app.
     */
    fun parse(text: String): List<EngineDef> {
        val engines = JSONObject(text).getJSONArray("engines")
        val result = ArrayList<EngineDef>(engines.length())
        for (i in 0 until engines.length()) {
            val row = engines.getJSONObject(i)
            val id = row.getString("id")
            result += EngineDef(
                id = id,
                engine = ENGINE_IDS[id],
                detect = parseDetect(row),
                strategies = parseStrategies(row),
                enginehost = parseEnginehost(row),
            )
        }
        return result
    }

    private fun parseDetect(row: JSONObject): List<DetectRule> {
        val detect = row.optJSONArray("detect") ?: return emptyList()
        val rules = ArrayList<DetectRule>(detect.length())
        for (i in 0 until detect.length()) {
            val all = detect.getJSONObject(i).getJSONArray("all")
            val conditions = ArrayList<DetectCondition>(all.length())
            var unknown = false
            for (j in 0 until all.length()) {
                val c = all.getJSONObject(j)
                val parsed: DetectCondition? = when (c.getString("type")) {
                    "dirExists" -> DetectCondition.DirExists(c.getString("path"))
                    "fileExists" -> DetectCondition.FileExists(c.getString("path"))
                    "anyFileNameContains" -> DetectCondition.AnyFileNameContains(c.getString("value").lowercase())
                    "anyFileExtension" -> DetectCondition.AnyFileExtension(c.getString("value").lowercase())
                    "anyFileExtensionDeep" -> DetectCondition.AnyFileExtensionDeep(c.getString("value").lowercase(), c.getInt("maxDepth"))
                    "anyFileNameIn" -> DetectCondition.AnyFileNameIn(
                        buildSet {
                            val values = c.getJSONArray("values")
                            for (k in 0 until values.length()) add(values.getString(k).lowercase())
                        },
                    )
                    "dirNamePrefixCount" -> DetectCondition.DirNamePrefixCount(c.getString("prefix").lowercase(), c.getInt("min"))
                    "fileHeadRegex" -> DetectCondition.FileHeadRegex(
                        c.getString("path"),
                        Regex(c.getString("regex"), RegexOption.IGNORE_CASE),
                    )
                    "builtin" -> DetectCondition.Builtin(c.getString("name"))
                    // A future condition type this app doesn't know: the
                    // RULE cannot be evaluated soundly, so the whole rule
                    // is dropped (never "condition ignored, rule matches
                    // anyway" -- that would misdetect).
                    else -> null
                }
                if (parsed == null) {
                    unknown = true
                    break
                }
                conditions += parsed
            }
            if (!unknown && conditions.isNotEmpty()) rules += DetectRule(conditions)
        }
        return rules
    }

    private fun parseStrategies(row: JSONObject): List<GameLaunchStrategy> {
        val strategies = row.optJSONArray("strategies") ?: return emptyList()
        return buildList {
            for (i in 0 until strategies.length()) {
                runCatching { GameLaunchStrategy.valueOf(strategies.getString(i)) }.getOrNull()?.let(::add)
            }
        }
    }

    private fun parseEnginehost(row: JSONObject): EnginehostTarget? {
        val eh = row.optJSONObject("enginehost") ?: return null
        val extras = eh.optJSONObject("extras")?.let { json ->
            buildMap { json.keys().forEach { key -> put(key, json.getString(key)) } }
        } ?: emptyMap()
        return EnginehostTarget(
            engine = eh.getString("family"),
            engineContext = if (eh.isNull("context")) null else eh.getString("context"),
            runtimeRequirements = extras,
            versionSelectorFallback = eh.optString("versionSelectorFallback").ifEmpty { null },
        )
    }
}

/**
 * Evaluates one [DetectRule] list against a real game folder. Pure and
 * context-free so the JVM unit tests exercise the exact shipped JSON.
 * [builtinProbe] resolves [DetectCondition.Builtin] names; an unknown
 * name fails its rule (soundness over optimism, same as the parser's
 * unknown-type handling).
 */
object EngineDetectRules {
    private const val FILE_HEAD_BYTES = 4096

    fun matches(rules: List<DetectRule>, folder: File, builtinProbe: (String, File) -> Boolean): Boolean =
        rules.any { rule -> rule.all.all { condition -> holds(condition, folder, builtinProbe) } }

    private fun holds(condition: DetectCondition, folder: File, builtinProbe: (String, File) -> Boolean): Boolean =
        when (condition) {
            is DetectCondition.DirExists -> File(folder, condition.path).isDirectory
            is DetectCondition.FileExists -> File(folder, condition.path).isFile
            is DetectCondition.AnyFileNameContains ->
                folder.listFiles()?.any { it.isFile && it.name.lowercase().contains(condition.value) } == true
            is DetectCondition.AnyFileExtension ->
                folder.listFiles()?.any { it.isFile && it.extension.lowercase() == condition.value } == true
            is DetectCondition.AnyFileExtensionDeep ->
                anyFileExtensionWithin(folder, condition.value, condition.maxDepth)
            is DetectCondition.AnyFileNameIn ->
                folder.listFiles()?.any { it.isFile && it.name.lowercase() in condition.values } == true
            is DetectCondition.DirNamePrefixCount ->
                (folder.listFiles()?.count { it.isDirectory && it.name.lowercase().startsWith(condition.prefix) } ?: 0) >= condition.min
            is DetectCondition.FileHeadRegex -> {
                val file = File(folder, condition.path)
                file.isFile && runCatching {
                    val head = file.inputStream().use { input ->
                        String(input.readNBytes(FILE_HEAD_BYTES), Charsets.ISO_8859_1)
                    }
                    condition.regex.containsMatchIn(head)
                }.getOrDefault(false)
            }
            is DetectCondition.Builtin -> builtinProbe(condition.name, folder)
        }

    /**
     * [DetectCondition.AnyFileExtensionDeep]: like AnyFileExtension but
     * descending [maxDepth] directory levels (maxDepth 0 = root only).
     * Depth-capped for the same reason Unity's builtin probe is: this
     * runs against every scanned folder, some of which are huge.
     */
    private fun anyFileExtensionWithin(folder: File, extension: String, maxDepth: Int): Boolean {
        val entries = folder.listFiles() ?: return false
        if (entries.any { it.isFile && it.extension.lowercase() == extension }) return true
        if (maxDepth <= 0) return false
        return entries.any { it.isDirectory && anyFileExtensionWithin(it, extension, maxDepth - 1) }
    }
}
