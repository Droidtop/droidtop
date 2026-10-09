package dev.droidtop.library.integrations

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.File
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/**
 * One catalog droidtop lists plugins from (docs/SPEC.md 12a "Added catalogs"). The official catalog
 * (droidtop-platforms' index, [PluginCatalogSources.OFFICIAL]) is the first, built in and never
 * removable; every other one is a catalog the person added by address or QR code and accepted.
 */
data class PluginCatalogSource(
    /** [PluginCatalogSources.OFFICIAL_ID], or the added catalog's own id ("owner/name"). */
    val id: String,
    val name: String,
    /** The index address; null for the official catalog, whose address follows the platform-database base URL. */
    val indexUrl: String?,
    val homepage: String? = null,
    val official: Boolean = false,
    /**
     * The catalog's organisation plugin master (SPKI, base64), trusted on first use when the person accepted
     * the catalog; null for a catalog without one. It is also its own origin's trusted key, certifies that
     * origin's repository keys, signs its revocation list and certifies the key its index is signed with.
     */
    val masterKeyBase64: String? = null,
    /** Whether an index signature has verified; from then on an unsigned copy is refused. */
    val indexSigned: Boolean = false,
    /** The disclaimer version the person accepted; nothing is listed while the index carries a newer one. */
    val acceptedDisclaimer: Int = 0,
)

/**
 * The catalogs the person added, in droidtop's own private storage (`filesDir/plugin-catalogs.json`),
 * written through a temp file and a rename like the key store beside it. The official catalog is not
 * in the file and cannot be: it is [OFFICIAL], first in [all].
 */
object PluginCatalogSources {
    const val OFFICIAL_ID = "droidtop"
    val OFFICIAL = PluginCatalogSource(id = OFFICIAL_ID, name = "droidtop", indexUrl = null, official = true)

    private const val STORE_NAME = "plugin-catalogs.json"
    const val INDEX_FILE = "index.json"
    const val INDEX_SIGNATURE_FILE = "index.json.sig"
    const val INDEX_CERTIFICATE_FILE = "index.cert"
    const val REVOCATIONS_FILE = "revocations.json"

    fun storeFile(context: Context): File = File(context.filesDir, STORE_NAME)

    /** The official catalog, then the added ones in the order they were added. Disk. */
    fun all(context: Context): List<PluginCatalogSource> = listOf(OFFICIAL) + added(storeFile(context))

    fun byId(context: Context, id: String): PluginCatalogSource? = all(context).firstOrNull { it.id == id }

    fun added(file: File): List<PluginCatalogSource> {
        if (!file.isFile) return emptyList()
        val array = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val json = array.optJSONObject(i) ?: return@mapNotNull null
            val id = json.optString("id").takeIf { it.isNotBlank() && it != OFFICIAL_ID } ?: return@mapNotNull null
            val url = json.optString("indexUrl").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            PluginCatalogSource(
                id = id,
                name = json.optString("name").ifBlank { id },
                indexUrl = url,
                homepage = text(json, "homepage"),
                masterKeyBase64 = text(json, "masterKey"),
                indexSigned = json.optBoolean("indexSigned", false),
                acceptedDisclaimer = json.optInt("acceptedDisclaimer", 0),
            )
        }
    }

    /** Adds [source], or replaces the entry with its id. Never the official catalog. */
    fun put(file: File, source: PluginCatalogSource) {
        require(!source.official && source.id != OFFICIAL_ID) { "the official catalog is built in" }
        val list = added(file)
        val next = if (list.any { it.id == source.id }) list.map { if (it.id == source.id) source else it } else list + source
        save(file, next)
    }

    /** Removes the added catalog [id]; false when there is none. */
    fun remove(file: File, id: String): Boolean {
        val list = added(file)
        if (list.none { it.id == id }) return false
        save(file, list.filterNot { it.id == id })
        return true
    }

    private fun save(file: File, list: List<PluginCatalogSource>) {
        val array = JSONArray()
        list.forEach { source ->
            array.put(
                JSONObject()
                    .put("id", source.id)
                    .put("name", source.name)
                    .put("indexUrl", source.indexUrl)
                    .put("acceptedDisclaimer", source.acceptedDisclaimer)
                    .put("indexSigned", source.indexSigned)
                    .apply {
                        source.homepage?.let { put("homepage", it) }
                        source.masterKeyBase64?.let { put("masterKey", it) }
                    },
            )
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("couldn't replace ${file.name}")
        }
    }

    private fun text(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }

    /**
     * The index address for what the person typed or scanned: a GitHub repository address
     * (`https://github.com/<owner>/<repo>`, its /tree/<branch> form too) becomes the raw address of
     * that repository's index.json; an https address ending in .json is the index itself; any other
     * https address is a folder with index.json at its top. Null for anything else: plain http is
     * refused, because the first fetch is the one the person's trust decision is made on.
     */
    fun indexUrlFor(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        if (!trimmed.startsWith("https://", ignoreCase = true)) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host == "github.com" || host == "www.github.com") {
            val segments = uri.path.orEmpty().trim('/').split('/')
            if (segments.size < 2 || segments[0].isBlank() || segments[1].isBlank()) return null
            val branch = if (segments.size >= 4 && (segments[2] == "tree" || segments[2] == "blob")) segments[3] else "HEAD"
            return "https://raw.githubusercontent.com/${segments[0]}/${segments[1].removeSuffix(".git")}/$branch/$INDEX_FILE"
        }
        if (uri.query != null || uri.fragment != null) return null
        return if (trimmed.endsWith(".json", ignoreCase = true)) trimmed else "$trimmed/$INDEX_FILE"
    }

    /** A file published beside the index: the same folder, another name. */
    fun siblingUrl(indexUrl: String, name: String): String = indexUrl.substringBeforeLast('/') + "/" + name

    /**
     * The text of the QR code in a picked image (a photo or a screenshot of the code), or null when
     * the image has none. zxing's decoder, the same library droidtop draws its own QR codes with.
     * Disk; call off the main thread.
     */
    fun qrText(context: Context, uri: Uri): String? = runCatching {
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@runCatching null
        // A camera photo is far larger than a QR code needs; scale it down so the decode stays quick.
        val scaled = if (maxOf(bitmap.width, bitmap.height) > 1600) {
            val factor = 1600f / maxOf(bitmap.width, bitmap.height)
            android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt(), (bitmap.height * factor).toInt(), true)
        } else {
            bitmap
        }
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        val source = RGBLuminanceSource(scaled.width, scaled.height, pixels)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
    }.getOrNull()
}
