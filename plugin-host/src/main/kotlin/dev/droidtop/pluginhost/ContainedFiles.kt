package dev.droidtop.pluginhost

import android.content.Context
import java.io.File

/**
 * The files :app opens for a contained plugin and hands to its isolated process as descriptors (docs/plugin-api.md 5.3):
 * that process can open none of them itself. The names are the keys [PluginSandboxService] reads them by.
 */
internal object ContainedFiles {
    const val CLASSES = "classes.jar"
    const val PLUGIN_PY = "plugin.py"

    /** Any file under this prefix is one of the bundle's native libraries; a contained plugin is refused when one is handed over. */
    const val NATIVE_PREFIX = "lib/"

    const val PYTHON_LIBPYTHON = "python/libpython/"
    const val PYTHON_STDLIB = "python/stdlib.zip"
    const val PYTHON_DEP = "python/dep/"
    const val PYTHON_EXT = "python/ext/"

    sealed interface Result {
        data class Files(val files: List<Pair<String, File>>) : Result
        data class Missing(val reason: String) : Result
    }

    /**
     * Every file [record]'s kind needs, by name. Reads the disk and may build the Python standard library zip the first
     * time ([PythonRuntimeManager.containedFiles]): call it off the main thread.
     */
    fun forRecord(context: Context, record: PluginRecord): Result {
        val dir = PluginStore.payloadDirFor(context, record.manifest.id)
        return when (record.manifest.kind) {
            PluginKind.NATIVE_BUNDLE -> {
                val jar = File(dir, CLASSES)
                if (!jar.isFile) return Result.Missing("its classes.jar is missing from the installed bundle")
                val libs = record.manifest.payload.filter { it.path.startsWith(NATIVE_PREFIX) && it.path.endsWith(".so") }
                Result.Files(listOf(CLASSES to jar) + libs.map { it.path to File(dir, it.path) })
            }
            PluginKind.PYTHON -> {
                val script = File(dir, PLUGIN_PY)
                if (!script.isFile) return Result.Missing("its plugin.py is missing from the installed bundle")
                val runtime = PythonRuntimeManager.containedFiles(context) ?: return Result.Missing("the Python runtime is not installed")
                Result.Files(listOf(PLUGIN_PY to script) + runtime)
            }
            PluginKind.FLUTTER_EMBED -> Result.Missing("a Flutter plugin cannot run contained; it needs full access")
        }
    }
}
