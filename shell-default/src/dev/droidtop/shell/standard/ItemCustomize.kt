package dev.droidtop.shell.standard

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.EditText
import app.murinelauncher.widget.radio.RadioGroupBottomSheet
import app.murinelauncher.widget.radio.RadioListDialog
import com.android.launcher3.BubbleTextView
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.Executors
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notices

/**
 * The person's own options for one home-screen app icon (docs/SPEC.md, Launcher mode "Per-icon options";
 * Droidtop/tracker#564), from its long-press menu: its title, a picture in place of its icon, and what a swipe up
 * or down on it does. Kept by [HomeItemExtras]. A folder's picture is set from the folder's own options.
 */
object ItemCustomize {
    private const val TAG = "droidtop.ItemCustomize"

    /** The options dialog for [info], an app icon on the home screen, in the dock or in a folder. */
    fun show(context: Context, info: ItemInfo, view: View?) {
        val id = info.id
        fun swipeRow(up: Boolean): Pair<String, () -> Unit> = context.getString(
            if (up) R.string.item_customize_swipe_up else R.string.item_customize_swipe_down,
            HomeActions.label(context, HomeItemExtras.swipe(context, id, up)),
        ) to {
            HomeActions.pickAction(
                context,
                context.getString(if (up) R.string.item_customize_swipe_up_pick else R.string.item_customize_swipe_down_pick),
                HomeItemExtras.swipe(context, id, up),
            ) { action -> HomeItemExtras.setSwipe(context, id, up, action) }
        }
        // Each row's label with what it does, so no row is found again by a computed index.
        val rows: List<Pair<String, () -> Unit>> = buildList {
            add(context.getString(R.string.item_customize_title, HomeItemExtras.title(info) ?: info.title ?: "") to { askTitle(context, info, view) })
            add(context.getString(R.string.item_customize_picture) to { choosePicture(context, id) })
            if (HomeItemExtras.picture(info) != null) {
                add(context.getString(R.string.item_customize_picture_remove) to { removePicture(context, id) })
            }
            add(swipeRow(up = true))
            add(swipeRow(up = false))
        }
        RadioListDialog.show(
            context = context,
            title = context.getString(R.string.item_customize),
            entryCount = rows.size,
            iconPosition = RadioGroupBottomSheet.IconPosition.START,
            currentIndex = -1,
            textProvider = { rows[it].first },
            iconProvider = null,
            iconTint = null,
            isVisibleProvider = null,
            isEnabledProvider = null,
            onSelected = { row -> rows.getOrNull(row)?.second?.invoke() },
        )
    }

    /** The icon's own title; empty gives it back the app's. */
    private fun askTitle(context: Context, info: ItemInfo, view: View?) {
        val field = EditText(context).apply {
            setText(HomeItemExtras.title(info) ?: info.title ?: "")
            setSingleLine()
            selectAll()
            // No full-screen input method over the dialog (as every droidtop field, NoFullscreenTextInput).
            imeOptions = dev.droidtop.ui.shared.noFullscreen(imeOptions)
        }
        AlertDialog.Builder(context, R.style.DroidtopDialog)
            .setTitle(R.string.item_customize_title_ask)
            .setView(field)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                HomeItemExtras.setTitle(info.id, field.text.toString().takeIf { it.trim() != info.title?.toString() })
                (view as? BubbleTextView)?.applyLabel(info)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Lets the person pick an image for item [id] (an app icon or a folder) with the system's file picker. */
    @JvmStatic
    fun choosePicture(context: Context, id: Int) {
        context.startActivity(Intent(context, ItemPictureActivity::class.java).putExtra(ItemPictureActivity.EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @JvmStatic
    fun removePicture(context: Context, id: Int) {
        val app = context.applicationContext
        Executors.THREAD_POOL_EXECUTOR.execute {
            HomeItemExtras.setPicture(app, id, null)
            LauncherAppState.getInstance(app).model.forceReload()
        }
    }

    /** Reads the picked image off the main thread, keeps it for the item, and has the home screen drawn again. */
    internal fun savePicture(context: Context, id: Int, uri: Uri) {
        val app = context.applicationContext
        Executors.THREAD_POOL_EXECUTOR.execute {
            val saved = try {
                val bytes = app.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_IMAGE_BYTES) }
                bytes != null && HomeItemExtras.setPicture(app, id, bytes)
            } catch (e: Exception) {
                Log.w(TAG, "could not read the picture for item $id", e)
                false
            }
            if (saved) {
                LauncherAppState.getInstance(app).model.forceReload()
            } else {
                Executors.MAIN_EXECUTOR.execute {
                    Notices.flash(app.getString(R.string.item_customize_picture_failed), tone = NoticeTone.Caution, context = app)
                }
            }
        }
    }

    /**
     * All of [input], refused when it is larger than [max] bytes. InputStream.readNBytes would do this, but it is
     * Android 13's and droidtop runs from Android 8.
     */
    private fun readAtMost(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var n = input.read(buffer)
        while (n >= 0) {
            out.write(buffer, 0, n)
            if (out.size() > max) throw java.io.IOException("larger than $max bytes")
            n = input.read(buffer)
        }
        return out.toByteArray()
    }

    private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
}

/** The system's image picker for one home-screen item; the image chosen goes to [ItemCustomize.savePicture]. */
class ItemPictureActivity : DocumentPickActivity() {
    override val mimeTypes = arrayOf("image/*")

    override fun onPicked(uri: Uri) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id >= 0) ItemCustomize.savePicture(this, id, uri)
        finish()
    }

    override fun onNoPicker() {
        Notices.flash(getString(R.string.item_customize_picture_failed), tone = NoticeTone.Caution, context = this)
        finish()
    }

    companion object {
        const val EXTRA_ID = "dev.droidtop.extra.ITEM_ID"
    }
}
