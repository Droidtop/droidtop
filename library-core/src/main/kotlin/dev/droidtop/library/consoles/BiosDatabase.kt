package dev.droidtop.library.consoles

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Per-system BIOS requirements -- the EmuDeck-style "does this system
 * have the firmware it needs" half of droidtop's emulator setup helpers.
 * Data-driven exactly like [KnownPlayers] (docs/SPEC.md §7e2): a bundled
 * seed asset generated from Batocera's real, maintained BIOS registry
 * (batocera-systems -- the same md5/path data Batocera's own missing-bios
 * checker runs on; see droidtop-platforms/generator/from_batocera.py),
 * refreshable from the droidtop-platforms repository, with the same
 * validate-before-replace / ignore-unparseable defenses.
 *
 * File paths in the database are relative to a games root (Batocera's
 * own layout, "bios/scph5501.bin"), which matches droidtop's ES-DE-style
 * `<gamesRoot>/<system>/` ROM layout: the shared `bios` folder sits
 * beside the system folders.
 */
data class BiosFileSpec(val file: String, val md5: List<String>)

data class SystemBiosSpec(val systemId: String, val name: String, val files: List<BiosFileSpec>)

/** One checked file: present on disk, and (when hashes are known) whether one matched. */
data class BiosFileStatus(val spec: BiosFileSpec, val present: Boolean, val md5Ok: Boolean?)

object BiosDatabase {
    private const val DB_FILE_NAME = "bios-database.json"

    @Volatile
    private var cached: Map<String, SystemBiosSpec>? = null

    fun forSystem(context: Context, systemId: String): SystemBiosSpec? = all(context)[systemId]

    fun all(context: Context): Map<String, SystemBiosSpec> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val updated = File(context.filesDir, DB_FILE_NAME)
                .takeIf { it.isFile }
                ?.let { runCatching { parse(it.readText()) }.getOrNull() }
            val loaded = updated
                ?: runCatching { parse(context.assets.open(DB_FILE_NAME).bufferedReader().use { it.readText() }) }
                    .getOrElse { emptyMap() }
            cached = loaded
            return loaded
        }
    }

    fun invalidate() {
        cached = null
    }

    private fun parse(text: String): Map<String, SystemBiosSpec> {
        val root = JSONObject(text).getJSONObject("systems")
        val result = LinkedHashMap<String, SystemBiosSpec>()
        for (systemId in root.keys()) {
            val system = root.getJSONObject(systemId)
            val files = system.getJSONArray("files")
            val specs = ArrayList<BiosFileSpec>(files.length())
            for (i in 0 until files.length()) {
                val entry = files.getJSONObject(i)
                val md5s = entry.optJSONArray("md5")?.let { array ->
                    List(array.length()) { array.getString(it).lowercase() }
                } ?: emptyList()
                specs += BiosFileSpec(entry.getString("file"), md5s)
            }
            result[systemId] = SystemBiosSpec(systemId, system.optString("name", systemId), specs)
        }
        check(result.isNotEmpty()) { "BIOS database has no systems" }
        return result
    }

    /**
     * Checks [spec]'s files under [gamesRoot] -- present-by-name, plus a
     * real md5 verification when the registry knows hashes (BIOS files
     * are small; hashing them is cheap and catches the classic
     * "right name, wrong dump" failure EmuDeck's own checker exists for).
     */
    fun check(gamesRoot: File, spec: SystemBiosSpec): List<BiosFileStatus> = spec.files.map { fileSpec ->
        val onDisk = File(gamesRoot, fileSpec.file)
        when {
            !onDisk.isFile -> BiosFileStatus(fileSpec, present = false, md5Ok = null)
            fileSpec.md5.isEmpty() -> BiosFileStatus(fileSpec, present = true, md5Ok = null)
            else -> BiosFileStatus(fileSpec, present = true, md5Ok = md5Of(onDisk) in fileSpec.md5)
        }
    }

    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Refreshes the database from droidtop-platforms -- same
     * validate-before-replace atomic-write contract
     * [PlayersDatabaseUpdater] established. Returns the system count.
     */
    fun update(context: Context, url: String = PlatformDatabaseSource.urlFor(context, DB_FILE_NAME)): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        val text = try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode} from $url" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val count = parse(text).size

        val dest = File(context.filesDir, DB_FILE_NAME)
        val temp = File(context.filesDir, "$DB_FILE_NAME.downloading")
        temp.writeText(text)
        check(temp.renameTo(dest) || run { dest.delete(); temp.renameTo(dest) }) {
            "Couldn't move the downloaded BIOS database into place"
        }
        invalidate()
        return count
    }
}
