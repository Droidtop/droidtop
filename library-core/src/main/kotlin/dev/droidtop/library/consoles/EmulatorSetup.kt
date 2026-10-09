package dev.droidtop.library.consoles

import android.content.Context
import dev.droidtop.runtime.tasks.ElevatedFiles
import dev.droidtop.runtime.tasks.RiskyActions
import dev.droidtop.runtime.tasks.RiskyClass
import dev.droidtop.runtime.tasks.TaskManager
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** How an emulator's config file is laid out. */
enum class ConfigFormat {
    /** One `key = "value"` per line, no sections: RetroArch's retroarch.cfg. */
    KEY_VALUE,

    /** `[section]` headers and `key = value` lines: PCSX2, Dolphin, PPSSPP. */
    INI,
}

/** One value a [ConfigSetting] can take, as written in the file and as the person reads it. */
data class ConfigOption(val value: String, val label: String)

/**
 * One option droidtop can set in an emulator's own config file, with where the person finds the same option inside
 * the emulator ([manual]), so the row can say what to do when droidtop cannot reach the file.
 */
data class ConfigSetting(
    val id: String,
    val label: String,
    val about: String,
    val section: String?,
    val key: String,
    val options: List<ConfigOption>,
    val manual: String,
)

/**
 * An emulator's config file. [writesOnExit]: the emulator writes its whole config back when it closes, so a change
 * made while it is open is lost (RetroArch's `config_save_on_exit`, on by default).
 */
data class ConfigSpec(val file: String, val format: ConfigFormat, val settings: List<ConfigSetting>, val writesOnExit: Boolean)

/**
 * What an emulator needs from outside itself, beyond the launch: where it reads BIOS files and which of its options
 * droidtop can set (docs/SPEC.md "Emulator setup helper", Droidtop/tracker#248). The one source both the helper's
 * actions and the manual steps read. For a players-database emulator it is the row's `setup` object; for RetroArch,
 * whose launch droidtop generates, it is [EmulatorSetup.retroArch].
 *
 * The BIOS folder is either a fixed path ([biosFolder]) or the value of a key in the emulator's own config
 * ([biosFolderKey]), because RetroArch lets the person move it. [biosManual] is where the emulator shows that folder.
 */
data class EmulatorSetupSpec(
    val biosFolder: String? = null,
    val biosFolderKey: String? = null,
    val biosManual: String? = null,
    val config: ConfigSpec? = null,
)

/** Reading and changing one key of a config file, by its format. Pure, for tests. */
object ConfigText {
    private fun keyValueMatch(line: String, key: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith(key) && trimmed.substring(key.length).trimStart().startsWith("=")
    }

    private fun valueOf(line: String): String = line.substringAfter('=').trim().removeSurrounding("\"")

    private fun sectionOf(line: String): String? {
        val trimmed = line.trim()
        return if (trimmed.length >= 2 && trimmed.startsWith("[") && trimmed.endsWith("]")) trimmed.substring(1, trimmed.length - 1).trim() else null
    }

    /** The value of [key] (in [section] for an INI file, null meaning before any section), or null when the file does not set it. */
    fun get(text: String, format: ConfigFormat, section: String?, key: String): String? {
        var current: String? = null
        for (line in text.lines()) {
            if (format == ConfigFormat.INI) {
                val header = sectionOf(line)
                if (header != null) {
                    current = header
                    continue
                }
                if (current != section) continue
            }
            if (keyValueMatch(line, key)) return valueOf(line)
        }
        return null
    }

    /**
     * [text] with [key] set to [value]: its line replaced where the file has one, otherwise added (at the end of a
     * key-value file; at the end of its section in an INI file, with the section added when it is missing). Every
     * other line is kept as it was.
     */
    fun set(text: String, format: ConfigFormat, section: String?, key: String, value: String): String {
        val written = if (format == ConfigFormat.KEY_VALUE) "$key = \"$value\"" else "$key = $value"
        val lines = text.lines().toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        fun joined() = lines.joinToString("\n", postfix = "\n")
        if (format == ConfigFormat.KEY_VALUE) {
            val at = lines.indexOfFirst { keyValueMatch(it, key) }
            if (at >= 0) lines[at] = written else lines += written
            return joined()
        }
        val start: Int
        if (section == null) {
            start = 0
        } else {
            val header = lines.indexOfFirst { sectionOf(it) == section }
            if (header < 0) {
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
                lines += "[$section]"
                lines += written
                return joined()
            }
            start = header + 1
        }
        val end = (start until lines.size).firstOrNull { sectionOf(lines[it]) != null } ?: lines.size
        val existing = (start until end).firstOrNull { keyValueMatch(lines[it], key) }
        if (existing != null) {
            lines[existing] = written
            return joined()
        }
        var at = end
        while (at > start && lines[at - 1].isBlank()) at--
        lines.add(at, written)
        return joined()
    }
}

