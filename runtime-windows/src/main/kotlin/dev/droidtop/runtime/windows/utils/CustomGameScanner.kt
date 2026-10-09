package dev.droidtop.runtime.windows.utils

import android.net.Uri
import dev.droidtop.runtime.windows.DroidtopGameIdStore
import dev.droidtop.runtime.windows.PrefManager
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.launch
import timber.log.Timber

/** One game folder the scanner knows: its app id ("CUSTOM_GAME_<n>") and its folder's name. */
data class ScannedGame(val appId: String, val name: String)

/**
 * The PC folder-game scanner: which folders are games, the numeric id each
 * keeps, and the art a folder carries. Adapted from GameNative's
 * app/gamenative/utils/CustomGameScanner.kt (GPL-3.0), cut to what droidtop's
 * PC library reads (PcLibrary): the Steam-library import of a folder, the
 * permission prompts, the import destinations and the container-config icon
 * lookups were GameNative's and are not carried. The ids live in droidtop's
 * own store ([DroidtopGameIdStore], docs/SPEC.md 7g, tracker#269), never in a
 * file inside the game folder; the folder lists stay in the same preferences
 * GameNative kept them in ([PrefManager]).
 */
object CustomGameScanner {

    private const val CUSTOM_GAME = "CUSTOM_GAME"

    /**
     * GameNative's own CustomGames folders: the public root and the app sandbox
     * of every volume, the external folder it was set to, and the internal one.
     * Their immediate subfolders are games.
     */
    private val managedRootPaths: List<String>
        get() {
            val roots = LinkedHashSet<String>()
            val appDir = StoragePaths.baseExternalAppDirPath
            if (appDir.isNotEmpty()) {
                StorageUtils.publicInstallRoot(File(appDir))?.let { roots.add(File(it, "CustomGames").absolutePath) }
                roots.add(File(appDir, "CustomGames").absolutePath)
            }
            val external = PrefManager.externalStoragePath
            if (external.isNotBlank()) {
                roots.add(File(external, "CustomGames").absolutePath)
            }
            StoragePaths.externalVolumePaths
                .filterNot { it.contains("/Android/data/") }
                .forEach { roots.add(File(it, "CustomGames").absolutePath) }
            if (StoragePaths.baseDataDirPath.isNotEmpty()) {
                roots.add(File(StoragePaths.baseDataDirPath, "CustomGames").absolutePath)
            }
            return roots.toList()
        }

    /** [managedRootPaths] plus the scan roots the person gave it ([PrefManager.customGameScanRoots]). */
    private val scanRootPaths: List<String>
        get() = (managedRootPaths + PrefManager.customGameScanRoots.map { File(it).absolutePath }).distinct()

    /**
     * A folder's icon: a SteamGridDB logo it carries, else the icon extracted
     * from its one executable, else a nearby image. Null when there is none.
     */
    fun findIconFileForCustomGame(appId: String): String? {
        val folderPath = getFolderPathFromAppId(appId) ?: return null
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return null

        val steamGridLogo = folder.listFiles { file ->
            file.isFile && file.name.startsWith("steamgriddb_logo", ignoreCase = true) &&
                (
                    file.name.endsWith(".png", ignoreCase = true) ||
                        file.name.endsWith(".jpg", ignoreCase = true) ||
                        file.name.endsWith(".webp", ignoreCase = true)
                    )
        }?.firstOrNull()
        if (steamGridLogo != null) return steamGridLogo.absolutePath

        val uniqueExeRel = findUniqueExeRelativeToFolder(folder)
        extractedIcon(folder, idOf(appId), uniqueExeRel)?.let { return it.absolutePath }
        return findNearbyImageIcon(folder, uniqueExeRel)
    }

    /** A cover the person put in the folder ("coverv", then "cover"), as a file:// URI. */
    fun findCapsuleCoverForCustomGame(appId: String): String? {
        val folderPath = getFolderPathFromAppId(appId) ?: return null
        return findCoverByBaseNames(File(folderPath), listOf("coverv", "cover"))
    }

    private fun findCoverByBaseNames(folder: File, baseNames: List<String>): String? {
        if (!folder.exists() || !folder.isDirectory) return null
        val extensions = listOf("png", "jpg", "jpeg", "webp")
        val files = folder.listFiles { f -> f.isFile } ?: return null
        for (base in baseNames) {
            val match = files.filter { file ->
                extensions.any { ext -> file.name.equals("$base.$ext", ignoreCase = true) }
            }.minByOrNull { file ->
                val ext = file.name.substringAfterLast('.', "").lowercase()
                extensions.indexOf(ext).let { if (it == -1) Int.MAX_VALUE else it }
            }
            if (match != null) return Uri.fromFile(match).toString()
        }
        return null
    }

