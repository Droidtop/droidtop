package dev.droidtop.runtime.windows

import android.content.Context
import android.net.Uri
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import dev.droidtop.runtime.windows.utils.ManifestInstaller
import dev.droidtop.runtime.windows.utils.X86_64GuestLibs
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Any Wine or Proton build, wherever it comes from (docs/SPEC.md 5a): the
 * catalog's, or one a person adds by link or file. Every build goes through
 * [prepare] before it is installed: its own wine binaries say whether this
 * runtime can run it on this device ([WineBuildRules.refusal]), and it is
 * named so the runtime finds it ([WineBuildRules.canonicalName]). Builds are
 * the Winlator/GameNative content package (.wcp: a tar.xz or tar.zst with a
 * profile.json), which the contents store installs as it always has.
 */
object WineBuilds {
    private const val PREFS = "droidtop_wine_builds"
    private const val KEY_ADDED = "added"

    /**
     * Checks the Wine/Proton build [ContentsManager.extraContentFile] just
     * unpacked into the contents store's staging folder, renames it when the
     * runtime would not read its name (or to [catalogId], the name the
     * catalog gave it, which keeps builds of different sources apart), and
     * returns its profile as the store will install it. Throws with a
     * sentence a person can act on. Disk.
     */
    fun prepare(context: Context, manager: ContentsManager, profile: ContentProfile, catalogId: String? = null): ContentProfile =
        try {
            check(context, manager, profile, catalogId)
        } catch (e: IOException) {
            // A refused build's unpacked tree (hundreds of MB) would wait in staging until the next install.
            ContentsManager.cleanTmpDir(context)
            throw e
        }

    private fun check(context: Context, manager: ContentsManager, profile: ContentProfile, catalogId: String?): ContentProfile {
        val staging = ContentsManager.getTmpDir(context)
        val elf = wineElf(staging, profile.wineBinPath ?: "bin")
        val arch = elf?.let { WineBuildRules.buildArch(it.machine) }
        val wanted = catalogId?.substringBeforeLast('-')
        val name = if (wanted != null) {
            wanted.takeIf { WineBuildRules.canonicalName(it, arch) == it }
                ?: throw IOException("The catalog names it $wanted, but its programs are built for ${elf?.machine ?: "something unreadable"}")
        } else {
            WineBuildRules.canonicalName(profile.verName, arch)
                ?: throw IOException("Its name \"${profile.verName}\" says no Wine or Proton version")
        }
        WineBuildRules.refusal(X86_64GuestLibs.isX86_64Host(), elf, name)?.let { throw IOException(it) }
        val code = catalogId?.substringAfterLast('-')?.toIntOrNull()?.let(WineBuildRules::runtimeVerCode)
            ?: WineBuildRules.runtimeVerCode(profile.verCode)
        if (name == profile.verName && code == profile.verCode) return profile
        val file = File(staging, ContentsManager.PROFILE_NAME)
        val json = JSONObject(file.readText())
        json.put(ContentProfile.MARK_TYPE, if (name.startsWith("proton")) "Proton" else "Wine")
        json.put(ContentProfile.MARK_VERSION_NAME, name)
        json.put(ContentProfile.MARK_VERSION_CODE, code)
        file.writeText(json.toString(2))
        return manager.readProfile(file) ?: throw IOException("its profile.json could not be rewritten")
    }

    /** The id a prepared build is chosen by ("proton-11.0-2-arm64ec-1"). */
    fun id(profile: ContentProfile): String = "${profile.verName}-${profile.verCode}"

