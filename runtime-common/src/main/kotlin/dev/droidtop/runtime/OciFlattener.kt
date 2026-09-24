package dev.droidtop.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Receives a flattened rootfs one entry at a time: see [OciFlattener]. */
fun interface RootfsEntrySink {
    /** [entry] is clean (see [OciFlattener]); [data] is its content, empty for anything but a regular file. */
    fun accept(entry: TarArchiveEntry, data: InputStream)
}

/**
 * A sink that writes what it is given as one tar stream, for a `tar`
 * binary to extract: GNU long names and base-256 numbers (toybox reads
 * both; it ignores a pax `linkpath`, so pax headers are never written), no
 * user or group names (so an extractor sets the numeric ids and never looks
 * a name up in Android's own user table).
 */
class TarStreamSink(output: OutputStream) : RootfsEntrySink, Closeable {
    private val tar = TarArchiveOutputStream(output, 1 shl 16).apply {
        setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
        setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
        setAddPaxHeadersForNonAsciiNames(false)
    }

    override fun accept(entry: TarArchiveEntry, data: InputStream) {
        tar.putArchiveEntry(entry)
        if (entry.isFile && !entry.isLink) data.copyTo(tar, 1 shl 16)
        tar.closeArchiveEntry()
    }

    override fun close() {
        tar.finish()
        tar.close()
    }
}

/**
 * Turns an OCI image's layers into one rootfs, entry by entry, for a
 * [RootfsEntrySink]: the job `crane export` used to do, done here so that
 * nothing the image says reaches the filesystem unchecked, whoever writes
 * it (the app for proot, root's `tar` for droidspaces).
 *
 * Layers are read top (newest) first, which is how overlay semantics are
 * decided in one pass: the first time a path is seen is its final
 * version, and anything a higher layer hid is never emitted. A layer's
 * whiteouts (`.wh.<name>` hides `<name>`; `.wh..wh..opq` hides everything
 * a lower layer put in that directory) apply to the layers below it, not
 * its own entries.
 *
 * What is emitted is clean by construction:
 *  - every name is relative to the image root with no `..` ([TarPaths]); a
 *    name that would climb out is skipped and reported.
 *  - nothing is ever beneath a symlink, a file or any other non-directory:
 *    once a path is known to be one, every entry under it is skipped, and
 *    once anything has been emitted under a path, that path is a directory
 *    for good, so a later (lower) symlink of the same name is skipped
 *    instead. An extractor therefore never resolves a path through a link
 *    the image made, the defect class of a symlink pointing out of the
 *    rootfs into the host (docs/SPEC.md §3, finding 7 of
 *    docs/security/2026-09-24-droidtop-intents-updater.md).
 *  - each path is emitted at most once, and the destination starts empty,
 *    so no entry ever replaces something already there.
 *  - hard links come last, and only to a regular file the same layer
 *    supplied and that survived into the final tree (a layer's hard links
 *    can only name its own files); anything else is skipped and reported.
 *  - headers carry numeric ids and permission bits only: no user or group
 *    names, no pax extensions, no extended attributes.
 *
 * Each layer's blob is checked against its digest as it is read, after
 * crane checked it on download; a mismatch (storage corrupted since) fails
 * the flatten, and the caller's rootfs is never marked complete.
 */
object OciFlattener {
    /** How a flatten went. [skipped] names each entry not emitted for a reason other than being shadowed, up to [MAX_REPORTED]. */
    data class Result(val written: Int, val skipped: List<String>, val skippedCount: Int)

    const val MAX_REPORTED = 200

    fun flatten(store: OciImageStore, image: OciImageStore.Image, sink: RootfsEntrySink): Result =
        flatten(image.layers.map { layer -> { openLayer(store.blob(layer.digest), layer) } }, sink)

    /** Flattens [layers], bottom (oldest) first as in a manifest, each opened as an uncompressed tar stream by its function. */
    internal fun flatten(layers: List<() -> LayerStream>, sink: RootfsEntrySink): Result {
        val state = State()
        for (layerIndex in layers.indices.reversed()) {
            layers[layerIndex]().use { layer ->
                val tar = TarArchiveInputStream(layer.tar)
                while (true) {
                    val entry = tar.nextEntry ?: break
                    state.visit(entry, layerIndex, tar, sink)
                }
                state.endLayer()
                layer.finish()
            }
        }
        state.emitHardLinks(sink)
        return Result(state.written, state.skipped, state.skippedCount)
    }