    private fun findNearbyImageIcon(folder: File, uniqueExeRel: String?): String? {
        fun File.icoFiles(): List<File> = this.listFiles { f ->
            f.isFile && (f.name.endsWith(".ico", ignoreCase = true) || f.name.endsWith(".png", ignoreCase = true))
        }?.toList() ?: emptyList()

        val rootIcons = folder.icoFiles()
        val subdirIcons = folder.listFiles { f -> f.isDirectory }?.flatMap { it.icoFiles() } ?: emptyList()
        val allIcons = (rootIcons + subdirIcons)
        if (allIcons.isEmpty()) return null

        val exeBase = uniqueExeRel?.substringAfterLast('/')?.substringBeforeLast('.')
        val extractedIcons = allIcons.filter { it.name.endsWith(".extracted.ico", ignoreCase = true) }
        if (extractedIcons.isNotEmpty()) {
            if (extractedIcons.size == 1) return extractedIcons.first().absolutePath
            if (!exeBase.isNullOrEmpty()) {
                extractedIcons.firstOrNull {
                    it.nameWithoutExtension.replace(".extracted", "").equals(exeBase, ignoreCase = true)
                }?.let { return it.absolutePath }
            }
            return extractedIcons.first().absolutePath
        }
        if (!exeBase.isNullOrEmpty()) {
            allIcons.firstOrNull { it.nameWithoutExtension.equals(exeBase, ignoreCase = true) }?.let { return it.absolutePath }
        }
        allIcons.firstOrNull { it.name.contains("icon", ignoreCase = true) }?.let { return it.absolutePath }
        val distinct = allIcons.distinctBy { it.absolutePath }
        return if (distinct.size == 1) distinct.first().absolutePath else null
    }

    /**
     * The folder's executable, relative to it, when there is exactly one
     * (uninstallers aside) in the folder or across its immediate subfolders.
     */
    fun findUniqueExeRelativeToFolder(folder: File): String? {
        if (!folder.exists() || !folder.isDirectory) return null
        val candidates = mutableListOf<String>()
        folder.listFiles { f ->
            f.isFile && f.name.endsWith(".exe", ignoreCase = true) && !f.name.startsWith("unins", ignoreCase = true)
        }?.forEach { f -> candidates.add(f.name) }
        val subDirs = folder.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (sd in subDirs) {
            sd.listFiles { f ->
                f.isFile && f.name.endsWith(".exe", ignoreCase = true) && !f.name.startsWith("unins", ignoreCase = true)
            }?.forEach { f -> candidates.add(sd.name + "/" + f.name) }
        }
        val unique = candidates.distinct()
        return if (unique.size == 1) unique.first() else null
    }

    /** Every folder the scanner knows: its manual folders and each scan root's subfolders. */
    fun scanAsLibraryItems(): List<ScannedGame> {
        val items = mutableListOf<ScannedGame>()
        val seen = mutableSetOf<String>()
        for (folderPath in candidateFolders()) {
            val item = createLibraryItemFromFolder(folderPath)
            if (item != null && seen.add(item.appId)) items.add(item)
        }
        return items
    }

    private fun candidateFolders(): Set<String> {
        val folders = LinkedHashSet<String>()
        folders.addAll(PrefManager.customGameManualFolders)
        for (root in scanRootPaths) {
            File(root).listFiles { f -> f.isDirectory }?.forEach { folders.add(it.absolutePath) }
        }
        return folders
    }

