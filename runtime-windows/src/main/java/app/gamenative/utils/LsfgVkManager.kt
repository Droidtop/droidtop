package app.gamenative.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars

/**
 * Minimal, real compatibility shim for the four real calls forked-in
 * `com.winlator.xenvironment.components.BionicProgramLauncherComponent`
 * makes on `app.gamenative.utils.LsfgVkManager` (confirmed via reading its
 * actual usage this session): `isSupported`, `ensureRuntimeInstalled`,
 * `writeConfig`, `applyLaunchEnv`, all `@JvmStatic` to match the real Java
 * call-site style (`LsfgVkManager.isSupported(container)`, no `.INSTANCE`).
 * Upstream's real `LsfgVkManager` is a 650-line manager for the lsfg-vk
 * Vulkan frame-generation layer -- installing a real .so + manifest into
 * the container filesystem, copying `Lossless.dll` out of the Steam library
 * (via `SteamService`), writing `conf.toml`. Not forked in wholesale: frame
 * generation is a real feature droidtop doesn't support yet, not a dead
 * import like `MainActivity`. `isSupported` returns false so every real
 * call site's own `if (LsfgVkManager.isSupported(...))` guard skips the
 * rest of this block automatically -- an honest "not available yet," not a
 * fabricated partial implementation of the other three methods.
 */
object LsfgVkManager {
    @JvmStatic
    fun isSupported(container: Container): Boolean = false

    @JvmStatic
    fun ensureRuntimeInstalled(context: Context, container: Container): Boolean = false

    @JvmStatic
    fun writeConfig(container: Container): Boolean = false

    @JvmStatic
    fun applyLaunchEnv(container: Container, envVars: EnvVars): Boolean = false
}
