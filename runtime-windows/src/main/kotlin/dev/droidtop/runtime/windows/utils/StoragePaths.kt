package dev.droidtop.runtime.windows.utils

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager

/**
 * The app's own storage roots the folder scanner and the Steam carry-over
 * read: GameNative's `DownloadService` path fields (GPL-3.0), without the
 * service. Filled once by [dev.droidtop.runtime.windows.WindowsBackbone];
 * empty until then, which reads as "no such folder".
 */
object StoragePaths {
    @Volatile
    var baseDataDirPath: String = ""
        private set

    /** `Android/data/<package>` on the primary volume, the parent of the external files dir. */
    @Volatile
    var baseExternalAppDirPath: String = ""
        private set

    /** Every mounted non-primary volume (SD card, USB): its app folder and its public install root. */
    @Volatile
    var externalVolumePaths: List<String> = emptyList()
        private set

    /** Disk work (volume listing): off the main thread. */
    fun init(context: Context) {
        baseDataDirPath = context.dataDir.path
        baseExternalAppDirPath = context.getExternalFilesDir(null)?.parentFile?.path ?: ""
        val sm = context.getSystemService(StorageManager::class.java)
        externalVolumePaths = StorageUtils.getAllExternalFilesDirs(context)
            .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
            .filter { sm?.getStorageVolume(it)?.isPrimary != true }
            .flatMap { dir -> listOfNotNull(dir.absolutePath, StorageUtils.publicInstallRoot(dir)?.absolutePath) }
            .distinct()
    }
}
