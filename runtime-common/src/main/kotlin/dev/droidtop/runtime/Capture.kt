package dev.droidtop.runtime

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/**
 * The one entry point for screenshots (docs/SPEC.md "The companion's tabs", Screenshots; Droidtop/tracker#414 slice
 * C15): the companion's Game row and its pin take one here. Two routes, in order:
 * - the helper app's shell runs `screencap -p` into droidtop's own external cache folder (Android's default screen;
 *   `screencap -d` wants a physical display id that apps are not given);
 * - else droidtop's accessibility service takes it (`AccessibilityService.takeScreenshot`, Android 11 and later, any
 *   screen): that service's second job beside typing, registered as [accessibility] while it is connected.
 * The picture is saved to Pictures/Screenshots like Android's own, and handed back for Share.
 */
object Capture {
    enum class Route { PROVIDER, ACCESSIBILITY, NONE }

    /** Takes a screenshot of a display and hands the picture back (null when Android refused). */
    fun interface Shooter {
        fun shoot(displayId: Int, done: (Bitmap?) -> Unit)
    }

    /** The accessibility service's screenshot, set while the service is connected on Android 11 or later. */
    @Volatile
    var accessibility: Shooter? = null

    /** Which route takes a screenshot of [displayId]. Pure. */
    fun route(providerShell: Boolean, displayId: Int, accessibilityReady: Boolean): Route = when {
        providerShell && displayId == android.view.Display.DEFAULT_DISPLAY -> Route.PROVIDER
        accessibilityReady -> Route.ACCESSIBILITY
        else -> Route.NONE
    }

    /** What a person reads when no route can take it. Pure. */
    fun noRouteMessage(displayId: Int, providerShell: Boolean): String =
        if (providerShell && displayId != android.view.Display.DEFAULT_DISPLAY) {
            "Turn on droidtop's accessibility service to take screenshots of this screen"
        } else {
            "Needs the helper app or droidtop's accessibility service"
        }

    /** A saved screenshot: where it is, for Share, and the line to show. */
    data class Shot(val uri: Uri?, val message: String)

    /** Takes a screenshot of [displayId] and saves it. Off the main thread; blocks up to a few seconds. */
    suspend fun take(context: Context, displayId: Int): Shot = withContext(Dispatchers.IO) {
        val provider = runCatching { TaskManager.shell.capabilities().shellCommand }.getOrDefault(false)
        val shooter = accessibility
        val name = "droidtop-${System.currentTimeMillis()}.png"
        val bytes: ByteArray? = when (route(provider, displayId, shooter != null)) {
            Route.PROVIDER -> {
                val dir = context.externalCacheDir ?: return@withContext Shot(null, "No place to save it")
                val file = File(dir, name)
                val out = TaskManager.shell.exec(listOf("screencap", "-p", file.absolutePath))
                val data = if (out?.exit == 0) runCatching { file.readBytes() }.getOrNull() else null
                file.delete()
                data
            }
            Route.ACCESSIBILITY -> suspendCancellableCoroutine { cont ->
                shooter!!.shoot(displayId) { bitmap ->
                    val png = bitmap?.let { b -> ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
                    if (cont.isActive) cont.resume(png)
                }
            }
            Route.NONE -> return@withContext Shot(null, noRouteMessage(displayId, provider))
        }
        if (bytes == null || bytes.isEmpty()) return@withContext Shot(null, "Android did not take the screenshot")
        val uri = save(context, name, bytes) ?: return@withContext Shot(null, "Could not save the screenshot")
        Shot(uri, "Saved to Pictures/Screenshots")
    }

    /** Saves [bytes] as Pictures/Screenshots/[name] (MediaStore on Android 10 and later, else droidtop's own folder). */
    private fun save(context: Context, name: String, bytes: ByteArray): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching null
            uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Screenshots").apply { mkdirs() }
            val file = File(dir, name).apply { writeBytes(bytes) }
            androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }
    }.getOrNull()

    /** Android's share sheet for a screenshot. */
    fun shareIntent(uri: Uri): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        "Share screenshot",
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
