package dev.droidtop.pluginhost

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * The file broker of a `gpu.render` plugin's process (docs/plugin-api.md 5.3, "The graphics tier"; `native/src/
 * plugin_broker.c`, the sandbox library's broker in `vendor/sandbox`). That process runs under droidtop's UID, so once
 * it is locked down every call it makes that names a path is answered here, in :app, from [rules]: the system's
 * read-only code and data, droidtop's APK and the device nodes a graphics driver opens. None of droidtop's private
 * data (databases, preferences, sign-in tokens, other plugins' folders, caches) is in them. Plugin files keep coming as
 * descriptors and plugin data through the broker API, as for every contained plugin.
 */
internal object SandboxBroker {
    private const val READ = 1
    private const val WRITE = 2

    init {
        System.loadLibrary("droidtoppy")
    }

    @JvmStatic private external fun nativeServe(plugin: String, paths: Array<String>, modes: IntArray): Int

    /** The rules for [context]'s plugin processes. */
    fun rules(context: Context): Map<String, Int> {
        val rules = LinkedHashMap<String, Int>()
        val info = context.applicationInfo
        listOf(
            "/system", "/system_ext", "/product", "/vendor", "/odm", "/apex", "/linkerconfig",
            "/data/dalvik-cache", "/data/fonts", "/data/resource-cache", "/proc", "/sys", "/dev/__properties__",
            File(info.sourceDir).parent ?: info.sourceDir,
            info.nativeLibraryDir,
        ).forEach { rules[it] = READ }
        // Devices ART and a graphics driver open after the fact (/dev/ashmem<boot id> is allowed by the broker's decider).
        listOf(
            "/dev/null", "/dev/zero", "/dev/random", "/dev/urandom", "/dev/ashmem", "/dev/ion", "/dev/dma_heap",
            "/dev/kgsl-3d0", "/dev/dri", "/dev/mali0", "/dev/goldfish_pipe", "/dev/goldfish_pipe_dprctd",
            "/dev/goldfish_sync", "/dev/goldfish_address_space", "/dev/qemu_pipe",
        ).forEach { rules[it] = READ or WRITE }
        return rules
    }

    /** Starts a broker for [pluginId]'s graphics process; returns that process's end of its socket, or null. */
    fun serve(context: Context, pluginId: String): ParcelFileDescriptor? {
        val rules = rules(context)
        val fd = nativeServe(pluginId, rules.keys.toTypedArray(), rules.values.toIntArray())
        if (fd < 0) {
            android.util.Log.w("droidtop.sandbox", "$pluginId: the file broker did not start (errno ${-fd})")
            return null
        }
        return ParcelFileDescriptor.adoptFd(fd)
    }
}
