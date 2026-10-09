package dev.droidtop.pluginhost

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.annotation.RequiresApi
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * A contained Flutter plugin's `flutter_assets`, served to Android's AssetManager from memory (docs/plugin-api.md 5.3,
 * "Flutter assets").
 *
 * AssetManager opens an asset path itself, in libandroidfw and libziparchive, and an isolated process may not `open`
 * droidtop's file (sepolicy `neverallow isolated_app_all app_data_file_type:file open`). The guarded hooks did not reach
 * that open ("Unable to open '/droidtop-sandbox/plugin/flutter_assets.zip'", rig, v0.2.0-dev.1649, Android 13). So from
 * API 30 the assets zip that :app handed over as a descriptor is unpacked once into a memfd of this process's own, and a
 * [ResourcesLoader] with an [AssetsProvider] answers every asset as an [AssetFileDescriptor] range of that memfd. The
 * loader is added to the context's Resources before the engine is built, so the engine's AssetManager (its
 * `AAssetManager_open`) finds `flutter_assets/...` there, with nothing opened by path.
 *
 * The provider is a [java.lang.reflect.Proxy] of the interface, not a class implementing it: a class whose shape names
 * an API 30 type cannot even be loaded on API 26 (build-scripts/check_class_load_api.py), and the API 30 types appear
 * here only inside method bodies.
 */
@RequiresApi(30)
internal class ContainedAssets private constructor(private val memfd: FileDescriptor, private val index: Map<String, LongArray>) {
    val count: Int get() = index.size

    /** `AssetsProvider.loadAssetFd`: AssetManager2 asks for "assets/<name>"; the bare name is accepted too. */
    private fun loadAssetFd(path: String): AssetFileDescriptor? {
        val entry = index[path.removePrefix("assets/")] ?: return null
        return AssetFileDescriptor(ParcelFileDescriptor.dup(memfd), entry[0], entry[1])
    }

    fun installInto(context: Context) {
        val type = android.content.res.loader.AssetsProvider::class.java
        val provider = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "loadAssetFd" -> loadAssetFd(args!![0] as String)
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ContainedAssets($count files)"
                else -> null
            }
        } as android.content.res.loader.AssetsProvider
        val loader = ResourcesLoader()
        loader.addProvider(ResourcesProvider.empty(provider))
        context.resources.addLoaders(loader)
    }

    companion object {
        /** Unpacks the zip behind [zip] (entries named `flutter_assets/...`) into one memfd and indexes it. Throws on failure. */
        fun from(zip: ParcelFileDescriptor): ContainedAssets {
            check(Build.VERSION.SDK_INT >= 30) { "contained Flutter assets need Android 11 or later" }
            val memfd = Os.memfd_create("flutter_assets", 0)
            val index = HashMap<String, LongArray>()
            val input = FileInputStream(zip.fileDescriptor)
            input.channel.position(0)
            val out = FileOutputStream(memfd)
            var offset = 0L
            ZipInputStream(input.buffered()).use { entries ->
                while (true) {
                    val entry = entries.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val length = entries.copyTo(out)
                    index[entry.name] = longArrayOf(offset, length)
                    offset += length
                }
            }
            out.flush()
            return ContainedAssets(memfd, index)
        }
    }
}
