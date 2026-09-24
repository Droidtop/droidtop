package dev.droidtop.library.consoles

import android.content.Context
import dev.droidtop.library.EnginesDatabase
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The index-driven refresh of the platform databases (docs/SPEC.md 7e2).
 *
 * droidtop-platforms is a TREE -- one file per engine, platform, player,
 * BIOS set, device -- with an `index.json` naming every file and its
 * sha256. A refresh downloads that one small index, compares each entry
 * against what this device already holds, and downloads only the files
 * whose hash changed: a one-engine fix is a few hundred bytes rather than
 * the whole registry, and a repeat check with nothing new costs exactly
 * one request.
 *
 * The apps still PARSE monolithic databases (`engines-database.json` and
 * friends), so the per-file cache is composed back into those documents
 * here and handed to the same `install` entry points a whole-file download
 * uses -- one validation path, one atomic replace, whichever way the bytes
 * arrived. A composed document that does not parse is discarded and the
 * previous database stays exactly as it was.
 *
 * The index is also how this survives an older app meeting a newer repo:
 * a collection this app has no consumer for (hardware, controllers) is
 * cached and ignored rather than treated as an error, and a repo with no
 * index at all falls back to the legacy whole-file URLs
 * ([PlatformDatabases.refresh]).
 */
object PlatformDatabaseIndex {
    const val INDEX_FILE_NAME = "index.json"

    /** Where the per-file cache and the last index this device accepted live. */
    private const val CACHE_DIR = "platform-db"

    data class Collection(
        val name: String,
        /** The monolithic document's array/object key, e.g. `engines`. */
        val key: String,
        /** `array` (order is the files' index order) or `object` (keyed by file id). */
        val form: String,
        /** Field carrying the id inside an object-form file, dropped when composing. */
        val idField: String?,
        val version: Int,
        /** Document-level fields that are neither `version` nor [key], e.g. `comment`. */
        val meta: Map<String, String>,
        /** The monolithic file name this composes into, or null when nothing consumes it yet. */
        val legacy: String?,
    )

    data class Entry(val path: String, val collection: String, val sha256: String) {
        /** Object-form files are keyed by their file name, so the tree IS the id map. */
        val id: String get() = path.substringAfterLast('/').removeSuffix(".json")
    }

    data class Index(val schemaVersion: Int, val collections: Map<String, Collection>, val files: List<Entry>)

    /** What a refresh did, for the settings row to report. */
    data class Refreshed(val downloaded: Int, val unchanged: Int, val counts: Map<String, Int>)

    fun parse(text: String): Index {
        val root = JSONObject(text)
        val collectionsJson = root.getJSONObject("collections")
        val collections = LinkedHashMap<String, Collection>()
        for (name in collectionsJson.keys()) {
            val json = collectionsJson.getJSONObject(name)
            val metaJson = json.optJSONObject("meta")
            collections[name] = Collection(
                name = name,
                key = json.getString("key"),
                form = json.optString("form", "array"),
                idField = json.optString("idField", "").ifEmpty { null },
                version = json.optInt("version", 1),
                meta = metaJson?.let { m -> buildMap { m.keys().forEach { put(it, m.getString(it)) } } } ?: emptyMap(),
                legacy = json.optString("legacy", "").ifEmpty { null },
            )
        }
        val filesJson = root.getJSONArray("files")
        val files = ArrayList<Entry>(filesJson.length())
        for (i in 0 until filesJson.length()) {
            val json = filesJson.getJSONObject(i)
            files += Entry(
                path = json.getString("path"),
                collection = json.getString("collection"),
                sha256 = json.getString("sha256").lowercase(),
            )
        }
        check(collections.isNotEmpty() && files.isNotEmpty()) { "Index names no files" }
        return Index(root.optInt("schemaVersion", 1), collections, files)
    }

