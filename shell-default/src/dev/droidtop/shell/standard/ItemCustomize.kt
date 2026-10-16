package dev.droidtop.shell.standard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
        val hasPicture = HomeItemExtras.picture(info) != null
        val rows = buildList {
            add(context.getString(R.string.item_customize_title, HomeItemExtras.title(info) ?: info.title ?: ""))
            add(context.getString(R.string.item_customize_picture))
            if (hasPicture) add(context.getString(R.string.item_customize_picture_remove))
            add(context.getString(R.string.item_customize_swipe_up, HomeActions.label(context, HomeItemExtras.swipe(context, id, up = true))))
            add(context.getString(R.string.item_customize_swipe_down, HomeActions.label(context, HomeItemExtras.swipe(context, id, up = false))))
        }
        val removeRow = if (hasPicture) 2 else -1
        val upRow = if (hasPicture) 3 else 2
        RadioListDialog.show(
            context = context,
            title = context.getString(R.string.item_customize),
            entryCount = rows.size,
            iconPosition = RadioGroupBottomSheet.IconPosition.START,
            currentIndex = -1,
            textProvider = { rows[it] },
            iconProvider = null,
            iconTint = null,
            isVisibleProvider = null,
            isEnabledProvider = null,
            onSelected = { row ->
                when (row) {
                    0 -> askTitle(context, info, view)
                    1 -> choosePicture(context, id)
                    removeRow -> removePicture(context, id)
                    upRow, upRow + 1 -> {
                        val up = row == upRow
                        HomeActions.pickAction(
                            context,
                            context.getString(if (up) R.string.item_customize_swipe_up_pick else R.string.item_customize_swipe_down_pick),
                            HomeItemExtras.swipe(context, id, up),
                        ) { action -> HomeItemExtras.setSwipe(context, id, up, action) }
                    }
                }
            },
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
                val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_IMAGE_BYTES) throw java.io.IOException("larger than $MAX_IMAGE_BYTES bytes")
                    }
                    out.toByteArray()
                }
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

    private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
}

/** Shows the system's image picker for one home-screen item and hands the choice to [ItemCustomize]. */
class ItemPictureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQUEST_PICK)
        } catch (e: Exception) {
            Log.w("droidtop.ItemPicture", "no file picker", e)
            Notices.flash(getString(R.string.item_customize_picture_failed), tone = NoticeTone.Caution, context = this)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val uri = data?.data
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK && uri != null && id >= 0) ItemCustomize.savePicture(this, id, uri)
        finish()
    }

    companion object {
        const val EXTRA_ID = "dev.droidtop.extra.ITEM_ID"
        private const val REQUEST_PICK = 1
    }
}
