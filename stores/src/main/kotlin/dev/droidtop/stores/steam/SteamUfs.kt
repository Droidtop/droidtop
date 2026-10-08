package dev.droidtop.stores.steam

import android.content.Context
import `in`.dragonbra.javasteam.types.KeyValue
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where Steam Auto-Cloud says a game keeps its saves (docs/SPEC.md 7g,
 * "Stores", Droidtop/tracker#313): the `ufs` section of the game's product
 * info, read the way GameNative read it (KeyValueUtils.generateSteamApp,
 * PathType.from; GPL-3.0). Only Windows entries are kept: droidtop runs a
 * Steam game through Wine.
 */
@Serializable
internal enum class SaveRoot {
    GameInstall,
    SteamUserData,
    WinMyDocuments,
    WinAppDataLocal,
    WinAppDataLocalLow,
    WinAppDataRoaming,
    WinSavedGames,
    WinProgramData,
    LinuxHome,
    LinuxXdgDataHome,
    LinuxXdgConfigHome,
    MacHome,
    MacAppSupport,
    Root,
    None,
    ;

    /** Whether this root is somewhere a Windows game keeps files, so a Wine prefix has it. */
    val isWindows: Boolean
        get() = when (this) {
            GameInstall, SteamUserData, WinMyDocuments, WinAppDataLocal, WinAppDataLocalLow,
            WinAppDataRoaming, WinSavedGames, WinProgramData, Root,
            -> true
            else -> false
        }

    /** The token Steam writes for this root in a cloud file's name: `%WinAppDataLocal%`. */
    val token: String get() = "%$name%"

    companion object {
        /** The root a `%Name%` token, a bare name or one of Steam's older names stands for; [None] for anything else. */
        fun from(keyValue: String?): SaveRoot {
            val name = keyValue?.trim()?.trim('%')?.lowercase() ?: return None
            return when (name) {
                "steamuserbasestorage" -> SteamUserData
                "steamclouddocuments" -> WinMyDocuments
                "windowshome", "root_mod" -> Root
                else -> entries.firstOrNull { it != None && it.name.lowercase() == name } ?: None
            }
        }
    }
}

/**
 * One `ufs/savefiles` entry: files matching [pattern] under [root]/[path] on
 * this device. [uploadRoot] and [uploadPath] are what the cloud names them by
 * when a `rootoverrides` entry moved the local folder (Steam's own root for
 * the game, so a file keeps one cloud name wherever each device keeps it).
 */
@Serializable
internal data class SavePattern(
    val root: SaveRoot,
    val path: String,
    val pattern: String,
    val recursive: Int = 0,
    val uploadRoot: SaveRoot = root,
    val uploadPath: String = path,
)

@Serializable
internal data class SteamUfs(
    val quota: Int = 0,
    val maxNumFiles: Int = 0,
    val patterns: List<SavePattern> = emptyList(),
) {
    val isEmpty: Boolean get() = patterns.isEmpty()
}

internal object SteamUfsParser {
    private class RootOverride(val from: SaveRoot, val to: SaveRoot, val addPath: String, val transforms: List<Pair<String, String>>)

    /** The `ufs` section of an app's product info [app] (its root KeyValue). */
    fun parse(app: KeyValue): SteamUfs {
        val ufs = app["ufs"]
        val overrides = ufs["rootoverrides"].children.mapNotNull { entry ->
            val os = entry["os"].value.orEmpty()
            val osList = entry["oslist"].value.orEmpty()
            val forWindows = os.equals("Windows", ignoreCase = true) || osList.split(",").any { it.trim().equals("windows", ignoreCase = true) }
            if (!forWindows) return@mapNotNull null
            RootOverride(
                from = SaveRoot.from(entry["root"].value),
                to = SaveRoot.from(entry["useinstead"].value),
                addPath = entry["addpath"].value.orEmpty(),
                transforms = entry["pathtransforms"].children.map { it["find"].value.orEmpty() to it["replace"].value.orEmpty() },
            )
        }
        val patterns = ufs["savefiles"].children.mapNotNull { entry ->
            val platforms = entry["platforms"].children.map { it.value?.lowercase() }
            if (platforms.isNotEmpty() && "windows" !in platforms) return@mapNotNull null
            val original = SaveRoot.from(entry["root"].value)
            // "." and "/" both mean the root itself.
            val originalPath = entry["path"].value.orEmpty().let { if (it == "." || it == "/") "" else it }
            val override = overrides.find { it.from == original }
            SavePattern(
                root = override?.to ?: original,
                path = override?.let { moved(originalPath, it) } ?: originalPath,
                pattern = entry["pattern"].value.orEmpty(),
                recursive = entry["recursive"].asInteger(0),
                uploadRoot = original,
                uploadPath = originalPath,
            )
        }
        return SteamUfs(quota = ufs["quota"].asInteger(), maxNumFiles = ufs["maxnumfiles"].asInteger(), patterns = patterns)
    }

    /** [path] under the override's new root: its `addpath` in front (Windows backslashes made slashes), then its transforms. */
    private fun moved(path: String, override: RootOverride): String {
        var moved = if (override.addPath.isNotEmpty()) {
            val added = override.addPath.replace('\\', '/').trimEnd('/')
            if (path.isNotEmpty()) "$added/${path.trimStart('/')}" else added
        } else {
            path
        }
        override.transforms.forEach { (find, replace) -> moved = moved.replace(find, replace) }
        return moved
    }
}

/**
 * The `ufs` section kept per game in a small file beside the other Steam
 * files: product info is read per app only when an install, an update check
 * or a save sync asks for it, and the save sync then needs no second request.
 */
internal object SteamUfsCache {
    private val JSON = Json { ignoreUnknownKeys = true }

    fun file(context: Context, appId: Int): File = File(context.filesDir, "steam/ufs/$appId.json")

    fun save(context: Context, appId: Int, ufs: SteamUfs) {
        val file = file(context, appId)
        file.parentFile?.mkdirs()
        file.writeText(JSON.encodeToString(SteamUfs.serializer(), ufs))
    }

    fun load(context: Context, appId: Int): SteamUfs? = runCatching {
        file(context, appId).takeIf { it.isFile }?.let { JSON.decodeFromString(SteamUfs.serializer(), it.readText()) }
    }.getOrNull()
}