    /** An uncompressed layer [tar]; [finish] checks the whole blob was what its digest says. */
    internal class LayerStream(val tar: InputStream, private val verify: () -> Unit, private val closeable: Closeable) : Closeable {
        fun finish() = verify()
        override fun close() = closeable.close()

        companion object {
            /** A stream with nothing to verify, for tests. */
            fun plain(tar: InputStream) = LayerStream(tar, {}, tar)
        }
    }

    private fun openLayer(file: File, layer: OciImageStore.Descriptor): LayerStream {
        val (algorithm, hex) = OciImageStore.splitDigest(layer.digest) ?: error("not a blob digest: ${layer.digest}")
        check(algorithm == "sha256") { "layer ${layer.digest} uses $algorithm, which is not supported" }
        val raw = file.inputStream()
        val hashing = DigestInputStream(BufferedInputStream(raw, 1 shl 16), MessageDigest.getInstance("SHA-256"))
        val sniff = BufferedInputStream(hashing, 1 shl 16)
        sniff.mark(4)
        val magic = ByteArray(4).also { sniff.read(it) }
        sniff.reset()
        val tar: InputStream = when {
            magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() -> GZIPInputStream(sniff, 1 shl 16)
            magic.contentEquals(byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte())) -> {
                raw.close()
                error("layer ${layer.digest} is zstd-compressed (${layer.mediaType}), which droidtop cannot unpack yet")
            }
            else -> sniff
        }
        return LayerStream(
            tar = tar,
            verify = {
                // The tar ends before the blob does (end-of-archive blocks, gzip's trailer): hash all of it.
                drain(tar)
                drain(sniff)
                val actual = hashing.messageDigest.digest().joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
                if (actual != hex) {
                    file.delete()
                    error("layer ${layer.digest} on disk has digest sha256:$actual; deleted it, pull again")
                }
            },
            closeable = raw,
        )
    }

    private class State {
        /**
         * Final kind of every path decided so far: [IMPLICIT_DIR] (something
         * was emitted beneath it), [DIR] (its header was emitted), or, for a
         * non-directory, the index of the layer that supplied it.
         */
        val kinds = HashMap<String, Int>()

        /** Which layer supplied each regular file, for hard links. */
        val fileLayer = HashMap<String, Int>()

        /** Paths hidden from the layers below by a whiteout above. */
        val hidden = HashSet<String>()

        /** Directories whose lower-layer contents a layer above made opaque. */
        val opaque = HashSet<String>()
        val pendingHidden = mutableListOf<String>()
        val pendingOpaque = mutableListOf<String>()
        val hardLinks = mutableListOf<HardLink>()

        var written = 0
        val skipped = mutableListOf<String>()
        var skippedCount = 0

        fun skip(reason: String) {
            skippedCount++
            if (skipped.size < MAX_REPORTED) skipped += reason
        }

        fun visit(entry: TarArchiveEntry, layer: Int, data: InputStream, sink: RootfsEntrySink) {
            val path = TarPaths.relative(entry.name)
            if (path == null) return skip("${entry.name}: outside the image root")
            // The root itself: the destination directory, made by whoever extracts.
            if (path.isEmpty()) return

            val base = path.substringAfterLast('/')
            if (base.startsWith(WHITEOUT)) {
                val parent = TarPaths.parent(path)
                when {
                    base == OPAQUE -> pendingOpaque += parent
                    base.startsWith(WHITEOUT + WHITEOUT) -> Unit // aufs bookkeeping (.wh..wh.plnk and the like)
                    else -> pendingHidden += TarPaths.child(parent, base.removePrefix(WHITEOUT))
                }
                return
            }
            if (isHidden(path)) return

            // An ancestor that is a non-directory: one a higher layer put there
            // shadows this entry; one from this same layer is a symlink or file
            // the entry would be written through, which is reported.
            val ancestors = TarPaths.ancestors(path)
            for (ancestor in ancestors) {
                val kind = kinds[ancestor] ?: continue
                if (kind >= 0) {
                    if (kind == layer) skip("${entry.name}: beneath a symlink or a non-directory")
                    return
                }
            }

            val existing = kinds[path]
            when {
                entry.isDirectory -> {
                    if (existing != null && existing != IMPLICIT_DIR) return
                    sink.accept(clean(path, entry, TarConstants.LF_DIR), NO_DATA)
                    written++
                    kinds[path] = DIR
                }
                existing != null -> return // a higher layer's version, or a directory by now
                entry.isLink -> {
                    val target = TarPaths.relative(entry.linkName)
                    if (target.isNullOrEmpty()) return skip("${entry.name}: hard link to ${entry.linkName}, outside the image root")
                    kinds[path] = layer
                    hardLinks += HardLink(path, target, layer, entry)
                }
                entry.isSymbolicLink -> {
                    sink.accept(clean(path, entry, TarConstants.LF_SYMLINK, linkName = entry.linkName), NO_DATA)
                    written++
                    kinds[path] = layer
                }
                entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> {
                    val type = when {
                        entry.isCharacterDevice -> TarConstants.LF_CHR
                        entry.isBlockDevice -> TarConstants.LF_BLK
                        else -> TarConstants.LF_FIFO
                    }
                    sink.accept(
                        clean(path, entry, type).apply { devMajor = entry.devMajor; devMinor = entry.devMinor },
                        NO_DATA,
                    )
                    written++
                    kinds[path] = layer
                }
                entry.isFile -> {
                    val size = if (entry.isSparse) entry.realSize else entry.size
                    sink.accept(clean(path, entry, TarConstants.LF_NORMAL, size = size), data)
                    written++
                    kinds[path] = layer
                    fileLayer[path] = layer
                }
                else -> return skip("${entry.name}: unsupported entry type")
            }
            for (ancestor in ancestors) kinds.putIfAbsent(ancestor, IMPLICIT_DIR)
        }

        fun endLayer() {
            hidden += pendingHidden
            opaque += pendingOpaque
            pendingHidden.clear()
            pendingOpaque.clear()
        }

        fun emitHardLinks(sink: RootfsEntrySink) {
            for (link in hardLinks) {
                if (fileLayer[link.target] != link.layer) {
                    skip("${link.entry.name}: hard link to ${link.entry.linkName}, which is not a file of its own layer in the final tree")
                    continue
                }
                sink.accept(clean(link.path, link.entry, TarConstants.LF_LINK, linkName = link.target), NO_DATA)
                written++
            }
        }

        private fun isHidden(path: String): Boolean {
            if (path in hidden || "" in opaque) return true
            for (ancestor in TarPaths.ancestors(path)) {
                if (ancestor in hidden || ancestor in opaque) return true
            }
            return false
        }
    }

    private class HardLink(val path: String, val target: String, val layer: Int, val entry: TarArchiveEntry)

    private const val IMPLICIT_DIR = -2
    private const val DIR = -1
    /** The content of every entry but a regular file. (InputStream.nullInputStream is API 33; minSdk is 26.) */
    private val NO_DATA: InputStream get() = ByteArrayInputStream(ByteArray(0))

    private fun drain(input: InputStream) {
        val buffer = ByteArray(1 shl 16)
        while (input.read(buffer) >= 0) Unit
    }

    private const val WHITEOUT = ".wh."
    private const val OPAQUE = ".wh..wh..opq"

    /** A fresh header for [path]: the source's permission bits, numeric ids and mtime, nothing else carried over. */
    private fun clean(path: String, source: TarArchiveEntry, type: Byte, linkName: String? = null, size: Long = 0): TarArchiveEntry =
        TarArchiveEntry(if (type == TarConstants.LF_DIR) "$path/" else path, type).apply {
            mode = source.mode and 0b111_111_111_111
            userName = ""
            groupName = ""
            setUserId(source.longUserId)
            setGroupId(source.longGroupId)
            modTime = source.modTime
            if (linkName != null) this.linkName = linkName
            this.size = size
        }
}
