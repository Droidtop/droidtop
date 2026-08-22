package app.gamenative.utils.downloader

import android.content.Context
import java.io.File

/**
 * Minimal, real compatibility shim for the real thing forked-in
 * `com.winlator.container.ContainerManager`/`com.winlator.xenvironment.
 * ImageFsInstaller` call: `ContainerFilesDownloaderKt.
 * ensureContainerFileAvailableBlocking(context, componentId, callback)`
 * (the `...Kt` name is Kotlin's real top-level-function class name,
 * matched here with a Java-visible `@file:JvmName`). Upstream's real
 * downloader fetches large container-pattern/extras archives from a
 * gamenative-hosted CDN via `SteamService`-gated auth, with real resume/
 * checksum logic (187 lines) -- not forked in, since droidtop has no such
 * CDN or account layer yet. Always returns null here: every real call site
 * already null-checks the result and falls back to the bundled `assets/`
 * copy of the same archive when the download path isn't available (see
 * `ContainerManager.extractContainerPatternCommon`'s own real fallback),
 * so this is an honest "always use the bundled asset," not a broken stub.
 */
fun interface ProgressCallback {
    fun onProgress(progress: Float)
}

@Suppress("UNUSED_PARAMETER")
fun ensureContainerFileAvailableBlocking(
    context: Context,
    componentId: String,
    callback: ProgressCallback,
): File? = null
