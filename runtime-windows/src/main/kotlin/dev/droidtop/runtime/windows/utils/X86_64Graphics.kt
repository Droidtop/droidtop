package dev.droidtop.runtime.windows.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import org.json.JSONObject

/**
 * The graphics drivers a container can choose on an x86_64 device, where
 * none of the arm64 ones (the Wrapper family, Turnip, Adreno, Vortek) exist.
 *
 * - [LAVAPIPE]: Mesa's software Vulkan driver with the Khronos loader, so DXVK
 *   and VKD3D get a device that presents to the app's X server. It renders on
 *   the CPU: slow, but it runs on every x86_64 device. Built by
 *   `tools/x86_64-lavapipe` in this repository's CI from Termux's x86_64
 *   packages and downloaded when a container first uses it.
 * - [NONE]: no Vulkan driver in the guest. Wine still draws 2D and GDI through
 *   the X server; Direct3D has no device.
 *
 * A container's `graphicsDriver` holds the id. On arm64 nothing here applies.
 */
object X86_64Graphics {
    const val LAVAPIPE = "lavapipe"
    const val NONE = "none"

    /** Every driver an x86_64 container can choose, the default first. */
    @JvmField
    val DRIVERS: List<String> = listOf(LAVAPIPE, NONE)

    /**
     * The name [id] is shown with. What follows the name in parentheses is a
     * description that `StringUtils.parseIdentifier` drops, so a label parses
     * back to its id the way gamenative's own driver lists do.
     */
    @JvmStatic
    fun label(id: String): String = when (id) {
        LAVAPIPE -> "Lavapipe (software Vulkan)"
        NONE -> "None (2D and GDI only)"
        else -> id
    }

    /** [DRIVERS] as their labels, in the same order: the list the prefix dialog offers on x86_64. */
    @JvmField
    val LABELS: List<String> = DRIVERS.map(::label)

    /** Release of Droidtop/droidtop that carries the asset; see .github/workflows/x86_64-lavapipe.yml. */
    const val LAVAPIPE_TAG = "x86_64-lavapipe-20261008-8a05a54d"
    const val LAVAPIPE_SHA256 = "6fc782fa7d84410e2fada8adfbc57fdc2dd6d096918a07c35ede8a1b487dad3c"
    private val lavapipe = PinnedReleaseAsset(
        LAVAPIPE_TAG,
        "x86_64-lavapipe.tzst",
        LAVAPIPE_SHA256,
        "x86_64-lavapipe",
        "the software Vulkan driver",
    )

    /** Whether [driver] has everything it needs on this device. */
    @JvmStatic
    fun isInstalled(context: Context, driver: String): Boolean =
        driver != LAVAPIPE || lavapipe.isInstalled(context)

    /** Fetches what [driver] needs, once; nothing for a driver that needs no download. */
    suspend fun ensureInstalled(context: Context, driver: String, onProgress: (Float) -> Unit) {
        if (driver == LAVAPIPE) lavapipe.ensureInstalled(context, onProgress)
    }

    /**
     * The guest environment for [container]'s driver, applied after
     * [X86_64GuestLibs.applyLaunchEnv] so these values win.
     *
     * For lavapipe the loader's directory goes first on the library path: it
     * holds the Khronos `libvulkan.so.1`, which has to outrank the guest
     * libraries' link to Android's loader (no X11 surface there). Everything
     * else in that directory is a library the guest set does not carry, so
     * nothing of the guest's own is shadowed (tools/x86_64-lavapipe/build.sh).
     * The ICD manifest is rewritten to name this app's copy. Mesa's software
     * presentation is pinned to the socket copy (`sw,noshm`): this X server's
     * MIT-SHM takes SysV ids that lavapipe's libandroid-shmem does not produce.
     */
    @JvmStatic
    fun applyLaunchEnv(context: Context, container: Container, envVars: EnvVars) {
        if (container.graphicsDriver != LAVAPIPE || !lavapipe.isInstalled(context)) return
        val root = lavapipe.root(context)
        val lib = File(root, "usr/lib")
        val existing = envVars.get("LD_LIBRARY_PATH")
        envVars.put("LD_LIBRARY_PATH", if (existing.isEmpty()) lib.path else lib.path + ":" + existing)
        val icd = writeIcd(root, lib).path
        envVars.put("VK_ICD_FILENAMES", icd)
        envVars.put("VK_DRIVER_FILES", icd)
        envVars.put("MESA_VK_WSI_DEBUG", "sw,noshm")
        envVars.put("MESA_SHADER_CACHE_DIR", File(root, "cache").apply { mkdirs() }.path)
    }

    private fun writeIcd(root: File, lib: File): File {
        val shipped = File(root, "usr/share/vulkan/icd.d/lvp_icd.x86_64.json")
        val written = File(root, "lvp_icd.json")
        val json = JSONObject(shipped.readText())
        json.getJSONObject("ICD").put("library_path", File(lib, "libvulkan_lvp.so").path)
        val text = json.toString(2) + "\n"
        if (!written.isFile || written.readText() != text) written.writeText(text)
        return written
    }
}
