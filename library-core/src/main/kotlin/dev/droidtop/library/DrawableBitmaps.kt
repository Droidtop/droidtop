package dev.droidtop.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * The one way droidtop turns an app's icon (any [Drawable]) into a plain
 * ARGB bitmap it can cache, encode or hand to Compose.
 *
 * Drawing straight into `Canvas(bitmap)` is a software canvas, and a
 * software canvas refuses a HARDWARE-config bitmap: "Software rendering
 * doesn't support hardware bitmaps". Android 14's Clock icon is one
 * (Launcher3's `ClockDrawableWrapper` draws its flattened face as a
 * hardware bitmap), so the Clock app listed with a blank plate in Gaming's
 * Apps (emulator rig, dq-coordinator-23 F1). A `BitmapDrawable`'s own
 * hardware bitmap was copied out, but that covered only that one class.
 *
 * On API 28+ the drawable is recorded into a [Picture] instead: a picture
 * canvas accepts hardware bitmaps and notes that it did, and
 * `Bitmap.createBitmap(Picture, w, h, config)` then renders such a picture
 * through the hardware renderer and reads it back as [Bitmap.Config.ARGB_8888]
 * (a picture without them is drawn in software, as before). Below 28 there
 * is no such path, and nothing droidtop draws there hands out hardware
 * bitmaps (the config itself arrived in API 26).
 */
object DrawableBitmaps {
    fun render(
        drawable: Drawable,
        width: Int = drawable.intrinsicWidth,
        height: Int = drawable.intrinsicHeight,
    ): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        drawable.setBounds(0, 0, w, h)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val picture = Picture()
            drawable.draw(picture.beginRecording(w, h))
            picture.endRecording()
            return Bitmap.createBitmap(picture, w, h, Bitmap.Config.ARGB_8888)
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}
