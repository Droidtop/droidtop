package dev.droidtop.pluginhost

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * The file broker of a plugin process droidtop loads from descriptors, contained and `gpu.render` alike (docs/plugin-api.md
 * 5.3, "Files by path"; `native/src/plugin_broker.c`, the sandbox library's broker in `vendor/sandbox`). Once the process is
 * locked down, the calls it makes that name a path under its broker's care are answered here, in :app, from [rules] and
 * [aliases]:
 *
 * - Droidtop's own private folder, wherever the plugin's code (or Android's framework, or ART, or a Flutter or Python
 *   library) names it, is the plugin's own folder instead ([appDataFor]): `getFilesDir`, `getCacheDir`, `app_flutter`,
 *   `databases` and the rest are real, writable folders of the plugin's, and droidtop's own files are never among them.
 * - A `gpu.render` process, under droidtop's UID, sends every path here, so its rules also hold the system's read-only
 *   code and data, droidtop's APK and the device nodes a graphics driver opens. A contained process sends only its
 *   private folder ([privateRoots], the claims of `SBX_LOCK_PASSTHROUGH`); every other path stays the kernel's, where
 *   its isolated UID and SELinux already decide.
 *
 * Plugin files keep coming as descriptors (the guarded hooks' virtual paths, local files of the library) and plugin data
 * through the broker API, as before. Every refusal is logged under `droidtop.sandbox` with the plugin's id.
 */
internal object SandboxBroker {
    private const val READ = 1
    private const val WRITE = 2

    init {
        System.loadLibrary("droidtoppy")
    }

    @JvmStatic private external fun nativeServe(plugin: String, paths: Array<String>, modes: IntArray, aliasFrom: Array<String>, aliasTo: Array<String>): Int

    /**
     * The names of droidtop's private folders as code in a plugin process may spell them, credential-encrypted first, then
     * device-encrypted: what the broker answers for in a contained process, and what it maps to the plugin's own folder.
     */
    fun privateRoots(info: ApplicationInfo): Pair<List<String>, List<String>> {
        val user = android.os.Process.myUid() / 100_000
        val credential = listOfNotNull(info.dataDir, "/data/data/${info.packageName}", "/data/user/$user/${info.packageName}").distinct()
        val device = listOfNotNull(info.deviceProtectedDataDir, "/data/user_de/$user/${info.packageName}").distinct()
        return credential to device
    }

    /** Every name in [privateRoots], for the lockdown's claims. */
    fun claims(info: ApplicationInfo): Array<String> = privateRoots(info).let { (credential, device) -> (credential + device).toTypedArray() }

    /** The plugin's own stand-in for droidtop's private folder: credential-encrypted and device-encrypted halves. */
    fun appDataFor(context: Context, pluginId: String): Pair<File, File> {
        val data = PluginStore.dataDirFor(context, pluginId)
        return File(data, "app-data").apply { mkdirs() } to File(data, "app-data-de").apply { mkdirs() }
    }

    /**
     * The rules for [pluginId]'s process of [tier]. An open process reads the system and the few devices ART needs;
     * the graphics devices only with the `gpu.render` hole in [holes] (docs/plugin-api.md 5.3, "Open processes").
     */
    fun rules(context: Context, pluginId: String, tier: PluginTier, holes: Set<String> = emptySet()): Map<String, Int> {
        val rules = LinkedHashMap<String, Int>()
        val (credential, device) = appDataFor(context, pluginId)
        rules[credential.path] = READ or WRITE
        rules[device.path] = READ or WRITE
        if (tier == PluginTier.OPEN) {
            val info = context.applicationInfo
            listOf(
                "/system", "/system_ext", "/product", "/vendor", "/odm", "/apex", "/linkerconfig",
                "/data/dalvik-cache", "/data/fonts", "/data/resource-cache", "/proc", "/sys", "/dev/__properties__",
                // libbinder reads which driver features the kernel has (extended_error) when it starts.
                "/dev/binderfs/features",
                File(info.sourceDir).parent ?: info.sourceDir,
                info.nativeLibraryDir,
            ).forEach { rules[it] = READ }
            // Devices ART opens after the fact (/dev/ashmem<boot id> is allowed by the broker's decider).
            listOf("/dev/null", "/dev/zero", "/dev/random", "/dev/urandom", "/dev/ashmem").forEach { rules[it] = READ or WRITE }
            // The graphics driver's devices, only for the gpu.render hole: a networked process does not get the GPU.
            if (PluginTiers.GPU_RENDER in holes) {
                listOf(
                    "/dev/ion", "/dev/dma_heap", "/dev/kgsl-3d0", "/dev/dri", "/dev/mali0", "/dev/goldfish_pipe",
                    "/dev/goldfish_pipe_dprctd", "/dev/goldfish_sync", "/dev/goldfish_address_space", "/dev/qemu_pipe",
                ).forEach { rules[it] = READ or WRITE }
            }
        }
        return rules
    }

    /** Droidtop's private folders, by every name, mapped to [pluginId]'s own. */
    fun aliases(context: Context, pluginId: String): List<Pair<String, String>> {
        val (credential, device) = privateRoots(context.applicationInfo)
        val (ownCredential, ownDevice) = appDataFor(context, pluginId)
        return credential.map { it to ownCredential.path } + device.map { it to ownDevice.path }
    }

    /** Starts a broker for [pluginId]'s process of [tier] with [holes]; returns that process's end of its socket, or null. */
    fun serve(context: Context, pluginId: String, tier: PluginTier, holes: Set<String> = emptySet()): ParcelFileDescriptor? {
        val rules = rules(context, pluginId, tier, holes)
        val aliases = aliases(context, pluginId)
        val fd = nativeServe(
            pluginId, rules.keys.toTypedArray(), rules.values.toIntArray(),
            aliases.map { it.first }.toTypedArray(), aliases.map { it.second }.toTypedArray(),
        )
        if (fd < 0) {
            android.util.Log.w("droidtop.sandbox", "$pluginId: the file broker did not start (errno ${-fd})")
            return null
        }
        return ParcelFileDescriptor.adoptFd(fd)
    }
}
