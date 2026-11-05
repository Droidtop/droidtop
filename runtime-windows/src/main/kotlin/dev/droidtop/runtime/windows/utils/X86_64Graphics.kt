package dev.droidtop.runtime.windows.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.XEnvironment
import java.io.File
import org.json.JSONObject

/**
 * The graphics drivers a container can choose on an x86_64 device, where
 * none of the arm64 ones (the Wrapper family, Turnip, Adreno, Vortek) exist.
 *
 * - [LAVAPIPE]: Mesa's software Vulkan driver with the Khronos loader, so DXVK
 *   and VKD3D get a device that presents to the app's X server, and Mesa's
 *   xlib OpenGL (llvmpipe), so WineD3D's OpenGL renderer gets a context too
 *   (Droidtop/tracker#309). Both render on the CPU: slow, but they run on
 *   every x86_64 device. Built by `build-scripts/x86_64-lavapipe` in this
 *   repository's CI from Termux's x86_64 packages and recipe and downloaded
 *   when a container first uses it.
 * - [VENUS]: Mesa's virtio Vulkan driver (Venus) from the same archive, whose
 *   commands a vtest server ([VtestServerComponent], `libvirgl_vtest.so` in the
 *   APK) replays on Android's own Vulkan, i.e. the device's GPU
 *   (docs/SPEC.md 10b, "A real GPU on x86_64: Venus"; Droidtop/tracker#608).
 *   Offered where the device's Vulkan qualifies ([venusQualifies]); not the
 *   default until a rig has run it (owner to confirm).
 * - [NONE]: no Vulkan driver in the guest. Wine still draws 2D and GDI through
 *   the X server; Direct3D has no device.
 *
 * A container's `graphicsDriver` holds the id. On arm64 nothing here applies.
 */
object X86_64Graphics {
    const val LAVAPIPE = "lavapipe"
    const val VENUS = "venus"
    const val NONE = "none"

    /** Every driver an x86_64 container can choose, the default first. */
    @JvmField
    val DRIVERS: List<String> = listOf(LAVAPIPE, VENUS, NONE)

    /**
     * The name [id] is shown with. What follows the name in parentheses is a
     * description that `StringUtils.parseIdentifier` drops, so a label parses
     * back to its id the way gamenative's own driver lists do.
     */
    @JvmStatic
    fun label(id: String): String = when (id) {
        LAVAPIPE -> "Lavapipe (software Vulkan and OpenGL)"
        VENUS -> "Venus (the device's GPU through Vulkan)"
        NONE -> "None (2D and GDI only)"
        else -> id
    }

    /** [DRIVERS] as their labels, in the same order: the list the prefix dialog offers on x86_64. */
    @JvmField
    val LABELS: List<String> = DRIVERS.map(::label)

    /** Release of Droidtop/droidtop that carries the asset; see .github/workflows/x86_64-lavapipe.yml. */
    const val LAVAPIPE_TAG = "x86_64-lavapipe-20261105-c93b1003"
    const val LAVAPIPE_SHA256 = "610fe7d1728c38d5bda45ae3d24248d7351d9dede8a05bbffee20f6082b4cbc4"
    private val lavapipe = PinnedReleaseAsset(
        LAVAPIPE_TAG,
        "x86_64-lavapipe.tzst",
        LAVAPIPE_SHA256,
        "x86_64-lavapipe",
        "the software Vulkan driver",
    )

    /** Whether [driver] has everything it needs on this device. */
    @JvmStatic
    fun isInstalled(context: Context, driver: String): Boolean = when (driver) {
        LAVAPIPE -> lavapipe.isInstalled(context)
        VENUS -> lavapipe.isInstalled(context) && vtestServer(context).isFile
        else -> true
    }

