package dev.droidtop.library

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * One place a store install can go, and the room it has there: a game
 * folder the person named (Settings > Game folders, [GamesRoots]), its name
 * as a person reads it ([friendlyLocation]), and the free space of the
 * partition it sits on.
 */
data class InstallVolume(
    val name: String,
    val path: String,
    val freeBytes: Long,
    val totalBytes: Long,
)

/**
 * The places a store install can go: the person's own game folders
 * ([GamesRoots.configured]), the same folders every library walk reads
 * (docs/SPEC.md 7g, "A root is a place, not a picker result"), so a store
 * game lands where the person keeps games, other apps (Enginehost) can read
 * it, and uninstalling droidtop leaves it there. One mechanism for where
 * games live: there is no second install-location setting. droidtop's own
 * Android/data folder is never offered (the owner, 2026-10-07: "The location
 * problem is solved by the game folder system").
 *
 * Each is reported with the free space of its partition, read with [StatFs]
 * on the nearest folder that exists. The one disk read of the install offer,
 * so callers run it off the main thread. A folder whose stats cannot be read
 * is dropped rather than drawn with a zero. Empty when the person has named
 * no game folder yet.
 */
fun installVolumes(context: Context): List<InstallVolume> =
    GamesRoots.configured(context).sortedBy { it.absolutePath }.mapNotNull { root ->
        runCatching {
            var existing: File? = root
            while (existing != null && !existing.exists()) existing = existing.parentFile
            val stats = StatFs(checkNotNull(existing).path)
            InstallVolume(
                name = friendlyLocation(root.absolutePath),
                path = root.absolutePath,
                freeBytes = stats.availableBytes,
                totalBytes = stats.totalBytes,
            )
        }.getOrNull()
    }

/**
 * A folder's path as a person names its place: "SD card / Games / Folder"
 * instead of "/storage/1234-ABCD/Games/Folder". The storage root becomes its
 * name (internal storage, SD card), a path of more than three steps keeps the
 * first and the last two with a gap between, and a path under no known root
 * keeps its last three steps. The full path belongs in a tooltip. Pure.
 */
fun friendlyLocation(path: String): String {
    val parts = path.split('/').filter { it.isNotEmpty() }
    val (root, rest) = when {
        parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated" -> "Internal storage" to parts.drop(3)
        parts.size >= 2 && parts[0] == "storage" && parts[1] != "self" -> "SD card" to parts.drop(2)
        parts.size >= 3 && parts[0] == "mnt" && parts[1] == "media_rw" -> "SD card" to parts.drop(3)
        parts.isNotEmpty() && (parts[0] == "sdcard") -> "Internal storage" to parts.drop(1)
        else -> null to parts.takeLast(3)
    }
    val steps = if (rest.size > 3) listOf(rest.first(), "…") + rest.takeLast(2) else rest
    return (listOfNotNull(root) + steps).joinToString(" / ").ifEmpty { path }
}

/**
 * Whether a [downloadBytes] download fits on a volume with [freeBytes] to
 * spare. Zero or negative sizes always fit: the library does not know the
 * size yet, so there is nothing to refuse on its behalf.
 */
fun fits(downloadBytes: Long, freeBytes: Long): Boolean = downloadBytes <= freeBytes

/** "46.7 GB free of 128.0 GB", the volume's own room in one line. Pure, for the tests. */
fun freeSpaceLine(volume: InstallVolume, formatSize: (Long) -> String): String =
    "${formatSize(volume.freeBytes)} free of ${formatSize(volume.totalBytes)}"

/**
 * The consent sheet's warning when the download will not fit on the
 * chosen volume, or null when it fits or the size is not known: droidtop
 * never warns about a size the store did not name.
 */
fun notEnoughRoomLine(downloadBytes: Long, volume: InstallVolume, formatSize: (Long) -> String): String? =
    if (fits(downloadBytes, volume.freeBytes) || downloadBytes <= 0) null
    else "Not enough room on ${volume.name}: the download is ${formatSize(downloadBytes)} but only ${formatSize(volume.freeBytes)} is free"

/**
 * The consent sheet's body lines, in sheet order: what the install or
 * update takes, the room the chosen game folder has, and the warning when
 * there is not enough of it. [volume] is null for a store that picks its
 * own location (Steam): the size is named, the room is the store's to check.
 *
 * [update] is an update of a game that is already installed: the download
 * is the store's delta, which the library does not carry ([PcInfo.sizeBytes]
 * is the on-disk size then, not the delta), so an update gets the free
 * space named but no fit warning. [sizeBytes] is the download size when
 * the library knows it, 0 when it does not. Pure, for the tests.
 */
fun storeInstallOfferLines(
    update: Boolean,
    sizeBytes: Long,
    volume: InstallVolume?,
    formatSize: (Long) -> String,
): List<String> = buildList {
    add(
        when {
            update -> "A newer build is available; the store names its size as the download starts."
            sizeBytes > 0 -> "Downloads ${formatSize(sizeBytes)}."
            else -> "The store does not name a size yet."
        },
    )
    if (volume == null) return@buildList
    add("${freeSpaceLine(volume, formatSize)} on ${volume.name}.")
    if (!update) notEnoughRoomLine(sizeBytes, volume, formatSize)?.let { add(it) }
}

/**
 * What a store install says when the person has named no game folder: a
 * store game goes into one of their game folders, never droidtop's own
 * Android/data folder, so there is nowhere to put it until one is added.
 */
const val NO_GAME_FOLDER_LINE = "No game folder yet. Add one under Settings > Game folders and store games install there."

/**
 * The game folder a store install goes to when no offer picked one: the
 * folder remembered for the store while it is still one of [configured],
 * else the first of [configured] in path order, else null (no game folder
 * named). Pure.
 */
fun installFolderFor(remembered: String?, configured: List<String>): String? =
    remembered?.takeIf { it in configured } ?: configured.sorted().firstOrNull()

/**
 * Which game folder a store installs to, remembered per store: the install
 * offer's choice outlives the offer, so the next install from the same store
 * names the same folder first. A remembered folder that is no longer one of
 * the person's game folders is not offered (the offer falls back to the
 * first). The key is the store's own name ([dev.droidtop.library.stores.StoreLibrary.label]).
 */
object StoreInstallVolumePrefs {
    private const val PREFS_NAME = "droidtop_store_install_volume"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(store: String) = "volume_$store"

    /** The path this store's installs go to, or null when it has never been chosen. */
    fun remembered(context: Context, store: String): String? = prefs(context).getString(key(store), null)

    fun remember(context: Context, store: String, path: String) {
        prefs(context).edit().putString(key(store), path).apply()
    }
}