    /** Downloads a Wine build from [url] and adds it; the line to show. Network and disk. */
    suspend fun addFromUrl(context: Context, url: String, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) return@withContext "Give a link that starts with https://"
        val file = File(context.cacheDir, "wine-build-download")
        try {
            onStatus("Downloading…")
            RuntimeDownloads.fetchUrl(trimmed, file) { onStatus("Downloading… ${(it.coerceIn(0f, 1f) * 100).toInt()}%") }
            add(context, file, trimmed, onStatus)
        } catch (e: Exception) {
            "Couldn't add it: ${e.message ?: e}"
        } finally {
            file.delete()
        }
    }

    /** Adds the Wine build archive a person picked; the line to show. Disk. */
    suspend fun addFromFile(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "wine-build-picked")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                ?: return@withContext "That file could not be opened"
            add(context, file, uri.lastPathSegment ?: uri.toString()) {}
        } catch (e: Exception) {
            "Couldn't add it: ${e.message ?: e}"
        } finally {
            file.delete()
        }
    }

    /** Builds a person added, by id, with where each came from. Disk. */
    fun added(context: Context): Map<String, String> {
        val json = runCatching { JSONObject(prefs(context).getString(KEY_ADDED, "{}")!!) }.getOrDefault(JSONObject())
        return json.keys().asSequence().associateWith { json.getString(it) }
    }

    private fun add(context: Context, archive: File, origin: String, onStatus: (String) -> Unit): String {
        val magic = archive.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
        when {
            magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() ->
                return "This is a .tar.gz, the way Linux Wine builds (GE-Proton, Kron4ek, Steam's Proton) ship. " +
                    "Linux builds need droidtop's Linux engine, which is not available yet; builds packed for Winlator or GameNative (.wcp) run here."
            magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() ->
                return "This is a zip; a Wine build is a .wcp package. Driver packages are added under Driver build."
        }
        onStatus("Checking it…")
        val manager = ContentsManager(context)
        val (profile, reason) = ManifestInstaller.extract(manager, Uri.fromFile(archive))
        if (profile == null) {
            ContentsManager.cleanTmpDir(context)
            return when (reason) {
                ContentsManager.InstallFailedReason.ERROR_NOPROFILE ->
                    "It has no profile.json, so it is not a Winlator/GameNative package (.wcp). " +
                        "A plain Linux Wine folder needs droidtop's Linux engine, which is not available yet."
                ContentsManager.InstallFailedReason.ERROR_BADTAR -> "It is not a .wcp package (a tar.xz or tar.zst archive)"
                ContentsManager.InstallFailedReason.ERROR_MISSINGFILES -> "It is missing files its profile.json names"
                ContentsManager.InstallFailedReason.ERROR_UNTRUSTPROFILE -> "Its profile.json writes outside its own folders"
                else -> "Its profile.json could not be read"
            }
        }
        if (profile.type != ContentProfile.ContentType.CONTENT_TYPE_WINE && profile.type != ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            ContentsManager.cleanTmpDir(context)
            return "This package is a ${profile.type}, not a Wine build"
        }
        val prepared = prepare(context, manager, profile)
        onStatus("Installing ${prepared.verName}…")
        if (!ManifestInstaller.finish(manager, prepared)) {
            val exists = ContentsManager.getInstallDir(context, prepared).exists()
            return if (exists) "${id(prepared)} is already on this device" else "It could not be installed"
        }
        val added = JSONObject(prefs(context).getString(KEY_ADDED, "{}") ?: "{}").put(id(prepared), origin)
        prefs(context).edit().putString(KEY_ADDED, added.toString()).apply()
        return "Added ${id(prepared)}; choose it under Wine build"
    }

    /** The ELF header of the build's wine programs (bin/wine is often a link into lib/wine/<arch>-unix). */
    private fun wineElf(root: File, binPath: String): WineBuildRules.Elf? {
        val bin = File(root, binPath)
        val candidates = listOf(File(bin, "wineserver"), File(bin, "wine"), File(bin, "wine64")) +
            (File(root, "lib/wine").listFiles { f -> f.name.endsWith("-unix") }?.map { File(it, "wine") } ?: emptyList())
        for (file in candidates) {
            if (!file.isFile) continue
            val head = file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                val n = input.read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
            WineBuildRules.elf(head)?.let { return it }
        }
        return null
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
