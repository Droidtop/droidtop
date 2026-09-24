package dev.droidtop.runtime

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * The store against a stand-in for `crane pull --format=oci`: a shell script
 * that copies a prepared image's blobs into the layout and appends its
 * manifest to index.json, as crane's layout.AppendImage does.
 */
class OciImageStoreTest {
    private val scratch: File = Files.createTempDirectory("oci-store-test").toFile()
    private val root = File(scratch, "oci")
    private val staging = File(scratch, "staging")
    private val pulls = File(scratch, "pulls")
    private val legacy = File(scratch, "image-cache").also { it.mkdirs(); File(it, "old.tar").writeText("old") }
    private val crane = File(scratch, "crane").apply {
        writeText(
            """
            #!/bin/sh
            # pull --format=oci --platform <p> <reference@sha256:hex> <layout>
            set -e
            ref="${'$'}5"; layout="${'$'}6"; image="${staging.path}/${'$'}{ref##*:}"
            echo "${'$'}ref" >> "${pulls.path}"
            mkdir -p "${'$'}layout/blobs/sha256"
            cp "${'$'}image"/blobs/* "${'$'}layout/blobs/sha256/"
            [ -f "${'$'}layout/oci-layout" ] || echo '{"imageLayoutVersion":"1.0.0"}' > "${'$'}layout/oci-layout"
            entry=${'$'}(cat "${'$'}image/descriptor.json")
            if [ -f "${'$'}layout/index.json" ]; then
              # "manifests" is the last key: drop the closing "]}" and append.
              current=${'$'}(tr -d '\n' < "${'$'}layout/index.json" | sed -e 's/[[:space:]]*][[:space:]]*}[[:space:]]*${'$'}//')
              case "${'$'}current" in *'[') sep='' ;; *) sep=',' ;; esac
              printf '%s%s%s]}' "${'$'}current" "${'$'}sep" "${'$'}entry" > "${'$'}layout/index.json"
            else
              printf '{"schemaVersion":2,"manifests":[%s]}' "${'$'}entry" > "${'$'}layout/index.json"
            fi
            """.trimIndent(),
        )
        setExecutable(true)
    }
    private val store = OciImageStore(root, { crane.path }, { "linux/arm64" }, legacy)

    @After
    fun cleanUp() {
        scratch.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Stages an image of [layers] (each a gzipped tar) and returns the digest it is pulled by. */
    private fun stage(key: String, vararg layers: ByteArray): String {
        val config = """{"architecture":"arm64","os":"linux","config":{"Env":["PATH=/usr/bin"]},"k":"$key"}""".toByteArray()
        val blobs = listOf(config) + layers
        val manifest = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json",""" +
            """"config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"${sha256(config)}","size":${config.size}},""" +
            """"layers":[${layers.joinToString(",") { """{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"${sha256(it)}","size":${it.size}}""" }}]}"""
        val manifestBytes = manifest.toByteArray()
        // The key is what a tag resolves to: an index digest, not the manifest's.
        val digest = sha256("index of $key".toByteArray())
        val dir = File(staging, digest.substringAfter(':')).also { File(it, "blobs").mkdirs() }
        (blobs + manifestBytes).forEach { File(File(dir, "blobs"), sha256(it).substringAfter(':')).writeBytes(it) }
        File(dir, "descriptor.json").writeText(
            """{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"${sha256(manifestBytes)}","size":${manifestBytes.size}}""",
        )
        return digest
    }

    private fun layer(vararg files: Pair<String, String>): ByteArray {
        val tar = OciFlattenerTest.Layer()
        files.forEach { (name, content) -> tar.file(name, content) }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(tar.bytes()) }
        return out.toByteArray()
    }

    private fun blobCount() = File(root, "blobs/sha256").listFiles().orEmpty().size

    private fun pullCount() = if (pulls.exists()) pulls.readLines().size else 0

    @Test
    fun `a pull is recorded by the digest it was asked for, and a second one is not downloaded again`() = runBlocking {
        val digest = stage("alpine", layer("etc/alpine-release" to "3.24"))
        val image = store.pull(RootfsImage("docker.io/library/alpine:latest", digest))
        assertEquals(digest, image.digest)
        assertEquals(1, image.layers.size)
        assertFalse("the old tar cache is gone", legacy.exists())

        store.pull(RootfsImage("docker.io/library/alpine:latest", digest))
        assertEquals(1, pullCount())
        assertEquals(listOf("docker.io/library/alpine:latest"), store.entries().map { it.reference })
    }

    @Test
    fun `a flatten reads the stored layers`() = runBlocking {
        val digest = stage("two", layer("a" to "old", "b" to "b"), layer("a" to "new"))
        val image = store.pull(RootfsImage("example/two:1", digest))
        val seen = mutableMapOf<String, String>()
        OciFlattener.flatten(store, image) { entry, data: InputStream -> seen[entry.name] = data.readBytes().decodeToString() }
        assertEquals(mapOf("a" to "new", "b" to "b"), seen)
    }

    @Test
    fun `a layer that no longer matches its digest fails the flatten and is deleted`() = runBlocking {
        val digest = stage("corrupt", layer("a" to "a"))
        val image = store.pull(RootfsImage("example/corrupt:1", digest))
        val blob = store.blob(image.layers.single().digest)
        blob.writeBytes(layer("a" to "tampered"))
        try {
            OciFlattener.flatten(store, image) { _, _ -> }
            fail("a corrupted layer was accepted")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("digest"))
        }
        assertFalse(blob.exists())
    }

    @Test
    fun `removing an image keeps the layers another image shares`() = runBlocking {
        val base = layer("etc/os-release" to "debian")
        val first = stage("first", base, layer("first" to "1"))
        val second = stage("second", base, layer("second" to "2"))
        store.pull(RootfsImage("example/first:1", first))
        val kept = store.pull(RootfsImage("example/second:1", second))

        store.remove(first)
        assertEquals(listOf(second), store.entries().map { it.digest })
        // config + two layers + manifest of the survivor
        assertEquals(4, blobCount())
        (kept.layers + kept.config).forEach { assertTrue(store.blob(it.digest).isFile) }
    }

    @Test
    fun `eviction takes the least recently used image, never the one kept`() = runBlocking {
        val first = stage("first", layer("f" to "x".repeat(4000)))
        val second = stage("second", layer("s" to "y".repeat(4000)))
        store.pull(RootfsImage("example/first:1", first))
        store.pull(RootfsImage("example/second:1", second))
        store.pull(RootfsImage("example/first:1", first)) // used again: now the newest

        store.evictToFit(ImageCachePolicy(enabled = true, maxCacheBytes = 1), keep = first)
        assertEquals(listOf(first), store.entries().map { it.digest })
    }

    @Test
    fun `garbage from an interrupted pull is collected`() = runBlocking {
        val digest = stage("one", layer("a" to "a"))
        store.pull(RootfsImage("example/one:1", digest))
        val leftover = File(root, "blobs/sha256/0123abcd").apply { writeText("partial") }
        store.evictToFit(ImageCachePolicy(enabled = true), keep = digest)
        assertFalse(leftover.exists())
        assertEquals(1, store.entries().size)
    }
}
