package dev.droidtop.stores.ea

import android.content.Context
import dev.droidtop.net.AtomicWrite
import dev.droidtop.runtime.SafeDelete
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Installed files are independent of the account's current library and survive sign-out. */
internal data class EaInstall(
    val id: String,
    val offerId: String,
    val title: String,
    val art: String?,
    val root: String,
    val path: String,
    val complete: Boolean = false,
    val everInstalled: Boolean = false,
    val version: String = "",
    val sizeBytes: Long = 0,
    val executable: String = "",
    val arguments: List<String> = emptyList(),
    val extra: Map<String, String> = emptyMap(),
)

internal object EaInstalls {
    const val PROOF = ".droidtop-ea-install"
    fun file(context: Context) = File(context.filesDir, "ea/installs.json")

    @Synchronized
    fun read(context: Context): List<EaInstall> = file(context).takeIf { it.isFile }
        ?.let { decode(it.readText()) }.orEmpty()

    @Synchronized
    fun put(context: Context, row: EaInstall) {
        AtomicWrite.text(file(context), encode(read(context).filterNot { it.id == row.id } + row))
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        AtomicWrite.text(file(context), encode(read(context).filterNot { it.id == id }))
    }

    /** A deletion or resumed install must prove this is the exact folder this store created, inside its recorded root. */
    fun ownedFolder(row: EaInstall): File {
        val root = File(row.root).canonicalFile
        val folder = File(row.path).canonicalFile
        check(folder.parentFile == root && folder != root) { "The EA install is outside its store folder" }
        val proof = File(folder, PROOF)
        check(proof.isFile && proof.readText() == row.id) { "This folder has no record of an EA install made by droidtop" }
        return folder
    }

    fun delete(row: EaInstall) {
        val folder = ownedFolder(row)
        check(SafeDelete.deleteWithin(File(row.root), folder)) { "Could not remove ${row.title}'s install" }
    }

    fun encode(rows: List<EaInstall>): String = JSONArray(rows.map { row ->
        JSONObject().put("id", row.id).put("offer", row.offerId).put("title", row.title).put("art", row.art ?: "")
            .put("root", row.root).put("path", row.path).put("complete", row.complete).put("everInstalled", row.everInstalled)
            .put("version", row.version).put("size", row.sizeBytes).put("exe", row.executable)
            .put("arguments", JSONArray(row.arguments)).put("extra", JSONObject(row.extra))
    }).toString()

    fun decode(text: String): List<EaInstall> {
        val array = JSONArray(text)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val args = o.optJSONArray("arguments") ?: JSONArray()
            val extra = o.optJSONObject("extra") ?: JSONObject()
            EaInstall(
                o.getString("id"), o.getString("offer"), o.getString("title"), o.optString("art").ifBlank { null },
                o.getString("root"), o.getString("path"), o.optBoolean("complete"), o.optBoolean("everInstalled"),
                o.optString("version"), o.optLong("size"), o.optString("exe"),
                (0 until args.length()).map { args.getString(it) }, extra.keys().asSequence().associateWith { extra.getString(it) },
            )
        }
    }
}
