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

    const val FLUTTER_ENGINE = "flutter/libflutter.so"
    const val FLUTTER_APP = "flutter/libapp.so"
    const val FLUTTER_ASSETS = "flutter/flutter_assets.zip"
    const val FLUTTER_DEX = "flutter/dex/"

    const val PYTHON_LIBPYTHON = "python/libpython/"
    const val PYTHON_STDLIB = "python/stdlib.zip"
    const val PYTHON_DEP = "python/dep/"
    const val PYTHON_EXT = "python/ext/"

    /** A `gpu.render` process's end of the socket to its file broker in :app ([SandboxBroker]). */
    const val BROKER = "sandbox/broker"

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
                // This device's ABI only: the libraries the plugin's System.loadLibrary will name.
                val abi = FlutterRuntimeManager.currentAbi()
                val libs = record.manifest.payload.filter { it.path.startsWith("$NATIVE_PREFIX$abi/") && it.path.endsWith(".so") }
                Result.Files(listOf(CLASSES to jar) + libs.map { it.path to File(dir, it.path) })
            }
            PluginKind.PYTHON -> {
                val script = File(dir, PLUGIN_PY)
                if (!script.isFile) return Result.Missing("its plugin.py is missing from the installed bundle")
                val runtime = PythonRuntimeManager.containedFiles(context) ?: return Result.Missing("the Python runtime is not installed")
                Result.Files(listOf(PLUGIN_PY to script) + runtime)
            }
            PluginKind.FLUTTER_EMBED -> {
                val engine = FlutterRuntimeManager.libflutterSoPath(context) ?: return Result.Missing("the Flutter runtime is not installed")
                val pinned = FlutterRuntimeManager.pinnedVersion(context)
                if (record.manifest.runtimeVersion != pinned) {
                    return Result.Missing("its runtimeVersion (${record.manifest.runtimeVersion}) does not match the installed Flutter runtime ($pinned)")
                }
                val libapp = File(dir, "lib/${FlutterRuntimeManager.currentAbi()}/libapp.so")
                if (!libapp.isFile) return Result.Missing("its libapp.so for this device is missing from the installed bundle")
                // The assets as the zip AssetManager reads, built once per installed bundle, outside the plugin's own data.
                val zip = File(context.cacheDir, "plugin-assets/${record.manifest.id}-${record.archiveDigest.take(16)}.zip")
                if (!zip.isFile) FlutterAssets.repack(dir, zip)
                val dex = File(dir, "dex").listFiles { f -> f.isFile && f.extension == "dex" }.orEmpty().sortedBy { it.name }
                Result.Files(
                    listOf(FLUTTER_ENGINE to engine, FLUTTER_APP to libapp, FLUTTER_ASSETS to zip) +
                        dex.map { FLUTTER_DEX + it.name to it },
                )
            }
        }
    }
}
