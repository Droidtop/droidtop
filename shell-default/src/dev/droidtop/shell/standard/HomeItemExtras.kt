package dev.droidtop.shell.standard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.WorkerThread
import app.lawnchair.icons.getCustomInstanceLabelForId
import app.lawnchair.icons.setCustomInstanceLabelForId
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.FastBitmapDrawable
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.ItemInfoWithIcon
import dev.droidtop.runtime.HomeAction
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/**
 * What the person set on one home-screen item, kept beside Launcher3's row for it and keyed by the item's id
 * (docs/SPEC.md, Launcher mode "Per-icon options"; Droidtop/tracker#564), as Nova and Apex keep theirs:
 *
 * - a picture in place of an app's icon or a folder's preview: a PNG in the app's files, `home_item_pictures/<id>.png`,
 *   read into memory on the model thread ([loadPictures]) so a view never touches a file, and decoded again only when
 *   its file changed;
 * - an app icon's title: Launcher3's own per-item label (`IconPreferences`' instance labels), which the loader used
 *   only for pinned shortcuts; [title] applies it to an app's icon too;
 * - an app icon's swipe up and down: [HomeAction]s in one JSON object in the launcher's preferences.
 *
 * Drawn by `BubbleTextView` and `FolderIcon` at the moment they apply an item, so an icon refresh never loses them.
 * Removed with the item ([forget], from `ModelWriter`'s deletes).
 *
 * The pictures and the swipes change from the model thread, the customize menu's executor and a restore: every
 * change is made under one lock ([lock]), so one never overwrites another that began first.
 */
object HomeItemExtras {
    private const val TAG = "droidtop.HomeItemExtras"
    private const val DIR = "home_item_pictures"
    /** A picture is kept at most this many pixels a side: larger than any icon is drawn. */
    const val MAX_SIDE = 384

    private val SWIPES = LauncherPrefs.backedUpItem("home_item_swipes", "")
    // A stored swipe table that does not parse is kept here, as it was, before anything is saved over it.
    private val SWIPES_UNREADABLE = LauncherPrefs.backedUpItem("home_item_swipes_unreadable", "")

    private val lock = Any()

    /** Each picture with the modification time of the file it was decoded from. */
    private class Picture(val modified: Long, val bitmap: Bitmap)

    @Volatile private var pictures: Map<Int, Picture> = emptyMap()
    @Volatile private var swipes: JSONObject? = null

    private fun dir(context: Context) = File(context.filesDir, DIR)

