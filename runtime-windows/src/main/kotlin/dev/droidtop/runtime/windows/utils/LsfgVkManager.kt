package dev.droidtop.runtime.windows.utils

import android.content.Context
import android.os.Looper
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.core.envvars.EnvVars
import dev.droidtop.library.stores.StoreLibraries
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import java.util.Locale
import kotlin.jvm.JvmStatic

/**
 * LSFG frame generation (docs/SPEC.md 5c, "Frame generation"): the lsfg-vk
 * Vulkan implicit layer, which hooks the game's swapchain presentation inside
 * the container's Vulkan driver and runs Lossless Scaling's frame generation
 * on it. No overlay and no screen capture.
 *
 * The layer is code droidtop ships (`liblsfg-vk-layer.so`, both ABIs, built by
 * `lsfg-vk-layer.yml`). What it runs is Lossless Scaling's own `Lossless.dll`,
 * which droidtop never downloads or ships: it is read from the person's own
 * install of Lossless Scaling (Steam app 993090) as droidtop's Steam
 * installed it, and copied into the prefix's home at launch. Without that
 * install the option is not offered ([losslessFolder]) and nothing here runs.
 *
 * A launch ([applyLaunchEnv]) puts the layer, a manifest naming it and the DLL
 * into the prefix, writes the layer's `conf.toml`, and points the Vulkan
 * loader at them with `VK_LAYER_PATH`. When frame generation is off the
 * manifest is removed, so the loader cannot find a stale layer.
 */
object LsfgVkManager {
    private const val TAG = "LsfgVkManager"

    /** Lossless Scaling's Steam app id. */
    const val LOSSLESS_SCALING_APP_ID = "993090"
    private const val LOSSLESS_DLL_NAME = "Lossless.dll"

    // Paths inside the prefix's home (the container's root dir).
    private const val CONFIG_RELATIVE_PATH = ".config/lsfg-vk/conf.toml"
    private const val LIB_RELATIVE_DIR = ".local/lib"
    private const val LAYER_RELATIVE_DIR = ".local/share/vulkan/implicit_layer.d"
    private const val DLL_RELATIVE_DIR = ".local/share/lsfg-vk"
    private const val LIB_FILENAME = "liblsfg-vk-layer.so"
    private const val MANIFEST_FILENAME = "VkLayer_LS_frame_generation.json"

    // implicit_layer.d back up to lib/.
    private const val MANIFEST_LIBRARY_PATH = "../../../lib/$LIB_FILENAME"

    // Under Wine /proc/self/exe is the Wine loader, so the layer matches the
    // [[game]] entry by this identifier, passed as LSFG_PROCESS.
    private const val PROCESS_EXE_IDENTIFIER = "droidtop-lsfg"

    /** Container extras (launch-overridable per game, `Container.LAUNCH_OVERRIDE_KEYS`). */
    const val EXTRA_ARMED = "lsfgEnabled"
    const val EXTRA_MULTIPLIER = "lsfgMultiplier"

    private const val ENV_DISABLE = "DISABLE_LSFG"
    private const val ENV_CONFIG = "LSFG_CONFIG"
    private const val ENV_PROCESS = "LSFG_PROCESS"

    /** The multipliers offered: 2x to 4x, as the layer supports. */
    val MULTIPLIERS = listOf(2, 3, 4)
    const val DEFAULT_MULTIPLIER = 2
    private const val FLOW_SCALE = 0.80f

    /** The layer is built against bionic Vulkan drivers; a glibc prefix has none to hook. */
    @JvmStatic
    fun isSupported(container: Container): Boolean =
        container.containerVariant.equals(Container.BIONIC, ignoreCase = true)

    /**
     * The folder of the person's Lossless Scaling install holding its DLL, or
     * null when droidtop's Steam has no installed copy. Disk and database
     * work: never on the main thread.
     */
    suspend fun losslessFolder(context: Context): File? {
        val store = StoreLibraries.byId("steam") ?: return null
        val path = store.installedPath(context, LOSSLESS_SCALING_APP_ID) ?: return null
        return findDll(File(path))?.parentFile
    }

