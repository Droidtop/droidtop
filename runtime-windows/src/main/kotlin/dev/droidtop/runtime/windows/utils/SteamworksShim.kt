package dev.droidtop.runtime.windows.utils

import android.content.Context
import com.winlator.container.Container
import dev.droidtop.library.stores.StoreLibraries
import dev.droidtop.library.stores.StorePlayer
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Steamworks inside droidtop's Wine prefix (docs/SPEC.md 5b, "Steamworks in
 * the prefix", Droidtop/tracker#310).
 *
 * A Windows Steam game's own `steam_api(64).dll` starts only when a Steam
 * client is running and it can load that client's `steamclient(64).dll`;
 * without one `SteamAPI_Init` fails and many games stop there. droidtop's
 * Steam is its own store and runs no client in the prefix, so a game that
 * uses Steamworks is started through gbe_fork's ColdClientLoader instead:
 * the loader writes the registry keys steam_api reads and starts the game,
 * and gbe_fork's steamclient answers it. Nothing in the game's folder is
 * touched; everything lives in the prefix's `C:\Program Files (x86)\Steam`.
 *
 * Who plays is droidtop's Steam sign-in ([StorePlayer]: the account's
 * SteamID, its name, the DLC droidtop installed), so saves the game keys to
 * the account and the account's DLC are the person's own. Steam Cloud saves
 * land where droidtop's sync reads them (`Steam/userdata/<account>/<app>/
 * remote`). A Steam game whose folder droidtop did not install (a Steam
 * library on a games drive) is found by its app manifest and plays as the
 * signed-in account, or as a local profile when nobody is signed in.
 *
 * Applies to every Steam game (one whose Steam app id is known: droidtop's
 * Steam, a Steam library's app manifest, or the game's own
 * `steam_appid.txt`) unless the game turned it off (its Wine and graphics
 * screen, [dev.droidtop.runtime.windows.WineOptions]'s Steamworks row). It
 * is not limited to folders where a `steam_api` file was found: Unity keeps
 * it three folders down and Unreal six, and walking a game's whole tree on
 * every launch costs more on a card than the loader does for a game that
 * never calls Steam (the loader then only starts the game).
 */
object SteamworksShim {
    /** Release of Droidtop/droidtop that carries the asset; see .github/workflows/steamworks-shim.yml. */
    const val RELEASE_TAG = "steamworks-shim-20261008-e3304a76"
    const val SHA256 = "391b346d3ff2820e74313d21fa8e6258ab803e2d2b1e32566a0f5b583c95e040"
    private val release = PinnedReleaseAsset(RELEASE_TAG, "steamworks-shim.tzst", SHA256, "steamworks-shim", "the Steamworks support for Steam games")

    /** How a launch goes through the shim: the loader to start instead of the game, from where, and what the guest needs set. */
    data class Launch(val target: File, val workingDir: File, val arguments: List<String>, val env: Map<String, String>)

    /** What [plan] found: the game's Steam app id, and where it came from (for the log). */
    data class Need(val appId: Int, val via: String)

    /**
     * Whether [entryId] (folder [gameRoot]) is a Steam game, and its app id.
     * At most one listing of a Steam library's `steamapps` and one small
     * file; never on the main thread.
     */
    fun plan(entryId: String?, gameRoot: File): Need? {
        entryId?.takeIf { it.startsWith("steam:") }?.substringAfter(':')?.toIntOrNull()?.let { return Need(it, "droidtop's Steam") }
        appIdFromManifest(gameRoot)?.let { return Need(it, "its Steam library's app manifest") }
        // A game that ships its own id beside its program is a Steam build too.
        runCatching { File(gameRoot, "steam_appid.txt").takeIf { it.isFile }?.readText()?.trim()?.toInt() }.getOrNull()
            ?.let { return Need(it, "the game's steam_appid.txt") }
        return null
    }

    /**
     * The app id of [gameRoot] when it is `steamapps/common/<dir>` of a
     * Steam library: the `appmanifest_*.acf` whose `installdir` names it.
     */
    internal fun appIdFromManifest(gameRoot: File): Int? {
        val common = gameRoot.parentFile ?: return null
        val steamapps = common.parentFile ?: return null
        if (!common.name.equals("common", ignoreCase = true) || !steamapps.name.equals("steamapps", ignoreCase = true)) return null
        val manifests = steamapps.listFiles { f -> f.isFile && f.name.startsWith("appmanifest_") && f.name.endsWith(".acf") } ?: return null
        for (manifest in manifests) {
            val text = runCatching { manifest.readText() }.getOrNull() ?: continue
            val installDir = Regex("\"installdir\"\\s+\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1) ?: continue
            if (!installDir.equals(gameRoot.name, ignoreCase = true)) continue
            return Regex("\"appid\"\\s+\"(\\d+)\"", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
        }
        return null
    }

    /** The DLC app ids a Steam library's manifest lists as installed (`InstalledDepots` entries with a `dlcappid`). */
    internal fun installedDlcFromManifest(gameRoot: File, appId: Int): Set<String> {
        val manifest = File(gameRoot.parentFile?.parentFile, "appmanifest_$appId.acf")
        val text = runCatching { manifest.readText() }.getOrNull() ?: return emptySet()
        return Regex("\"dlcappid\"\\s+\"(\\d+)\"", RegexOption.IGNORE_CASE).findAll(text).map { it.groupValues[1] }.toSet()
    }

    /**
     * Puts the shim and this launch's settings in [container]'s prefix and
     * says how to start [executable] through it. Downloads the shim the first
     * time. Disk and network work; never on the main thread.
     */
    suspend fun prepare(
        context: Context,
        container: Container,
        need: Need,
        entryId: String?,
        executable: File,
        gameRoot: File,
        workingDir: File,
        arguments: List<String>,
    ): Launch = withContext(Dispatchers.IO) {
        release.ensureInstalled(context) {}
        val steamDir = File(container.rootDir, ".wine/drive_c/Program Files (x86)/Steam").apply { mkdirs() }
        copyShim(File(release.root(context), "steam"), steamDir)

        val player = entryId?.takeIf { it.startsWith("steam:") }
            ?.let { id -> runCatching { StoreLibraries.forKey(id)?.player(context, id.substringAfter(':')) }.getOrNull() }
            ?: runCatching { StoreLibraries.byId("steam")?.player(context, need.appId.toString()) }.getOrNull()
        val dlc = player?.dlc?.takeIf { it.isNotEmpty() } ?: installedDlcFromManifest(gameRoot, need.appId)
        val settings = File(steamDir, "steam_settings").apply { mkdirs() }
        writeIfChanged(File(settings, "configs.user.ini"), userIni(player, language()))
        writeIfChanged(File(settings, "configs.app.ini"), appIni(dlc))

        val drives = container.drivesIterator().map { it[0] to it[1] }
        val loader = if (is64Bit(executable)) "steamclient_loader_x64.exe" else "steamclient_loader_x86.exe"
        writeIfChanged(
            File(steamDir, "ColdClientLoader.ini"),
            loaderIni(
                appId = need.appId,
                exe = windowsPath(drives, executable),
                runDir = windowsPath(drives, workingDir),
            ),
        )
        Timber.i(
            "SteamworksShim: app %d (from %s) starts through %s as %s, %d DLC",
            need.appId, need.via, loader, if (player != null) "the signed-in Steam account" else "a local profile", dlc.size,
        )
        Launch(
            target = File(steamDir, loader),
            workingDir = steamDir,
            arguments = arguments,
            // Proton's Wine sends steamclient loads to its lsteamclient bridge,
            // which needs a Linux Steam client this device does not have.
            env = mapOf("PROTON_DISABLE_LSTEAMCLIENT" to "1"),
        )
    }

    private fun copyShim(from: File, to: File) {
        for (file in from.listFiles().orEmpty()) {
            if (!file.isFile) continue
            val dest = File(to, file.name)
            if (dest.isFile && dest.length() == file.length() && dest.lastModified() == file.lastModified()) continue
            file.copyTo(dest, overwrite = true)
            dest.setLastModified(file.lastModified())
        }
    }

    private fun writeIfChanged(file: File, text: String) {
        if (file.isFile && runCatching { file.readText() }.getOrNull() == text) return
        file.writeText(text)
    }

    /**
     * gbe_fork's user settings: who plays, in which language, and where saves
     * go. Saves sit where Steam keeps them (`userdata/<account>/<app>/remote`
     * below the steamclient), which is where droidtop's Steam Cloud sync
     * reads and writes them; a local profile keeps them under `userdata/0`.
     */
    internal fun userIni(player: StorePlayer?, language: String): String = buildString {
        append("[user::general]\n")
        if (player != null) {
            append("account_name=").append(player.name.replace('\n', ' ')).append('\n')
            append("account_steamid=").append(player.steamId64).append('\n')
        }
        append("language=").append(language).append('\n')
        append("\n[user::saves]\n")
        val account = player?.steamId64?.let { it and 0xFFFFFFFFL } ?: 0L
        append("local_save_path=./userdata/").append(account).append('\n')
    }

    /** The DLC the game reports as owned: exactly [dlc], never every DLC. */
    internal fun appIni(dlc: Set<String>): String = buildString {
        append("[app::dlcs]\n")
        append("unlock_all=0\n")
        dlc.sorted().forEach { append(it).append('=').append(it).append('\n') }
    }

    internal fun loaderIni(appId: Int, exe: String, runDir: String): String = """
        [SteamClient]
        Exe=$exe
        ExeRunDir=$runDir
        ExeCommandLine=
        AppId=$appId
        SteamClientDll=steamclient.dll
        SteamClient64Dll=steamclient64.dll

        [Injection]
        ForceInjectSteamClient=0
        ForceInjectGameOverlayRenderer=0
        DllsToInjectFolder=
        IgnoreInjectionError=1
        IgnoreLoaderArchDifference=1

        [Persistence]
        Mode=0

        [Debug]
        ResumeByDebugger=0
    """.trimIndent() + "\n"

    /**
     * [file]'s path inside the prefix: below the mapped drive whose folder
     * holds it (the games roots, D: onwards), else below `Z:`, which a Wine
     * prefix maps to the filesystem root.
     */
    internal fun windowsPath(drives: List<Pair<String, String>>, file: File): String {
        val path = file.absolutePath
        val drive = drives
            .filter { (_, root) -> root.isNotEmpty() && (path == root || path.startsWith(root.trimEnd('/') + "/")) }
            .maxByOrNull { it.second.length }
        return if (drive != null) {
            drive.first + ":" + path.removePrefix(drive.second.trimEnd('/')).replace('/', '\\').ifEmpty { "\\" }
        } else {
            "Z:" + path.replace('/', '\\')
        }
    }

    /** Whether [exe] is a 64-bit (x86-64) program, read off its PE header; a file that cannot be read counts as 64-bit. */
    internal fun is64Bit(exe: File): Boolean = runCatching {
        RandomAccessFile(exe, "r").use { f ->
            f.seek(0x3C)
            val peOffset = Integer.reverseBytes(f.readInt()).toLong()
            f.seek(peOffset + 4)
            val machine = java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xFFFF
            machine != 0x014C
        }
    }.getOrDefault(true)

    /** The device's language as a Steam API language code (partner.steamgames.com, "Languages"); English when Steam has none for it. */
    internal fun language(locale: Locale = Locale.getDefault()): String = when (locale.language) {
        "de" -> "german"
        "fr" -> "french"
        "it" -> "italian"
        "es" -> if (locale.country in setOf("ES", "")) "spanish" else "latam"
        "pt" -> if (locale.country == "BR") "brazilian" else "portuguese"
        "ru" -> "russian"
        "pl" -> "polish"
        "nl" -> "dutch"
        "sv" -> "swedish"
        "da" -> "danish"
        "fi" -> "finnish"
        "nb", "no" -> "norwegian"
        "cs" -> "czech"
        "hu" -> "hungarian"
        "ro" -> "romanian"
        "tr" -> "turkish"
        "uk" -> "ukrainian"
        "el" -> "greek"
        "bg" -> "bulgarian"
        "th" -> "thai"
        "vi" -> "vietnamese"
        "id" -> "indonesian"
        "ja" -> "japanese"
        "ko" -> "koreana"
        "zh" -> if (locale.country in setOf("TW", "HK", "MO") || locale.script == "Hant") "tchinese" else "schinese"
        "ar" -> "arabic"
        else -> "english"
    }
}
