package dev.droidtop.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Every pulled image, kept as one OCI image layout (the OCI image-spec's
 * on-disk format: `oci-layout`, `index.json`, `blobs/sha256/<hex>`), which
 * `crane pull --format=oci` writes into. Blobs are content-addressed, so a
 * layer two images share (the same base under two tags, a sibling and the
 * primary off one distro) is downloaded and stored once, and the image's
 * config (Env, User, Entrypoint) is kept rather than thrown away as
 * `crane export` did.
 *
 * Each image is one entry of `index.json`, annotated with the digest it was
 * resolved to ([RootfsImage.digest], which for a multi-platform image is
 * the index's digest, not the platform manifest crane stored) and the
 * reference it was pulled as, which the cache-management UI shows. Entries
 * are kept oldest-used first, so [evictToFit] removes the least recently
 * used image; a blob no remaining entry references is deleted with it.
 *
 * All changes to the layout happen under one process-wide lock: crane
 * appends to `index.json` by rewriting it, and a collection running beside
 * a pull would delete the layers that pull just wrote.
 */
class OciImageStore(
    private val root: File,
    private val craneBinary: () -> String,
    private val platform: () -> String,
    /** Where the tar-per-image cache this replaced lived; deleted on first use. */
    private val legacyCacheDir: File? = null,
) {
    data class Descriptor(val mediaType: String, val digest: String, val size: Long)

    /** A pulled image: [digest] is the key it was pulled by, [manifestDigest] the platform manifest actually stored. */
    data class Image(
        val digest: String,
        val reference: String,
        val manifestDigest: String,
        val config: Descriptor,
        val layers: List<Descriptor>,
    )

    /** One stored image for the cache-management UI: [sizeBytes] counts every blob it uses, shared ones included. */
    data class Entry(val digest: String, val reference: String, val sizeBytes: Long)

    /** The file holding blob [digest], after checking the digest is one (it comes from JSON the registry wrote). */
    fun blob(digest: String): File {
        val (algorithm, hex) = splitDigest(digest) ?: error("not a blob digest: $digest")
        return File(File(File(root, "blobs"), algorithm), hex)
    }

    /**
     * [image] from the store, pulling it with crane first if it is not
     * there. [RootfsImage.digest] must be resolved: the store is keyed on
     * it, and pulling `reference@digest` makes the pull exactly that image
     * whatever the tag has moved to since.
     */
    suspend fun pull(image: RootfsImage): Image = lock.withLock {
        withContext(Dispatchers.IO) {
            legacyCacheDir?.takeIf { it.exists() }?.deleteRecursively()
            val digest = requireNotNull(image.digest) { "pull needs a resolved digest for ${image.reference}" }

            val current = readIndex()
            val entries = current?.let(::manifestsOf).orEmpty()
            val existing = entries.indexOfFirst { it.annotation(KEY_DIGEST) == digest }
            if (current != null && existing >= 0) {
                val stored = runCatching { imageOf(entries[existing]) }.getOrNull()
                if (stored != null && (listOf(stored.config) + stored.layers).all { blob(it.digest).isFile }) {
                    // Most recently used moves to the end, so eviction takes the oldest-used first.
                    writeIndex(current, entries.filterIndexed { i, _ -> i != existing } + entries[existing])
                    return@withContext stored
                }
                // A blob went missing (storage cleared under us): forget the entry and pull again.
                writeIndex(current, entries.filterIndexed { i, _ -> i != existing })
            }

            val before = readIndex()?.let { manifestsOf(it).size } ?: 0
            val result = ProcessRunner.run(
                listOf(craneBinary(), "pull", "--format=oci", "--platform", platform(), "${image.reference}@$digest", root.absolutePath),
            )
            check(result.succeeded) { "crane pull failed for ${image.reference}@$digest: ${result.stderr.ifBlank { result.stdout }}" }

            // crane appended exactly one entry: the platform's image manifest.
            val index = checkNotNull(readIndex()) { "crane pull wrote no index.json into $root" }
            val pulled = manifestsOf(index)
            check(pulled.size == before + 1) { "crane pull added ${pulled.size - before} entries to $root, expected one" }
            val added = JsonObject(
                pulled.last() + ("annotations" to JsonObject(
                    (pulled.last()["annotations"] as? JsonObject).orEmpty() +
                        mapOf(KEY_DIGEST to JsonPrimitive(digest), KEY_REFERENCE to JsonPrimitive(image.reference)),
                )),
            )
            writeIndex(index, pulled.dropLast(1) + added)
            imageOf(added)
        }
    }

    suspend fun entries(): List<Entry> = lock.withLock {
        withContext(Dispatchers.IO) {
            val index = readIndex() ?: return@withContext emptyList()
            manifestsOf(index).mapNotNull { entry ->
                val stored = runCatching { imageOf(entry) }.getOrNull() ?: return@mapNotNull null
                Entry(
                    digest = stored.digest,
                    reference = stored.reference,
                    sizeBytes = (entry.long("size") ?: 0) + stored.config.size + stored.layers.sumOf { it.size },
                )
            }
        }
    }

    /** Forgets the image pulled by [digest] and deletes every blob no other image uses. */
    suspend fun remove(digest: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            val index = readIndex() ?: return@withContext
            writeIndex(index, manifestsOf(index).filter { it.annotation(KEY_DIGEST) != digest })
            collectGarbage()
        }
    }

    /**
     * Removes least recently used images until the blobs on disk fit
     * [ImageCachePolicy.maxCacheBytes], never the image pulled by [keep]
     * (the one just used), and deletes blobs nothing references, including
     * the leftovers of an interrupted pull.
     */
    suspend fun evictToFit(policy: ImageCachePolicy, keep: String?) = lock.withLock {
        withContext(Dispatchers.IO) {
            collectGarbage()
            val cap = policy.maxCacheBytes ?: return@withContext
            while (blobBytes() > cap) {
                val index = readIndex() ?: return@withContext
                val entries = manifestsOf(index)
                val victim = entries.indexOfFirst { it.annotation(KEY_DIGEST) != keep }
                if (victim < 0) return@withContext
                writeIndex(index, entries.filterIndexed { i, _ -> i != victim })
                collectGarbage()
            }
        }
    }

    suspend fun clear() = lock.withLock {
        withContext(Dispatchers.IO) {
            root.deleteRecursively()
            legacyCacheDir?.deleteRecursively()
        }
    }

    private fun blobBytes(): Long =
        File(root, "blobs").walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Deletes every blob, and every temporary file crane left in the blob directories, that no index entry reaches. */
    private fun collectGarbage() {
        val index = readIndex() ?: return
        val reachable = mutableSetOf<String>()
        fun visit(descriptor: JsonObject) {
            val digest = descriptor.string("digest") ?: return
            if (!reachable.add(digest)) return
            val manifest = runCatching { parseJson(blob(digest)) }.getOrNull() ?: return
            (manifest["config"] as? JsonObject)?.string("digest")?.let { reachable += it }
            (manifest["layers"] as? JsonArray)?.forEach { layer -> (layer as? JsonObject)?.string("digest")?.let { reachable += it } }
            (manifest["manifests"] as? JsonArray)?.forEach { child -> (child as? JsonObject)?.let(::visit) }
        }
        manifestsOf(index).forEach(::visit)

        File(root, "blobs").listFiles().orEmpty().forEach { algorithmDir ->
            algorithmDir.listFiles().orEmpty().forEach { file ->
                if (file.isFile && "${algorithmDir.name}:${file.name}" !in reachable) file.delete()
            }
        }
    }

    private fun imageOf(entry: JsonObject): Image {
        val manifestDigest = checkNotNull(entry.string("digest")) { "an index.json entry in $root has no digest" }
        val manifest = parseJson(blob(manifestDigest))
        check(manifest["layers"] is JsonArray) { "$manifestDigest is not an image manifest (${manifest.string("mediaType")})" }
        return Image(
            digest = entry.annotation(KEY_DIGEST) ?: manifestDigest,
            reference = entry.annotation(KEY_REFERENCE) ?: manifestDigest,
            manifestDigest = manifestDigest,
            config = descriptorOf(manifest["config"]!!.jsonObject),
            layers = manifest["layers"]!!.jsonArray.map { descriptorOf(it.jsonObject) },
        )
    }

    private fun descriptorOf(json: JsonObject): Descriptor {
        val digest = checkNotNull(json.string("digest")) { "a descriptor in $root has no digest" }
        check(splitDigest(digest) != null) { "not a blob digest: $digest" }
        return Descriptor(mediaType = json.string("mediaType").orEmpty(), digest = digest, size = json.long("size") ?: -1)
    }

    private fun readIndex(): JsonObject? {
        val file = File(root, "index.json")
        return if (file.isFile) parseJson(file) else null
    }

    private fun writeIndex(index: JsonObject, manifests: List<JsonObject>) {
        val updated = JsonObject(index + ("manifests" to JsonArray(manifests)))
        val temporary = File(root, "index.json.tmp")
        temporary.writeText(json.encodeToString(JsonElement.serializer(), updated))
        check(temporary.renameTo(File(root, "index.json"))) { "could not replace $root/index.json" }
    }

    private fun manifestsOf(index: JsonObject): List<JsonObject> =
        (index["manifests"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun parseJson(file: File): JsonObject = json.parseToJsonElement(file.readText()).jsonObject

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.annotation(key: String): String? =
        (this["annotations"] as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull

    companion object {
        /** Annotation holding the digest an image was pulled by. */
        const val KEY_DIGEST = "dev.droidtop.image.digest"

        /** Annotation holding the reference an image was pulled as, for display. */
        const val KEY_REFERENCE = "dev.droidtop.image.reference"

        private val lock = Mutex()
        private val json = Json { prettyPrint = true }

        /** The store in app-private storage, pulling with the packaged crane for this install's ABI. */
        fun of(context: Context): OciImageStore {
            val app = context.applicationContext
            return OciImageStore(
                root = File(app.filesDir, "oci"),
                craneBinary = { Crane.binaryPath(app) },
                platform = { Crane.platform(app) },
                legacyCacheDir = File(app.filesDir, "image-cache"),
            )
        }

        /** `sha256:<hex>` split into its parts, or null for anything that is not a plain digest. */
        internal fun splitDigest(digest: String): Pair<String, String>? {
            val algorithm = digest.substringBefore(':', "")
            val hex = digest.substringAfter(':', "")
            if (algorithm.isEmpty() || hex.isEmpty()) return null
            if (!algorithm.all { it in 'a'..'z' || it in '0'..'9' }) return null
            if (!hex.all { it in 'a'..'f' || it in '0'..'9' }) return null
            return algorithm to hex
        }
    }
}