    /**
     * Fetches the index and everything it says changed, then installs every
     * collection this app consumes. Returns null when the source has no
     * index (an older repo, or a fork that only publishes the monolithic
     * files) so the caller can fall back.
     */
    fun refresh(context: Context, baseUrl: String, onStatus: (String) -> Unit): Refreshed? {
        onStatus("Checking the platform index...")
        val indexText = PlatformDatabaseTransport.getOrNull(baseUrl + "/" + INDEX_FILE_NAME) ?: return null
        val index = parse(indexText)

        val cache = File(context.filesDir, CACHE_DIR)
        var downloaded = 0
        var unchanged = 0
        val contents = LinkedHashMap<String, String>()
        for (entry in index.files) {
            val cached = File(cache, entry.path)
            val onDisk = cached.takeIf { it.isFile }?.readText()
            val text = if (onDisk != null && PlatformDatabaseTransport.sha256(onDisk) == entry.sha256) {
                unchanged++
                onDisk
            } else {
                onStatus("Fetching " + entry.path + "...")
                val fetched = PlatformDatabaseTransport.get(baseUrl + "/" + entry.path)
                check(PlatformDatabaseTransport.sha256(fetched) == entry.sha256) {
                    entry.path + " does not match the hash the index published"
                }
                downloaded++
                fetched
            }
            contents[entry.path] = text
        }

        // Nothing is written until every file is in hand and every composed
        // document has passed its database's own validation, so a refresh
        // that dies half way through, or one collection that does not
        // parse, leaves every database on the previous, consistent snapshot.
        val composed = ArrayList<Triple<String, Consumer, String>>()
        for (collection in index.collections.values) {
            val legacy = collection.legacy ?: continue
            val consumer = CONSUMERS[legacy] ?: continue // nothing in this build reads it
            val entries = index.files.filter { it.collection == collection.name }
            if (entries.isEmpty()) continue
            val text = compose(collection, entries.map { it to contents.getValue(it.path) })
            try {
                consumer.validate(text)
            } catch (e: Exception) {
                throw IllegalStateException(collection.name + " did not validate: " + e.message, e)
            }
            composed += Triple(collection.name, consumer, text)
        }
        check(composed.isNotEmpty()) { "The index carried no database this build knows how to read" }
        val counts = LinkedHashMap<String, Int>()
        for ((name, consumer, text) in composed) counts[name] = consumer.install(context, text)

        for ((path, text) in contents) PlatformDatabaseTransport.write(File(cache, path), text)
        PlatformDatabaseTransport.write(File(cache, INDEX_FILE_NAME), indexText)
        return Refreshed(downloaded, unchanged, counts)
    }

    /** Rebuilds a monolithic document from the per-file tree. */
    fun compose(collection: Collection, files: List<Pair<Entry, String>>): String {
        val root = JSONObject()
        root.put("version", collection.version)
        for ((key, value) in collection.meta) root.put(key, value)
        when (collection.form) {
            "object" -> {
                val body = JSONObject()
                for ((entry, text) in files) {
                    val item = JSONObject(text)
                    collection.idField?.let { item.remove(it) }
                    body.put(entry.id, item)
                }
                root.put(collection.key, body)
            }
            else -> {
                val body = JSONArray()
                for ((_, text) in files) body.put(JSONObject(text))
                root.put(collection.key, body)
            }
        }
        return root.toString(1)
    }

    /**
     * The documents this build reads, each with its database's own
     * validation (no write) and validate-then-replace install.
     */
    private class Consumer(val validate: (String) -> Int, val install: (Context, String) -> Int)

    private val CONSUMERS: Map<String, Consumer> by lazy {
        mapOf(
            "engines-database.json" to Consumer(EnginesDatabase::validate, EnginesDatabase::install),
            "platforms-database.json" to Consumer(PlatformsDatabase::validate, PlatformsDatabase::install),
            "players-database.json" to Consumer(PlayersDatabaseUpdater::validate, PlayersDatabaseUpdater::install),
            "bios-database.json" to Consumer(BiosDatabase::validate, BiosDatabase::install),
        )
    }
}
