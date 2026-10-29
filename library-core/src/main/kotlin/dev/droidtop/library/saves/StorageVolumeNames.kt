package dev.droidtop.library.saves

import android.content.Context
import android.os.storage.StorageManager

/**
 * The names Android gives the storage volumes ("SanDisk SD card", "USB drive"), keyed by the volume's id as it appears
 * in a path (`/storage/7EF7-E477/...`), for [SaveLines.folder] (Droidtop/tracker#672). A volume without an id (the
 * internal storage) or that cannot be read is left out, so its path keeps the plain "SD card".
 */
object StorageVolumeNames {
    fun of(context: Context): Map<String, String> = runCatching {
        val storage = context.getSystemService(StorageManager::class.java) ?: return emptyMap()
        storage.storageVolumes.mapNotNull { volume ->
            val id = volume.uuid ?: return@mapNotNull null
            val name = volume.getDescription(context)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id.uppercase() to name
        }.toMap()
    }.getOrDefault(emptyMap())
}
