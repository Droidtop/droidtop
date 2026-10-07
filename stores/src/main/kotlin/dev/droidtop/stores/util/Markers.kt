package dev.droidtop.stores.util

import java.io.File
import timber.log.Timber

/**
 * The two files a store install keeps in its folder to say how far it got:
 * a download running, a download finished. GameNative's `Marker` and
 * `MarkerUtils` (GPL-3.0), lifted with the stores that write them, with the
 * same file names so an install GameNative made reads the same.
 */
internal enum class Marker(val fileName: String) {
    DOWNLOAD_COMPLETE_MARKER(".download_complete"),
    DOWNLOAD_IN_PROGRESS_MARKER(".download_in_progress"),
}

internal object MarkerUtils {
    fun hasMarker(dirPath: String, type: Marker): Boolean = File(dirPath, type.fileName).exists()

    fun addMarker(dirPath: String, type: Marker): Boolean {
        val dir = File(dirPath)
        val marker = File(dir, type.fileName)
        if (marker.exists()) return true
        if (!dir.exists()) {
            Timber.e("Marker ${type.fileName} at $dirPath not added as directory not found")
            return false
        }
        return try {
            marker.createNewFile()
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to add marker ${type.fileName} at $dirPath")
            false
        }
    }

    fun removeMarker(dirPath: String, type: Marker): Boolean {
        val marker = File(dirPath, type.fileName)
        return !marker.exists() || marker.delete()
    }
}