/**
 * The emulator setup helper's device side: reading and writing an emulator's files directly when droidtop may, and
 * through the privileged helper (Shizuku or Sui, `PrivilegedShell.readFile`/`writeFile`) when they sit in another
 * app's `Android/data`, and saying what to do by hand when neither can. Everything here does file or helper IO:
 * background threads only.
 */
object EmulatorSetup {
    /**
     * How droidtop can reach a file: itself, through the helper, through the helper once Settings > Risky actions >
     * Write another app's files is on ([LOCKED]), or not at all.
     */
    enum class Reach { DIRECT, HELPER, LOCKED, NONE }

    // --- pure parts --------------------------------------------------------------------------------------------

    /**
     * RetroArch's setup (retroarch.cfg as droidtop launches it, CONFIGFILE). Keys and defaults from RetroArch 1.22.2
     * configuration.c (system_directory :1645, pause_nonactive :1770, savestate_auto_save/_load :1790-1791,
     * video_scale_integer :1895, network_cmd_enable :2217); menu places from menu_displaylist.c (User Interface,
     * Saving, Video > Scaling, Network, Directory lists) and the labels from intl/msg_hash_us.h.
     */
    fun retroArch(packageName: String): EmulatorSetupSpec = EmulatorSetupSpec(
        biosFolderKey = "system_directory",
        biosManual = "RetroArch > Settings > Directory > System/BIOS",
        config = ConfigSpec(
            file = DefaultPlayers.retroArchConfigFile(packageName),
            format = ConfigFormat.KEY_VALUE,
            writesOnExit = true,
            settings = listOf(
                ConfigSetting(
                    id = "pause_nonactive",
                    label = "When you switch away from a game",
                    about = "Whether the game keeps running while another app or the other screen has focus",
                    section = null,
                    key = "pause_nonactive",
                    options = listOf(ConfigOption("false", "Keep it running"), ConfigOption("true", "Pause it")),
                    manual = "RetroArch > Settings > User Interface > Pause Content When Not Active",
                ),
                ConfigSetting(
                    id = "savestate_auto_save",
                    label = "Save your place when a game closes",
                    about = "RetroArch saves a state as the game closes",
                    section = null,
                    key = "savestate_auto_save",
                    options = listOf(ConfigOption("true", "On"), ConfigOption("false", "Off")),
                    manual = "RetroArch > Settings > Saving > Auto Save State",
                ),
                ConfigSetting(
                    id = "savestate_auto_load",
                    label = "Continue from your place when a game starts",
                    about = "RetroArch loads the state it saved last time",
                    section = null,
                    key = "savestate_auto_load",
                    options = listOf(ConfigOption("true", "On"), ConfigOption("false", "Off")),
                    manual = "RetroArch > Settings > Saving > Auto Load State",
                ),
                ConfigSetting(
                    id = "video_scale_integer",
                    label = "Sharp pixels",
                    about = "Scales the picture by whole numbers only, so every pixel is the same size",
                    section = null,
                    key = "video_scale_integer",
                    options = listOf(ConfigOption("true", "On"), ConfigOption("false", "Off")),
                    manual = "RetroArch > Settings > Video > Scaling > Integer Scale",
                ),
                ConfigSetting(
                    id = "network_cmd_enable",
                    label = "Let droidtop control RetroArch while it runs",
                    about = "RetroArch's network commands, on this device only, which droidtop's RetroArch manager uses to change the core in a running RetroArch",
                    section = null,
                    key = "network_cmd_enable",
                    options = listOf(ConfigOption("true", "On"), ConfigOption("false", "Off")),
                    manual = "RetroArch > Settings > Network > Network Commands",
                ),
            ),
        ),
    )

    /**
     * A players-database row's `setup` object ([EmulatorSetupSpec]); `{pkg}` in a path is the row's package. Null when
     * the row has none. Throws on a malformed object, so a bad database is refused whole like any other bad row.
     */
    fun parse(obj: JSONObject?, packageName: String): EmulatorSetupSpec? {
        if (obj == null) return null
        fun path(raw: String): String = raw.replace("{pkg}", packageName)
        val config = obj.optJSONObject("config")?.let { c ->
            val settings = c.optJSONArray("settings")
            ConfigSpec(
                file = path(c.getString("file")),
                format = when (val f = c.getString("format")) {
                    "ini" -> ConfigFormat.INI
                    "keyvalue" -> ConfigFormat.KEY_VALUE
                    else -> throw IllegalArgumentException("unknown config format $f")
                },
                writesOnExit = c.optBoolean("writesOnExit", false),
                settings = (0 until (settings?.length() ?: 0)).map { i ->
                    val s = settings!!.getJSONObject(i)
                    val options = s.getJSONArray("options")
                    ConfigSetting(
                        id = s.getString("id"),
                        label = s.getString("label"),
                        about = s.optString("about"),
                        section = s.optString("section").takeIf { it.isNotEmpty() },
                        key = s.getString("key"),
                        options = (0 until options.length()).map { j ->
                            options.getJSONObject(j).let { ConfigOption(it.getString("value"), it.getString("label")) }
                        }.also { require(it.isNotEmpty()) { "setting ${s.getString("id")} has no options" } },
                        manual = s.getString("manual"),
                    )
                },
            )
        }
        return EmulatorSetupSpec(
            biosFolder = obj.optString("biosFolder").takeIf { it.isNotEmpty() }?.let(::path),
            biosFolderKey = obj.optString("biosFolderKey").takeIf { it.isNotEmpty() },
            biosManual = obj.optString("biosManual").takeIf { it.isNotEmpty() },
            config = config,
        )
    }

