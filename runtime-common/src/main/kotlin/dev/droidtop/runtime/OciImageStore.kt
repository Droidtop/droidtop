package dev.droidtop.runtime

import android.content.Context
import dev.droidtop.net.AtomicWrite
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

    /**
     * A pulled image: [digest] is the key it was pulled by, [manifestDigest] the platform manifest actually stored.
     * [downloaded] is true when [pull] fetched it now, false when it was already kept.
     */
    data class Image(
        val digest: String,
        val reference: String,
        val manifestDigest: String,
        val config: Descriptor,
        val layers: List<Descriptor>,
        val downloaded: Boolean = false,
    )

    /** One stored image for the cache-management UI: [sizeBytes] counts every blob it uses, shared ones included. */
    /** One stored image; [createdMs] is when its config says it was built, null when the config does not say. */
    data class Entry(val digest: String, val reference: String, val sizeBytes: Long, val createdMs: Long? = null)

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
            imageOf(added).copy(downloaded = true)
        }
    }

    /**
     * Runs [block] with the image pulled by [digest] marked in use, so no eviction, Clear or cache-off removal takes
     * it while a create is still unpacking it (docs/SPEC.md 3, "Storage is checked before it is spent"). Counted, so
     * two creates from one image each hold it.
     */
    suspend fun <T> using(digest: String, block: suspend () -> T): T {
        synchronized(pins) { pins.merge(digest, 1, Int::plus) }
        try {
            return block()
        } finally {
            synchronized(pins) { pins.compute(digest) { _, n -> if (n == null || n <= 1) null else n - 1 } }
        }
    }

    private fun inUse(): Set<String> = synchronized(pins) { pins.keys.toSet() }

    suspend fun entries(): List<Entry> = lock.withLock {
        withContext(Dispatchers.IO) {
            val index = readIndex() ?: return@withContext emptyList()
            manifestsOf(index).mapNotNull { entry ->
                val stored = runCatching { imageOf(entry) }.getOrNull() ?: return@mapNotNull null
                Entry(
                    digest = stored.digest,
                    reference = stored.reference,
                    sizeBytes = (entry.long("size") ?: 0) + stored.config.size + stored.layers.sumOf { it.size },
                    createdMs = runCatching { createdMs(blob(stored.config.digest).readText()) }.getOrNull(),
                )
            }
        }
    }

    /** The run settings of the image pulled by [digest] (its config blob's `config`), or null when it is not here. */
    suspend fun runConfig(digest: String): ImageRunConfig? = lock.withLock {
        withContext(Dispatchers.IO) {
            val index = readIndex() ?: return@withContext null
            val entry = manifestsOf(index).firstOrNull { it.annotation(KEY_DIGEST) == digest } ?: return@withContext null
            val stored = runCatching { imageOf(entry) }.getOrNull() ?: return@withContext null
            runCatching { ImageRunConfig.fromImageConfig(blob(stored.config.digest).readText()) }.getOrNull()
        }
    }

    /** What the stored images take on disk together: each blob once, however many images share it. */
    suspend fun diskBytes(): Long = lock.withLock { withContext(Dispatchers.IO) { blobBytes() } }

    /** Forgets the image pulled by [digest] and deletes every blob no other image uses. */
    suspend fun remove(digest: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            val index = readIndex() ?: return@withContext
            writeIndex(index, manifestsOf(index).filter { it.annotation(KEY_DIGEST) != digest })
            collectGarbage()
        }
    }

    /**
     * The cache-off path ([ImageCachePolicy.enabled] false): forgets the image a create just downloaded unless
     * another create is still using it ([using]), and deletes the blobs nothing else uses.
     */
    suspend fun removeUnlessInUse(digest: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            if (digest in inUse()) return@withContext
            val index = readIndex() ?: return@withContext
            writeIndex(index, manifestsOf(index).filter { it.annotation(KEY_DIGEST) != digest })
            collectGarbage()
        }
    }

    /**
     * Removes least recently used images until the blobs on disk fit
     * [ImageCachePolicy.maxCacheBytes], never the image pulled by [keep]
     * (the one just used) nor one an in-progress create is using ([using]),
     * and deletes blobs nothing references, including the leftovers of an
     * interrupted pull. With the cache off it removes nothing kept: what is
     * there stays until it is cleared. Which images go is
     * [ImageCacheEviction.victims]. Run after each pull and when the limit is
     * lowered.
     */
    suspend fun evictToFit(policy: ImageCachePolicy, keep: String?) = lock.withLock {
        withContext(Dispatchers.IO) {
            collectGarbage()
            if (!policy.enabled) return@withContext
            val limit = policy.maxCacheBytes ?: return@withContext
            val index = readIndex() ?: return@withContext
            val entries = manifestsOf(index)
            val kept = entries.map { entry ->
                ImageCacheEviction.Kept(
                    digest = entry.annotation(KEY_DIGEST) ?: entry.string("digest").orEmpty(),
                    blobs = mutableSetOf<String>().also { blobsOf(entry, it) },
                )
            }
            val victims = ImageCacheEviction.victims(kept, blobSizes(), limit, inUse() + listOfNotNull(keep)).toSet()
            if (victims.isEmpty()) return@withContext
            writeIndex(index, entries.filterIndexed { i, _ -> i !in victims })
            collectGarbage()
        }
    }

    /**
     * Settings > Containers' Clear: deletes every kept image but those an in-progress create is using ([using]).
     * Containers are untouched: each has its own copy of its image's files.
     */
    suspend fun clear() = lock.withLock {
        withContext(Dispatchers.IO) {
            legacyCacheDir?.deleteRecursively()
            val busy = inUse()
            if (busy.isEmpty()) {
                root.deleteRecursively()
                return@withContext
            }
            val index = readIndex() ?: return@withContext
            writeIndex(index, manifestsOf(index).filter { it.annotation(KEY_DIGEST) in busy })
            collectGarbage()
        }
    }

    /** Every blob file's size on disk, by digest. */
    private fun blobSizes(): Map<String, Long> = buildMap {
        File(root, "blobs").listFiles().orEmpty().forEach { algorithmDir ->
            algorithmDir.listFiles().orEmpty().forEach { file -> if (file.isFile) put("${algorithmDir.name}:${file.name}", file.length()) }
        }
    }

    private fun blobBytes(): Long =
        File(root, "blobs").walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Deletes every blob, and every temporary file crane left in the blob directories, that no index entry reaches. */
    private fun collectGarbage() {
        val index = readIndex() ?: return
        val reachable = mutableSetOf<String>()
        manifestsOf(index).forEach { blobsOf(it, reachable) }

        File(root, "blobs").listFiles().orEmpty().forEach { algorithmDir ->
            algorithmDir.listFiles().orEmpty().forEach { file ->
                if (file.isFile && "${algorithmDir.name}:${file.name}" !in reachable) file.delete()
            }
        }
    }

    /** Adds to [into] every blob [descriptor] reaches: its manifest, config and layers, and those of an index's children. */
    private fun blobsOf(descriptor: JsonObject, into: MutableSet<String>) {
        val digest = descriptor.string("digest") ?: return
        if (!into.add(digest)) return
        val manifest = runCatching { parseJson(blob(digest)) }.getOrNull() ?: return
        (manifest["config"] as? JsonObject)?.string("digest")?.let { into += it }
        (manifest["layers"] as? JsonArray)?.forEach { layer -> (layer as? JsonObject)?.string("digest")?.let { into += it } }
        (manifest["manifests"] as? JsonArray)?.forEach { child -> (child as? JsonObject)?.let { blobsOf(it, into) } }
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
        AtomicWrite.text(File(root, "index.json"), json.encodeToString(JsonElement.serializer(), updated))
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

        /** Images in-progress creates are using ([using]), by digest, with how many hold each; process-wide like [lock]. */
        private val pins = HashMap<String, Int>()

        /** When an image config ([text], its config blob) says the image was built: its `created`, or null. */
        fun createdMs(text: String): Long? = runCatching {
            val created = (json.parseToJsonElement(text).jsonObject["created"] as? JsonPrimitive)?.contentOrNull ?: return null
            java.time.Instant.parse(created).toEpochMilli().takeIf { it > 0 }
        }.getOrNull()

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