    private fun handleCustomGameDetection(folder: File, idPart: Int) {
        CustomGameCache.addEntry(idPart, folder.absolutePath)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                extractedIcon(folder, idPart, findUniqueExeRelativeToFolder(folder))
            } catch (e: Exception) {
                Timber.tag("CustomGameScanner").d(e, "Icon extraction failed for ${folder.name}")
            }
        }
    }

    /**
     * The icon of the folder's one executable, extracted into droidtop's own
     * cache (never into the game folder: docs/SPEC.md 7g, tracker#269) and
     * reused while the executable is not newer. Null when the folder has no
     * single executable or its icon cannot be read.
     */
    private fun extractedIcon(folder: File, idPart: Int, uniqueExeRel: String?): File? {
        if (uniqueExeRel.isNullOrEmpty() || StoragePaths.baseDataDirPath.isEmpty()) return null
        val exeFile = File(folder, uniqueExeRel.replace('/', File.separatorChar))
        if (!exeFile.exists()) return null
        val outIco = File(File(StoragePaths.baseDataDirPath, "cache/pc-icons"), "$idPart.ico")
        if (outIco.exists() && outIco.lastModified() >= exeFile.lastModified()) return outIco
        outIco.parentFile?.mkdirs()
        return try {
            if (ExeIconExtractor.tryExtractMainIcon(exeFile, outIco)) outIco else null
        } catch (e: Exception) {
            null
        }
    }

    private fun idOf(appId: String): Int = appId.removePrefix("${CUSTOM_GAME}_").toIntOrNull() ?: 0

    /** The game [folderPath] is, with its id; null when it is not a folder. */
    fun createLibraryItemFromFolder(folderPath: String): ScannedGame? {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return null
        val idPart = getOrGenerateGameId(folder)
        handleCustomGameDetection(folder, idPart)
        return ScannedGame(appId = "${CUSTOM_GAME}_$idPart", name = folder.name)
    }

    private fun getOrRebuildCache(): Map<Int, String> =
        CustomGameCache.getOrRebuildCache(
            getManualFolders = { candidateFolders() },
            readGameIdFromFile = { folder -> DroidtopGameIdStore.read(folder) },
        )

    private fun getAllExistingGameIds(excludeFolder: File? = null): Set<Int> {
        val cache = getOrRebuildCache()
        if (excludeFolder != null) {
            val excludeId = DroidtopGameIdStore.read(excludeFolder)
                ?: abs(excludeFolder.absolutePath.hashCode()).let { if (it == 0) 1 else it }
            return cache.keys.filter { it != excludeId }.toSet()
        }
        return cache.keys.toSet()
    }

    /**
     * The folder's remembered id, unless another live folder owns it (a copy
     * of a game folder carries its marker along); otherwise a new one from the
     * folder's path, made unique among the known games and remembered.
     */
    private fun getOrGenerateGameId(folder: File): Int {
        val storedId = DroidtopGameIdStore.read(folder)
        if (storedId != null) {
            val owner = getOrRebuildCache()[storedId]
            if (owner == null || owner == folder.absolutePath || !File(owner).isDirectory) {
                return storedId
            }
        }
        var candidateId = abs(folder.absolutePath.hashCode()).let { if (it == 0) 1 else it }
        val existingIds = if (storedId != null) getOrRebuildCache().keys.toSet() else getAllExistingGameIds(excludeFolder = folder)
        if (candidateId in existingIds) {
            var counter = 1
            while (candidateId + counter in existingIds) counter++
            candidateId += counter
        }
        DroidtopGameIdStore.write(folder, candidateId)
        return candidateId
    }

    /**
     * A game id no known folder holds and [isFree] accepts, for a game whose folder does not exist yet:
     * a Windows installer's prefix is made under the id its game will have ([adopt]), and a folder
     * game's prefix is the one of its id (docs/SPEC.md 7c, "Install a new game").
     */
    fun reserveGameId(isFree: (Int) -> Boolean): Int {
        val taken = getOrRebuildCache().keys
        repeat(1000) {
            val id = 1_000_000 + kotlin.random.Random.nextInt(1_000_000_000)
            if (id !in taken && isFree(id)) return id
        }
        error("no free game id")
    }

    /**
     * Makes [folder] the game with id [gameId]: remembered in droidtop's id store, told to the scanner as
     * a game folder, and returns its app id, or null when another folder holds that id. Waits for the
     * scanner's folder list to take the folder (its preference is written in the background, and a
     * library read straight after must see it).
     */
    suspend fun adopt(folder: File, gameId: Int): String? {
        if (!folder.isDirectory) return null
        val holder = getOrRebuildCache()[gameId]
        if (holder != null && holder != folder.absolutePath && File(holder).isDirectory) return null
        PrefManager.customGameManualFolders = PrefManager.customGameManualFolders + folder.absolutePath
        DroidtopGameIdStore.write(folder, gameId)
        CustomGameCache.invalidate()
        repeat(40) {
            if (folder.absolutePath in PrefManager.customGameManualFolders) return@repeat
            kotlinx.coroutines.delay(50)
        }
        return createLibraryItemFromFolder(folder.absolutePath)?.appId?.takeIf { it == "${CUSTOM_GAME}_$gameId" }
    }

    /** The folder of the game with numeric id [gameId], or null. */
    fun findCustomGameById(gameId: Int): String? {
        val folderPath = getOrRebuildCache()[gameId] ?: return null
        val folder = File(folderPath)
        if (folder.exists() && folder.isDirectory) return folderPath
        CustomGameCache.invalidate()
        return getOrRebuildCache()[gameId]
    }

    private fun getFolderPathFromAppId(appId: String): String? {
        if (!appId.startsWith("${CUSTOM_GAME}_")) return null
        val id = appId.removePrefix("${CUSTOM_GAME}_").toIntOrNull() ?: return null
        return findCustomGameById(id)
    }
}
