package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notices
import dev.droidtop.runtime.DesktopAppearance
import dev.droidtop.runtime.DesktopPanel
import dev.droidtop.shell.standard.DocumentPickActivity
import java.io.File

/**
 * A picture behind the Linux desktop (docs/SPEC.md 2b "Desktop appearance", Droidtop/tracker#694). The person picks an
 * image in the system's picker; droidtop reads it once, off the main thread, scaled to at most [MAX_SIDE] pixels on its
 * longer side, and keeps it as a PNG beside the panel's config ([DesktopPanel.BACKGROUND_PICTURE_FILE]), where the
 * container reads it. A PNG because sway's background program draws PNG in every build, whatever the picture was.
 * The setting moves to the picture only once it is saved; it applies from the next desktop start.
 */
object DesktopBackgroundPicture {
    private const val TAG = "droidtop.DesktopBackground"

    /** The longer side the kept picture is scaled down to; a 4K screen's width. */
    private const val MAX_SIDE = 3840

    /** Opens the system's image picker for the desktop's background. */
    fun choose(context: Context) {
        context.startActivity(Intent(context, DesktopPictureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Reads [uri] off the main thread, keeps it as the background picture and chooses it; says so if it cannot. */
    internal fun save(context: Context, uri: Uri) {
        val app = context.applicationContext
        Thread({
            val saved = runCatching { write(app, uri) }
                .onFailure { android.util.Log.w(TAG, "Couldn't keep the picture", it) }
                .getOrDefault(false)
            if (saved) {
                DesktopSetupPrefs.setBackground(app, DesktopAppearance.Background.PICTURE)
                Notices.flash(app.getString(R.string.desktop_background_picture_saved), tone = NoticeTone.Info, context = app)
            } else {
                Notices.flash(app.getString(R.string.desktop_background_picture_failed), tone = NoticeTone.Caution, context = app)
            }
        }, "desktop-background").start()
    }

    private fun write(context: Context, uri: Uri): Boolean {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return false
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return false
        val dir = DesktopPanel.hostDir(context.filesDir).apply { mkdirs() }
        val temp = File(dir, DesktopPanel.BACKGROUND_PICTURE_FILE + ".part")
        try {
            temp.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) return false }
        } finally {
            bitmap.recycle()
        }
        return temp.renameTo(File(dir, DesktopPanel.BACKGROUND_PICTURE_FILE))
    }
}

/** The system's image picker for the desktop's background; the image chosen goes to [DesktopBackgroundPicture.save]. */
class DesktopPictureActivity : DocumentPickActivity() {
    override val mimeTypes = arrayOf("image/*")

    override fun onPicked(uri: Uri) {
        DesktopBackgroundPicture.save(this, uri)
        finish()
    }

    override fun onNoPicker() {
        Notices.flash(getString(R.string.desktop_background_picture_failed), tone = NoticeTone.Caution, context = this)
        finish()
    }
}
