package dev.droidtop.runtime.windows.redist

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a game's first launch in a prefix installs before the game starts (docs/SPEC.md 9, "Redistributable installers";
 * Droidtop/tracker#916): every installer [RedistFinder] finds whose [PeInfo] matches a [RedistTable] row that droidtop
 * does not provide and this prefix has not run yet ([RedistRecord]), one per row and architecture.
 */
object RedistPlan {
    data class Pending(
        val file: File,
        val row: RedistTable.Row,
        val is64: Boolean,
    ) {
        val key: String get() = row.id + if (is64) "-x64" else "-x86"
        val title: String get() = row.title + if (is64) " (x64)" else " (x86)"
    }

    /** The pending installs for [gameRoot] in the prefix at [prefixRoot]. Disk work. */
    fun pending(gameRoot: File, prefixRoot: File): List<Pending> {
        val done = RedistRecord.read(prefixRoot).keys
        val seen = HashSet<String>()
        return RedistFinder.find(gameRoot).mapNotNull { file ->
            val info = PeInfo.read(file) ?: return@mapNotNull null
            val row = RedistTable.match(info) ?: return@mapNotNull null
            // The installer's own name says the architecture it installs (vc_redist.x64.exe is a 32-bit program).
            val is64 = Regex("""(x64|amd64|64)""", RegexOption.IGNORE_CASE).containsMatchIn(info.originalName ?: file.name)
            Pending(file, row, is64).takeIf { !row.provided && it.key !in done && seen.add(it.key) }
        }
    }

    /** [pending] as the launch carries it ([fromJson] reads it back). Pure. */
    fun toJson(pending: List<Pending>): String = JSONArray(pending.map { p ->
        JSONObject().put("path", p.file.path).put("row", p.row.id).put("x64", p.is64)
    }).toString()

    fun fromJson(json: String?): List<Pending> {
        val array = json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val row = RedistTable.ROWS.firstOrNull { it.id == o.optString("row") } ?: return@mapNotNull null
            Pending(File(o.optString("path")), row, o.optBoolean("x64"))
        }
    }
}

/** A prefix's record of the redistributables it ran: `.wine/droidtop-redist.json`, key to outcome and time. */
object RedistRecord {
    private fun file(prefixRoot: File) = File(prefixRoot, ".wine/droidtop-redist.json")

    fun read(prefixRoot: File): Map<String, String> {
        val json = runCatching { JSONObject(file(prefixRoot).readText()) }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().associateWith { json.optJSONObject(it)?.optString("outcome").orEmpty() }
    }

    fun write(prefixRoot: File, key: String, outcome: String) {
        val f = file(prefixRoot)
        val json = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        json.put(key, JSONObject().put("outcome", outcome).put("at", System.currentTimeMillis()))
        f.parentFile?.mkdirs()
        f.writeText(json.toString(2))
    }
}

/**
 * Licence acceptances the person asked droidtop to remember, for every game: keyed by vendor, product, licence version
 * and a SHA-256 of what was shown, so a changed licence asks again. Stored in droidtop's settings file, which its backup
 * writes.
 */
object RedistLicences {
    private const val PREFIX = "droidtop_redist_licence_"

    /** What the sheet shows for [licence], and so what an acceptance is of. Pure. */
    fun text(licence: RedistTable.Licence): String =
        "${licence.product} is licensed by ${licence.vendor} under its own terms, published at ${licence.url}. " +
            "Installing it means you accept them."

    /** The key an acceptance of [licence] is remembered under. Pure. */
    fun key(licence: RedistTable.Licence): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text(licence).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return listOf(licence.vendor, licence.product, licence.version, digest).joinToString("|")
    }

    private fun prefs(context: Context) = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)

    fun accepted(context: Context, licence: RedistTable.Licence): Boolean = prefs(context).contains(PREFIX + key(licence))

    fun remember(context: Context, licence: RedistTable.Licence) {
        prefs(context).edit().putString(PREFIX + key(licence), JSONObject()
            .put("vendor", licence.vendor).put("product", licence.product).put("version", licence.version)
            .put("at", System.currentTimeMillis()).toString()).apply()
    }
}
