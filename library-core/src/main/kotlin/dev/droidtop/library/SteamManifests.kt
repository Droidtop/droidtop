package dev.droidtop.library

import java.io.File

/**
 * A Steam library's own record of what it has installed: the
 * `appmanifest_<appid>.acf` files in its `steamapps` folder (docs/SPEC.md
 * 7g, "Steam libraries in a game folder"). The one reader of them, for the
 * PC walk ([PcFolderScan]) and for the Steamworks shim's app id.
 *
 * Why the walk needs them: a Steam library met inside a games root puts its
 * games at `steamapps/common/<installdir>`, which is already four folders
 * down, so a game whose program sits in a sub-folder (`bin/`, `Binaries/`) is
 * past the depth bound and was never found. The manifest says where the game
 * is and that it is installed, whatever its folder holds.
 *
 * Files are read, never written. Disk work; never on the main thread.
 */
object SteamManifests {

    /** One manifest's facts. [installed] is Steam's own `StateFlags` bit 4 (fully installed). */
    data class App(val appId: String, val name: String?, val installDir: String?, val installed: Boolean)

    /** The manifest [text] says, or null when it names no app id. */
    fun parse(text: String): App? {
        val appId = field(text, "appid")?.takeIf { id -> id.isNotEmpty() && id.all { it.isDigit() } } ?: return null
        val flags = field(text, "StateFlags")?.toIntOrNull() ?: 0
        return App(appId, field(text, "name"), field(text, "installdir"), installed = flags and 4 != 0)
    }

    /** The first top-level `"key" "value"` pair; the keys read are all before any nested block. */
    private fun field(text: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\"\\s+\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)

    /** Every manifest in [steamapps], in app id order. */
    fun apps(steamapps: File): List<App> =
        (steamapps.listFiles { f -> f.name.startsWith("appmanifest_") && f.name.endsWith(".acf") && f.isFile } ?: emptyArray())
            .sortedBy { it.name }
            .mapNotNull { manifest -> runCatching { parse(manifest.readText()) }.getOrNull() }

    /**
     * The folders of the games [libraryRoot] (a folder holding `steamapps`)
     * has fully installed: `steamapps/common/<installdir>` of each manifest
     * whose folder exists. The tools Steam installs as apps are no games.
     */
    fun installedGameFolders(libraryRoot: File): List<File> {
        val steamapps = File(libraryRoot, "steamapps")
        val common = File(steamapps, "common")
        return apps(steamapps)
            .filter { it.installed && !it.isTool() }
            .mapNotNull { app -> app.installDir?.takeIf { it.isNotBlank() }?.let { File(common, it) } }
            .filter { it.isDirectory }
            .distinct()
    }

    /** Steam's runtimes, Proton builds and redistributables: installed as apps, never games. */
    private fun App.isTool(): Boolean =
        appId in TOOL_APP_IDS ||
            name?.let { it.startsWith("Proton ") || it.startsWith("Steam Linux Runtime") || it.startsWith("Steamworks Common Redistributables") } == true

    private val TOOL_APP_IDS = setOf("228980", "1070560", "1391110", "1628350", "1493710")

    /**
     * The app id of [gameRoot] when it is `steamapps/common/<dir>` of a
     * Steam library: the manifest whose `installdir` names it.
     */
    fun appIdOf(gameRoot: File): Int? {
        val common = gameRoot.parentFile ?: return null
        val steamapps = common.parentFile ?: return null
        if (!common.name.equals("common", ignoreCase = true) || !steamapps.name.equals("steamapps", ignoreCase = true)) return null
        return apps(steamapps)
            .firstOrNull { it.installDir?.equals(gameRoot.name, ignoreCase = true) == true }
            ?.appId?.toIntOrNull()
    }
}
