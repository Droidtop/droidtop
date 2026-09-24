package dev.droidtop.runtime

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * One mounted shared-storage volume: [root] on the Android side, [name]
 * the directory it gets under [ContainerLayout.SHARED_STORAGE_DIR]. The
 * device's own storage is `primary`; a card or USB drive keeps the name
 * Android mounts it under (`/storage/1234-ABCD` is `1234-ABCD`), so a path
 * reads the same inside a container as in a file manager.
 */
data class SharedVolume(val name: String, val root: File) {
    companion object {
        const val PRIMARY = "primary"

        /**
         * Every volume that is mounted now and that droidtop can read.
         * Found through the app's own per-volume external directories
         * (`getExternalFilesDirs`, one per mounted volume on every API
         * level droidtop supports), each cut back to its volume root at
         * `Android/data`; that avoids `StorageVolume.getDirectory`, which
         * needs API 30. A volume droidtop cannot read (no all-files access
         * granted) is left out rather than bound as an empty directory.
         */
        fun mounted(context: Context): List<SharedVolume> =
            context.getExternalFilesDirs(null)
                .filterNotNull()
                .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
                .mapNotNull { volumeRoot(it) }
                .distinctBy { it.absolutePath }
                .filter { it.canRead() }
                .map { root -> SharedVolume(nameFor(root), root) }

        /** `<root>/Android/data/<package>/files` to `<root>`. */
        internal fun volumeRoot(appDir: File): File? {
            val path = appDir.absolutePath
            val cut = path.indexOf("/Android/data/")
            return if (cut > 0) File(path.substring(0, cut)) else null
        }

        /** `/storage/emulated/<user>` is [PRIMARY]; anything else is its mount directory's name. */
        internal fun nameFor(root: File): String =
            if (root.absolutePath.startsWith("/storage/emulated/")) PRIMARY else root.name
    }
}