    /**
     * The BIOS folder [spec] names: its fixed folder, or the value its config gives [EmulatorSetupSpec.biosFolderKey]
     * (RetroArch writes "default" when the person never chose one, which is not a path droidtop can use).
     */
    fun biosFolder(spec: EmulatorSetupSpec, configText: String?): String? =
        spec.biosFolder ?: spec.biosFolderKey?.let { key ->
            val format = spec.config?.format ?: ConfigFormat.KEY_VALUE
            configText?.let { ConfigText.get(it, format, null, key) }
                ?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("/") && it != "default" }
        }

    /** A BIOS database entry's path inside the emulator's BIOS folder ("bios/dc/dc_boot.bin" is "dc/dc_boot.bin"). */
    fun biosRelative(file: String): String = file.removePrefix("bios/")

    /**
     * Where a picked BIOS file goes inside the folder: the database's own name when its md5 matches a listed file
     * (a dump renamed by the person still lands where the emulator looks), else the listed file of the same name,
     * else the picked name as it is.
     */
    fun biosTarget(spec: SystemBiosSpec?, pickedName: String, md5: String): String {
        val files = spec?.files.orEmpty()
        files.firstOrNull { md5.lowercase() in it.md5 }?.let { return biosRelative(it.file) }
        files.firstOrNull { biosRelative(it.file).substringAfterLast('/').equals(pickedName, ignoreCase = true) }
            ?.let { return biosRelative(it.file) }
        return pickedName
    }

    fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    // --- device parts ------------------------------------------------------------------------------------------

    /** Whether droidtop can write [path] (or create it in its folder) itself, or the helper can, or neither. */
    fun writeReach(path: String): Reach {
        val file = File(path)
        val direct = runCatching {
            if (file.exists()) file.canWrite() else generateSequence(file.parentFile) { it.parentFile }.firstOrNull { it.exists() }?.canWrite() == true
        }.getOrDefault(false)
        return when {
            direct -> Reach.DIRECT
            ElevatedFiles.allowed(path) && TaskManager.privileges().files ->
                if (RiskyActions.allows(RiskyClass.OTHER_APP_FILES)) Reach.HELPER else Reach.LOCKED
            else -> Reach.NONE
        }
    }

    /** Reads [path] itself when it can, else through the helper; null when neither can. */
    fun read(path: String): String? {
        val file = File(path)
        if (runCatching { file.canRead() }.getOrDefault(false)) return runCatching { file.readText() }.getOrNull()
        if (!ElevatedFiles.allowed(path) || !TaskManager.privileges().files) return null
        return TaskManager.shell.readFile(path)?.toString(Charsets.UTF_8)
    }

    /** Writes [path] whole, itself when it can, else through the helper. */
    fun write(path: String, data: ByteArray): Boolean = when (writeReach(path)) {
        Reach.DIRECT -> runCatching {
            val file = File(path)
            file.parentFile?.mkdirs()
            val staged = File(file.parentFile, file.name + ".droidtop-part")
            staged.writeBytes(data)
            if (!staged.renameTo(file)) {
                staged.delete()
                error("rename failed")
            }
        }.isSuccess
        Reach.HELPER -> TaskManager.shell.writeFile(path, data)
        Reach.LOCKED, Reach.NONE -> false
    }

    /** The files in [folder] as paths relative to it, when droidtop or the helper can list it; null when neither can. */
    fun listFolder(folder: String): Set<String>? {
        val dir = File(folder)
        if (runCatching { dir.isDirectory && dir.canRead() }.getOrDefault(false)) {
            return dir.walkTopDown().maxDepth(3).filter { it.isFile }.map { it.relativeTo(dir).path }.toSet()
        }
        val shell = TaskManager.shell
        if (!ElevatedFiles.allowed("$folder/x") || !shell.capabilities().shell) return null
        val out = shell.exec(listOf("find", folder, "-maxdepth", "3", "-type", "f")) ?: return null
        if (out.exit != 0) return if (out.stderr.contains("No such file")) emptySet() else null
        return out.stdout.lines().filter { it.startsWith("$folder/") }.map { it.removePrefix("$folder/") }.toSet()
    }

    /** The setup [player] has: RetroArch's own, else its players-database row's. */
    fun specFor(context: Context, player: Player.AmStart): EmulatorSetupSpec? =
        if (RetroArchCores.isRetroArch(player.packageName)) retroArch(player.packageName) else KnownPlayers.setupFor(context, player.packageName)
}
