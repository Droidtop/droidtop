package dev.droidtop.library

import android.content.Context
import android.os.StatFs

/**
 * One place a store install can go, and the room it has there
 * (Droidtop/tracker#227): the volume's name as a person reads it, an
 * app-owned directory on it (stable, and the one [StatFs] needs to read
 * the partition's free space from), and that space now.
 */
data class InstallVolume(
    val name: String,
    val path: String,
    val freeBytes: Long,
    val totalBytes: Long,
)

/**
 * The volumes a store install can go to, primary storage first: the
 * device's internal storage plus every mounted SD card, each reported as
 * the free space of the partition its app-owned directory sits on.
 *
 * [Context.getExternalFilesDirs] rather than `StorageManager`'s
 * deprecated `getStorageVolumes` or a `MANAGE_EXTERNAL_STORAGE` walk of
 * `/storage`: the app's own directory on each volume is all a free-space
 * read needs, it is the scoped-storage half of the "Game folders" model,
 * and it needs no permission at all (docs/SPEC.md 7i, Droidtop/tracker#227).
 *
 * The one disk read of a consent sheet is this [StatFs], and it must run
 * off the main thread: callers wrap it in `withContext(Dispatchers.IO)`.
 * A volume whose stats cannot be read is dropped rather than drawn with
 * a zero, so the sheet can never say "0 GB free" for a full-but-readable
 * card.
 */
fun installVolumes(context: Context): List<InstallVolume> =
    context.getExternalFilesDirs(null).filterNotNull().mapIndexedNotNull { index, dir ->
        runCatching {
            val stats = StatFs(dir.path)
            InstallVolume(
                name = when (index) {
                    0 -> "Internal storage"
                    // Two SD slots at once is a phone story, not a handheld's,
                    // but the second card still gets its own name.
                    else -> "SD card${if (index > 2) " ${index - 1}" else ""}"
                },
                path = dir.path,
                freeBytes = stats.availableBytes,
                totalBytes = stats.totalBytes,
            )
        }.getOrNull()
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
 * update takes, the room the chosen volume has, and the warning when
 * there is not enough of it.
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
    volume: InstallVolume,
    formatSize: (Long) -> String,
): List<String> = buildList {
    add(
        when {
            update -> "A newer build is available; the store names its size as the download starts."
            sizeBytes > 0 -> "Downloads ${formatSize(sizeBytes)}."
            else -> "The store does not name a size yet."
        },
    )
    add("${freeSpaceLine(volume, formatSize)} on ${volume.name}.")
    if (!update) notEnoughRoomLine(sizeBytes, volume, formatSize)?.let { add(it) }
}

/**
 * Which volume a store installs to, remembered per store
 * (Droidtop/tracker#227): the consent sheet's choice outlives the sheet,
 * so the next install from the same store names the same volume first,
 * "Internal storage" (the store's own default) until one is chosen.
 *
 * The key is the store's own display name ([PcStoreNames]): the choice is
 * the person's, per store, exactly the granularity the sheet offers. The
 * remembered value is the volume's app-owned directory, which is stable
 * for the life of the install and dies with it on uninstall, so a stale
 * path can never point at another app's files.
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