    /** Fetches what [driver] needs, once; nothing for a driver that needs no download. */
    suspend fun ensureInstalled(context: Context, driver: String, onProgress: (Float) -> Unit) {
        if (driver == LAVAPIPE || driver == VENUS) lavapipe.ensureInstalled(context, onProgress)
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
     *
     * OpenGL: the same directory holds Mesa's xlib `libGL.so.1`, which does
     * GLX on the client side, so Wine is told to use GLX although the X
     * server has no GLX extension (`WINE_X11FORCEGLX`, the Android patch to
     * winex11's opengl.c that [X86_64GuestLibs] otherwise clears), and the
     * xlib winsys skips MIT-SHM for the same SysV reason (`XLIB_NO_SHM`).
     */
    @JvmStatic
    fun applyLaunchEnv(context: Context, container: Container, envVars: EnvVars) {
        val driver = container.graphicsDriver
        if ((driver != LAVAPIPE && driver != VENUS) || !isInstalled(context, driver)) return
        val root = lavapipe.root(context)
        val lib = File(root, "usr/lib")
        val existing = envVars.get("LD_LIBRARY_PATH")
        envVars.put("LD_LIBRARY_PATH", if (existing.isEmpty()) lib.path else lib.path + ":" + existing)
        val icd = if (driver == VENUS) {
            // The guest's Venus driver talks to this session's vtest server (VtestServerComponent) on this socket.
            envVars.put("VTEST_SOCKET_NAME", vtestSocket(context).path)
            writeIcd(root, "virtio_icd.x86_64.json", "virtio_icd.json", File(lib, "libvulkan_virtio.so")).path
        } else {
            writeIcd(root, "lvp_icd.x86_64.json", "lvp_icd.json", File(lib, "libvulkan_lvp.so")).path
        }
        envVars.put("VK_ICD_FILENAMES", icd)
        envVars.put("VK_DRIVER_FILES", icd)
        envVars.put("MESA_VK_WSI_DEBUG", "sw,noshm")
        if (File(lib, "libGL.so.1").exists()) {
            envVars.put("WINE_X11FORCEGLX", "1")
            envVars.put("XLIB_NO_SHM", "1")
        }
        envVars.put("MESA_SHADER_CACHE_DIR", File(root, "cache").apply { mkdirs() }.path)
    }

    /**
     * The drivers this device is offered: [DRIVERS], with [VENUS] only where its Vulkan qualifies
     * ([venusQualifies], read once through `GPUHelper`). JNI and a Vulkan instance: never on the main thread.
     */
    fun offered(): List<String> = if (venusHere) DRIVERS else DRIVERS - VENUS

    private val venusHere: Boolean by lazy {
        runCatching {
            venusQualifies(com.winlator.core.GPUHelper.vkGetApiVersion(), com.winlator.core.GPUHelper.vkGetDeviceExtensions().orEmpty().toList())
        }.getOrDefault(false)
    }

    /** Whether [container] runs on Venus here, so its session needs the vtest server. Disk read. */
    @JvmStatic
    fun usesVenus(context: Context, container: Container): Boolean =
        X86_64GuestLibs.isX86_64Host() && container.graphicsDriver == VENUS && isInstalled(context, VENUS)

    /** The vtest server the APK carries (built by .github/workflows/x86_64-lavapipe.yml, fetched by components.pin). */
    fun vtestServer(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libvirgl_vtest.so")

    /** The socket the session's vtest server listens on and the guest's Venus driver connects to. */
    fun vtestSocket(context: Context): File = File(XEnvironment.getTmpDir(context), ".virgl_vtest")

    /**
     * Whether a device's Vulkan can carry Venus, from the instance [apiVersion] and the GPU's device [extensions]
     * (`GPUHelper`): Vulkan 1.1 or later, file-descriptor external memory (the server shares the guest's memory
     * through it) and timeline semaphores (core in 1.2, else the KHR extension). Pure.
     */
    fun venusQualifies(apiVersion: Int, extensions: Collection<String>): Boolean {
        val major = apiVersion ushr 22
        val minor = (apiVersion ushr 12) and 0x3ff
        val atLeast11 = major > 1 || (major == 1 && minor >= 1)
        val atLeast12 = major > 1 || (major == 1 && minor >= 2)
        return atLeast11 && "VK_KHR_external_memory_fd" in extensions &&
            (atLeast12 || "VK_KHR_timeline_semaphore" in extensions)
    }

    private fun writeIcd(root: File, shippedName: String, writtenName: String, library: File): File {
        val shipped = File(root, "usr/share/vulkan/icd.d/$shippedName")
        val written = File(root, writtenName)
        val json = JSONObject(shipped.readText())
        json.getJSONObject("ICD").put("library_path", library.path)
        val text = json.toString(2) + "\n"
        if (!written.isFile || written.readText() != text) written.writeText(text)
        return written
    }
}