    /**
     * Brings the pictures in memory up to the files. On the model thread, as the workspace loads: a picture whose file
     * did not change since it was decoded is kept, so a reload after one change decodes only that one.
     */
    @WorkerThread
    fun loadPictures(context: Context) = synchronized(lock) {
        val out = HashMap<Int, Picture>()
        dir(context).listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".png").toIntOrNull() ?: return@forEach
            val modified = file.lastModified()
            val known = pictures[id]?.takeIf { it.modified == modified }
            (known ?: BitmapFactory.decodeFile(file.path)?.let { Picture(modified, it) })?.let { out[id] = it }
        }
        pictures = out
    }

    /** The picture the person gave this item, if any. */
    fun picture(info: ItemInfo?): Bitmap? = info?.takeIf { it.id >= 0 }?.let { pictures[it.id]?.bitmap }

    /**
     * The icon to draw for [info]: its picture when it has one (never themed: it is the person's own image), else
     * Launcher3's. Every place that draws an item's icon asks this, so the picture shows wherever the icon does.
     */
    @JvmStatic
    fun newIcon(context: Context, info: ItemInfoWithIcon, flags: Int): FastBitmapDrawable {
        val picture = picture(info) ?: return info.newIcon(context, flags)
        return BitmapInfo.fromBitmap(picture).newIcon(context, flags and BitmapInfo.FLAG_THEMED.inv()).also { it.isDisabled = info.isDisabled }
    }

    /**
     * Gives item [id] the picture in [bytes] (any image Android decodes), scaled to [MAX_SIDE]; null removes it.
     * Returns false when the bytes are not an image. Off the main thread.
     */
    @WorkerThread
    fun setPicture(context: Context, id: Int, bytes: ByteArray?): Boolean {
        // Decoded outside the lock: it is the slow part and touches nothing shared.
        val bitmap = bytes?.let { decodeScaled(it) ?: return false }
        synchronized(lock) {
            val file = File(dir(context), "$id.png")
            if (bitmap == null) {
                file.delete()
                pictures = pictures - id
                return true
            }
            dir(context).mkdirs()
            val part = File(dir(context), "$id.png.part")
            try {
                FileOutputStream(part).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!part.renameTo(file)) return false
            } catch (e: Exception) {
                Log.w(TAG, "could not save the picture for item $id", e)
                part.delete()
                return false
            }
            pictures = pictures + (id to Picture(file.lastModified(), bitmap))
            return true
        }
    }

    /** [bytes] as a square bitmap no larger than [MAX_SIDE] a side, decoded at the nearest power of two first. */
    fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        // Fitted, centred, into a square: an icon is drawn into a square and would otherwise be stretched.
        val longest = maxOf(decoded.width, decoded.height)
        val side = minOf(longest, MAX_SIDE)
        val scale = side.toFloat() / longest
        val w = (decoded.width * scale).toInt().coerceAtLeast(1)
        val h = (decoded.height * scale).toInt().coerceAtLeast(1)
        val square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(square).drawBitmap(
            decoded,
            null,
            android.graphics.Rect((side - w) / 2, (side - h) / 2, (side - w) / 2 + w, (side - h) / 2 + h),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
        )
        decoded.recycle()
        return square
    }

    /** The title the person gave this one app icon, if any (a folder's title is its own column). */
    fun title(info: ItemInfo?): String? {
        if (info == null || info.id < 0 || info.itemType != Favorites.ITEM_TYPE_APPLICATION) return null
        return getCustomInstanceLabelForId(info.id)?.takeIf { it.isNotEmpty() }
    }

    fun setTitle(id: Int, title: String?) = setCustomInstanceLabelForId(id, title?.trim()?.takeIf { it.isNotEmpty() })

    private fun swipeTable(context: Context): JSONObject = swipes ?: synchronized(lock) {
        swipes ?: run {
            val stored = SWIPES.get(context)
            try {
                JSONObject(stored.ifEmpty { "{}" })
            } catch (e: org.json.JSONException) {
                // Kept as it was before an empty table is saved over it, and said, so it can be recovered.
                Log.w(TAG, "the stored icon swipes do not parse; kept as home_item_swipes_unreadable", e)
                LauncherPrefs.get(context).put(SWIPES_UNREADABLE, stored)
                JSONObject()
            }
        }.also { swipes = it }
    }

    /** What a swipe up ([up]) or down on item [id] does; null when nothing. */
    fun swipe(context: Context, id: Int, up: Boolean): HomeAction? {
        if (id < 0) return null
        val entry = swipeTable(context).optJSONObject(id.toString()) ?: return null
        return HomeAction.decode(entry.optString(if (up) "up" else "down"))
    }

    fun hasSwipe(context: Context, id: Int): Boolean = id >= 0 && swipeTable(context).has(id.toString())

    fun setSwipe(context: Context, id: Int, up: Boolean, action: HomeAction?) = setSwipes(context, listOf(SwipeChange(id, up, action)))

    /** One swipe of one item: [action] null clears it. */
    class SwipeChange(val id: Int, val up: Boolean, val action: HomeAction?)

    /** Applies [changes] to the table and saves it once (an import sets up to two per icon). */
    fun setSwipes(context: Context, changes: List<SwipeChange>) = synchronized(lock) {
        val table = JSONObject(swipeTable(context).toString())
        for (change in changes) {
            if (change.id < 0) continue
            val key = change.id.toString()
            val entry = table.optJSONObject(key) ?: JSONObject()
            val side = if (change.up) "up" else "down"
            if (change.action == null) entry.remove(side) else entry.put(side, change.action.encoded)
            if (entry.length() == 0) table.remove(key) else table.put(key, entry)
        }
        save(context, table)
    }

    private fun save(context: Context, table: JSONObject) {
        swipes = table
        LauncherPrefs.get(context).put(SWIPES, table.toString())
    }

    /** Standard's own backup (`BackupHelper`) carries each picture as one flat entry, `home_item_picture_<id>.png`. */
    private const val BACKUP_PREFIX = "home_item_picture_"
    private val BACKUP_ENTRY = Regex("home_item_picture_(\\d+)\\.png")

    /** The picture files, as (backup entry name, file). */
    @WorkerThread
    fun pictureFilesForBackup(context: Context): List<Pair<String, File>> =
        dir(context).listFiles().orEmpty().filter { it.name.removeSuffix(".png").toIntOrNull() != null }.map { BACKUP_PREFIX + it.name to it }

    fun isBackupEntry(name: String): Boolean = BACKUP_ENTRY.matches(name)

    /** A restore replaces every picture: the old ones go first, then [isBackupEntry] files are moved in. */
    @WorkerThread
    fun clearPictureFiles(context: Context) = synchronized(lock) {
        // Only this store's own files, one at a time: each picture is "<id>.png".
        pictureFilesForBackup(context).forEach { (_, file) -> file.delete() }
        pictures = emptyMap()
    }

    @WorkerThread
    fun restorePictureFile(context: Context, staged: File): Boolean {
        val id = BACKUP_ENTRY.matchEntire(staged.name)?.groupValues?.get(1) ?: return false
        dir(context).mkdirs()
        return staged.renameTo(File(dir(context), "$id.png"))
    }

    /** Item [id] left the home screen: its picture, title and swipes go with it. On the model thread. */
    @WorkerThread
    fun forget(context: Context, id: Int) = forget(context, listOf(id))

    /** Items [ids] left the home screen (an import replacing the layout): the swipe table is saved once. */
    @WorkerThread
    fun forget(context: Context, ids: Collection<Int>) = synchronized(lock) {
        val gone = ids.filter { it >= 0 }
        for (id in gone) {
            if (id in pictures || File(dir(context), "$id.png").exists()) setPicture(context, id, null)
            if (getCustomInstanceLabelForId(id) != null) setCustomInstanceLabelForId(id, null)
        }
        val table = swipeTable(context)
        if (gone.any { table.has(it.toString()) }) {
            save(context, JSONObject(table.toString()).apply { gone.forEach { remove(it.toString()) } })
        }
    }
}
