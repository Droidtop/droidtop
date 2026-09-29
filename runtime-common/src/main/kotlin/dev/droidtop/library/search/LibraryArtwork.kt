package dev.droidtop.library.search

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * Decodes a library entry's own artwork (`LibraryEntry.iconUri`/
 * `artworkUri`) into a square, downsampled [Bitmap], for the surfaces that
 * draw a game's own image rather than a generic app icon
 * (`LauncherGamesActivity`'s pinned-shortcut icon). Callers pass the raw URI
 * string they already have, so this needs no `LibraryEntry`.
 */
object LibraryArtwork {
    /**
     * @param uriString a `content://`, `android.resource://` or bare/`file`
     * path, exactly as `LibraryEntry.iconUri`/`artworkUri` stores it.
     * @param edgePx the output bitmap's edge length in pixels; the source
     * image is downsampled (never upscaled past its own resolution) and
     * center-cropped square before scaling, so this never allocates more
     * than a source image's own decoded size.
     */
    fun decodeSquareBitmap(context: Context, uriString: String, edgePx: Int): Bitmap? {
        if (uriString.isBlank()) return null
        val uri = Uri.parse(uriString)
        val open: () -> java.io.InputStream? = when (uri.scheme) {
            null, "file" -> ({ File(uri.path ?: uriString).inputStream() })
            "content", "android.resource" -> ({ context.contentResolver.openInputStream(uri) })
            else -> return null
        }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edgePx) sample *= 2
            val decoded = open()?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val edge = minOf(decoded.width, decoded.height)
            val square = Bitmap.createBitmap(
                decoded, (decoded.width - edge) / 2, (decoded.height - edge) / 2, edge, edge,
            )
            Bitmap.createScaledBitmap(square, edgePx, edgePx, true)
        }.getOrNull()
    }
}