    /** `Lossless.dll` in [installDir] (any letter case, as Windows files are named) or one folder down. */
    internal fun findDll(installDir: File): File? {
        fun inFolder(dir: File): File? =
            dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(LOSSLESS_DLL_NAME, ignoreCase = true) }
        return inFolder(installDir) ?: installDir.listFiles()?.filter { it.isDirectory }?.firstNotNullOfOrNull(::inFolder)
    }

    /** The multiplier [value] names, or [DEFAULT_MULTIPLIER] when it names none. */
    fun multiplier(value: String?): Int = value?.toIntOrNull()?.takeIf { it in MULTIPLIERS } ?: DEFAULT_MULTIPLIER

    /**
     * Gets a launch ready for frame generation, or for none: installs the
     * layer, the manifest and the DLL, writes the config and sets the
     * environment when this prefix has it on; removes the manifest otherwise.
     * Returns whether the layer is armed. Called by
     * BionicProgramLauncherComponent on its launch thread.
     */
    @JvmStatic
    fun applyLaunchEnv(context: Context, container: Container, envVars: EnvVars): Boolean {
        envVars.remove(ENV_DISABLE)
        envVars.remove(ENV_CONFIG)
        envVars.remove(ENV_PROCESS)
        val wanted = isSupported(container) && container.getExtra(EXTRA_ARMED, "false").equals("true", ignoreCase = true)
        if (!wanted) {
            removeManifest(container)
            return false
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Timber.tag(TAG).w("frame generation not armed: launched on the main thread, which cannot read the Steam library")
            removeManifest(container)
            return false
        }
        val dll = runCatching { runBlocking { losslessFolder(context) }?.let { findDll(it) } }.getOrNull()
        if (dll == null) {
            Timber.tag(TAG).w("frame generation is on but Lossless Scaling is not installed in droidtop's Steam")
            removeManifest(container)
            return false
        }
        val multiplier = multiplier(container.getExtra(EXTRA_MULTIPLIER, ""))
        val home = container.rootDir
        val installedDll = File(home, "$DLL_RELATIVE_DIR/$LOSSLESS_DLL_NAME")
        val configFile = File(home, CONFIG_RELATIVE_PATH)
        val ok = runCatching {
            installLayer(context, home) &&
                copyIfDifferent(dll, installedDll) &&
                writeAtomic(configFile, configToml(installedDll.absolutePath, multiplier))
        }.getOrElse {
            Timber.tag(TAG).e(it, "could not set frame generation up")
            false
        }
        if (!ok) {
            removeManifest(container)
            return false
        }
        envVars.put(ENV_CONFIG, configFile.absolutePath)
        envVars.put(ENV_PROCESS, PROCESS_EXE_IDENTIFIER)
        val layerDir = File(home, LAYER_RELATIVE_DIR).absolutePath
        val existing = envVars.get("VK_LAYER_PATH")
        envVars.put("VK_LAYER_PATH", if (existing.isNullOrEmpty()) layerDir else "$existing:$layerDir")
        Timber.tag(TAG).i("frame generation armed: %dx, DLL from %s", multiplier, dll.parent)
        return true
    }

    /** Puts the layer library and its manifest where the Vulkan loader looks. */
    private fun installLayer(context: Context, home: File): Boolean {
        val source = File(context.applicationInfo.nativeLibraryDir, LIB_FILENAME)
        if (!source.isFile) {
            Timber.tag(TAG).e("the layer library is not in this build: %s", source.absolutePath)
            return false
        }
        val lib = File(home, "$LIB_RELATIVE_DIR/$LIB_FILENAME")
        if (!copyIfDifferent(source, lib)) return false
        FileUtils.chmod(lib, 0b111101101)
        val manifest = File(home, "$LAYER_RELATIVE_DIR/$MANIFEST_FILENAME")
        if (!writeAtomic(manifest, manifestJson())) return false
        return lib.isFile && manifest.isFile
    }

    private fun removeManifest(container: Container) {
        File(container.rootDir, "$LAYER_RELATIVE_DIR/$MANIFEST_FILENAME").delete()
    }

    private fun copyIfDifferent(from: File, to: File): Boolean {
        if (to.isFile && to.length() == from.length()) return true
        to.parentFile?.mkdirs()
        val tmp = File(to.parentFile, to.name + ".tmp")
        return try {
            from.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(to)
        } catch (t: Throwable) {
            tmp.delete()
            false
        }
    }

    // The layer rereads conf.toml on a change of its mtime and must never see half a file.
    private fun writeAtomic(file: File, text: String): Boolean {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            if (!FileUtils.writeString(tmp, text)) return false
            FileUtils.chmod(tmp, 0b110100100)
            tmp.renameTo(file)
        } catch (t: Throwable) {
            tmp.delete()
            false
        }
    }

    internal fun manifestJson(): String = """
        {
          "file_format_version": "1.0.0",
          "layer": {
            "name": "VK_LAYER_LS_frame_generation",
            "type": "GLOBAL",
            "api_version": "1.4.313",
            "library_path": "$MANIFEST_LIBRARY_PATH",
            "implementation_version": "1",
            "description": "Lossless Scaling frame generation layer",
            "functions": {
              "vkGetInstanceProcAddr": "layer_vkGetInstanceProcAddr",
              "vkGetDeviceProcAddr": "layer_vkGetDeviceProcAddr"
            },
            "disable_environment": { "DISABLE_LSFG": "1" }
          }
        }
    """.trimIndent() + "\n"

    /** The layer's `conf.toml` for one game at [multiplier]; the options lsfg-vk-android reads. */
    internal fun configToml(dllPath: String, multiplier: Int): String = buildString {
        appendLine("version = 1")
        appendLine()
        appendLine("[global]")
        appendLine("dll = ${tomlString(dllPath)}")
        appendLine("no_fp16 = false")
        appendLine()
        appendLine("[[game]]")
        appendLine("exe = ${tomlString(PROCESS_EXE_IDENTIFIER)}")
        appendLine("multiplier = ${multiplier(multiplier.toString())}")
        appendLine("flow_scale = ${String.format(Locale.US, "%.2f", FLOW_SCALE)}")
        appendLine("performance_mode = true")
        appendLine("hdr_mode = false")
        appendLine("fps_limit = 0")
        // Mailbox: the layer paces to the vsync grid itself, and a FIFO queue underneath breaks the cadence.
        appendLine("experimental_present_mode = \"mailbox\"")
    }

    private fun tomlString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
        append('"')
    }
}
