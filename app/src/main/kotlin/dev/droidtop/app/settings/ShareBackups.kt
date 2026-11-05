package dev.droidtop.app.settings

import android.content.Context
import android.os.Build
import dev.droidtop.library.storage.StorageShare
import dev.droidtop.library.storage.StorageShares
import dev.droidtop.net.peer.AgentNative
import dev.droidtop.net.peer.DeviceIdentity
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * "Back up droidtop" to one of the person's shares, and restore from one (docs/SPEC.md 7p, Droidtop/tracker#573). The
 * archive is [DroidtopBackup]'s, unchanged; this only carries it. On the share each device keeps its own folder,
 * `droidtop/backups/<device>/`, and every backup is a new file there: droidtop never deletes one.
 *
 * On a share the person keeps locked (the default) the archive is sealed with this device's own key before it leaves
 * the device (`remote_put` with `seal`), so only this device can open it; on a trusted share it is a plain zip the
 * person can open, and it never carries sign-ins. All of it is disk and network work: never on the main thread.
 */
object ShareBackups {
    const val ROOT = "droidtop/backups"
    private const val ZIP = ".zip"
    private const val LOCKED = ".zip.locked"

    /** One backup on a share. */
    data class Backup(val path: String, val device: String, val name: String, val mtimeMs: Long, val locked: Boolean) {
        /** Its date and time, as written in its name. */
        val label: String get() = stampLabel(name) ?: name
    }

    sealed interface Listed {
        data class Found(val backups: List<Backup>) : Listed
        data class Failed(val line: String) : Listed
    }

    /** This device's folder name on a share: its model, plus the start of its id when it has one. Pure but for [Build]. */
    fun deviceFolder(context: Context): String = folderName(Build.MODEL ?: "", DeviceIdentity.id(context))

    /** [model] reduced to letters, digits, `-` and `_`, then `-` and the first 8 of [id]. Pure. */
    fun folderName(model: String, id: String?): String {
        val base = model.trim().replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifBlank { "device" }.take(40)
        return if (id.isNullOrBlank()) base else "$base-${id.take(8)}"
    }

    /** A backup's file name for [now] (UTC). Pure. */
    fun fileName(now: Long, locked: Boolean): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))
        return "droidtop-backup-$stamp" + if (locked) LOCKED else ZIP
    }

    /** "2026-11-05 06:30 UTC" from a name [fileName] made, or null for another name. Pure. */
    fun stampLabel(name: String): String? {
        val m = Regex("""^droidtop-backup-(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})\d{2}\.zip(\.locked)?$""").find(name) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        return "$y-$mo-$d $h:$mi UTC"
    }

    /** Whether [name] is a backup this reads: a zip, or a locked one. Pure. */
    fun isBackup(name: String): Boolean = name.endsWith(ZIP) || name.endsWith(LOCKED)

    /**
     * Writes the archive with [write] into droidtop's cache, sends it to [share] (sealed unless the share is trusted),
     * and removes the local copy. The line to show.
     */
    fun backUp(context: Context, share: StorageShare, write: (OutputStream) -> String): String {
        val dir = File(context.cacheDir, "share-backup").apply { mkdirs() }
        val file = File(dir, "outgoing$ZIP")
        try {
            val line = runCatching { file.outputStream().use(write) }.getOrElse {
                return "The backup could not be made on this device: ${it.message ?: it.javaClass.simpleName}"
            }
            val locked = !share.trusted
            val args = JSONObject()
                .put("share", StorageShares.nativeArgs(context, share))
                .put("path", "$ROOT/${deviceFolder(context)}/${fileName(System.currentTimeMillis(), locked)}")
                .put("file", file.absolutePath)
                .put("seal", locked)
            if (locked) {
                val seed = DeviceIdentity.seed(context) ?: return "This device could not make the key that locks its backups"
                args.put("seed", seed)
            }
            val reply = AgentNative.call("remote_put", args)
            AgentNative.failure(reply)?.let { return "The backup didn't reach ${share.name}: $it" }
            return "$line, to ${share.name}" + if (locked) ", locked" else ""
        } finally {
            file.delete()
        }
    }

    /** Every droidtop backup on [share], newest first, from every device's folder. */
    fun list(context: Context, share: StorageShare): Listed {
        val shareArgs = StorageShares.nativeArgs(context, share)
        // A folder's entries, or the share's own words for why it could not be listed.
        fun entries(dir: String): Pair<List<JSONObject>, String?> {
            val reply = AgentNative.call("remote_list", JSONObject().put("share", shareArgs).put("dir", dir))
            AgentNative.failure(reply)?.let { return emptyList<JSONObject>() to it }
            val array = reply.optJSONArray("entries") ?: return emptyList<JSONObject>() to null
            return (0 until array.length()).mapNotNull(array::optJSONObject) to null
        }
        val (devices, failed) = entries(ROOT)
        if (failed != null) return Listed.Failed("${share.name}: $failed")
        val backups = devices.filter { it.optBoolean("is_dir") }.flatMap { device ->
            val name = device.optString("name")
            entries("$ROOT/$name").first.filter { !it.optBoolean("is_dir") && isBackup(it.optString("name")) }.map {
                Backup(
                    path = "$ROOT/$name/${it.optString("name")}",
                    device = name,
                    name = it.optString("name"),
                    mtimeMs = it.optLong("mtime_ms"),
                    locked = it.optString("name").endsWith(LOCKED),
                )
            }
        }
        return Listed.Found(backups.sortedByDescending { it.name.substringAfter("droidtop-backup-") })
    }

    /** Fetches [backup] into droidtop's cache (opened when this device locked it), hands it to [restore], removes it. */
    fun fetch(context: Context, share: StorageShare, backup: Backup, restore: (File) -> String): String {
        val dir = File(context.cacheDir, "share-backup").apply { mkdirs() }
        val file = File(dir, "incoming$ZIP")
        try {
            val args = JSONObject()
                .put("share", StorageShares.nativeArgs(context, share))
                .put("path", backup.path)
                .put("file", file.absolutePath)
            if (backup.locked) {
                val seed = DeviceIdentity.seed(context) ?: return "This device has no key to open a locked backup"
                args.put("seed", seed)
            }
            val reply = AgentNative.call("remote_get", args)
            if (reply.optString("code") == "locked_elsewhere") {
                return "Another device locked this backup, so this device can't open it"
            }
            AgentNative.failure(reply)?.let { return "The backup couldn't be fetched from ${share.name}: $it" }
            return restore(file)
        } finally {
            file.delete()
        }
    }
}
