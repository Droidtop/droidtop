package dev.droidtop.runtime.windows

import android.content.Context
import android.os.Build
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import timber.log.Timber

/**
 * A game's choice of the memory allocator its Wine processes use (docs/SPEC.md 9, "The allocator a game's Wine
 * uses"; Droidtop/tracker#894). Android's own (Scudo) unless the game opts into mimalloc.
 *
 * Why: Scudo gives each small size class a fixed 256 MB region in every process. On the x86_64 Lavapipe path,
 * LLVM compiling DXVK's pipelines inside the game's Wine process fills the 32 KB and 64 KB classes, and Scudo then
 * fails the allocation ("Scudo OOM: The process has exhausted 256M for size class N") however much memory the device
 * has. mimalloc has no per-class cap. It is built from vendor/mimalloc (v2.2.7) into the APK as libmimalloc.so and,
 * for a game that chose it, put first on its Wine processes' LD_PRELOAD, so its malloc and free come before bionic's.
 *
 * Off by default (owner, 2026-11-05): two allocators in one process is the risk, if something frees through one a
 * pointer the other allocated. Per game, so a game it breaks is the only one affected.
 *
 * Android 10 (API 29) or later only: the library uses ELF thread-local storage, which older linkers refuse to load,
 * and a preload that fails to load stops Wine from starting at all.
 */
object WineAllocator {
    /** The game's choice, as a launch override key (`Container.LAUNCH_OVERRIDE_KEYS`) and in its own options. */
    const val KEY = "droidtopAllocator"
    const val MIMALLOC = "mimalloc"
    const val LIBRARY = "libmimalloc.so"
    const val MIN_SDK = Build.VERSION_CODES.Q

    /** Whether this device can load the preloaded allocator at all. */
    fun supported(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= MIN_SDK

    /** [current] with [library] first, keeping every entry already there once. */
    fun withPreload(current: String?, library: String): String =
        (listOf(library) + current.orEmpty().split(':').filter { it.isNotEmpty() && it != library }).joinToString(":")

    /**
     * Puts mimalloc first on [envVars]' LD_PRELOAD when [container]'s game chose it and this device can load it.
     * Returns whether it did. Run after every other step that writes LD_PRELOAD except the sandbox's, which puts its
     * own library first and keeps the rest.
     */
    @JvmStatic
    fun applyLaunchEnv(context: Context, container: Container?, envVars: EnvVars): Boolean {
        if (container?.getExtra(KEY) != MIMALLOC) return false
        if (!supported()) {
            Timber.tag(TAG).w("mimalloc chosen but this device runs API %d; it needs %d. Android's allocator stays", Build.VERSION.SDK_INT, MIN_SDK)
            return false
        }
        val library = File(context.applicationInfo.nativeLibraryDir, LIBRARY)
        if (!library.isFile) {
            Timber.tag(TAG).w("mimalloc chosen but %s is not in the APK. Android's allocator stays", library.path)
            return false
        }
        envVars.put("LD_PRELOAD", withPreload(envVars.get("LD_PRELOAD"), library.path))
        Timber.tag(TAG).i("Wine runs on mimalloc (%s)", library.path)
        return true
    }

    private const val TAG = "droidtop.WineAllocator"
}
