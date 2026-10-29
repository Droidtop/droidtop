package dev.droidtop.net

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The one way droidtop replaces a file whole (Droidtop/tracker#766): a reader sees the old file or the new one, never
 * half of either, and a power cut after the call returns leaves the new one. The bytes go to a temp file of a name of
 * its own in the same folder, are synced to disk, and the temp file is moved over [target] in one step; a filesystem
 * that cannot move atomically gets a plain replacing move. On any failure the temp file is deleted, [target] is left as
 * it was, and the call throws.
 *
 * It replaced about forty hand-written copies with three behaviours between them: some threw, some fell back to writing
 * [target] in place (the half-file the temp file was there to prevent), none synced, and each used a fixed `x.tmp`, so
 * two writers met in the same temp file.
 */
object AtomicWrite {
    /** Replaces [target] with [text] in UTF-8. */
    fun text(target: File, text: String) = bytes(target, text.toByteArray(Charsets.UTF_8))

    /** Replaces [target] with [bytes]. */
    fun bytes(target: File, bytes: ByteArray) = stream(target) { it.write(bytes) }

    /** Replaces [target] with what [fill] writes; [fill] must not close the stream. */
    fun stream(target: File, fill: (OutputStream) -> Unit) {
        val folder = target.absoluteFile.parentFile ?: throw IOException("${target.path} has no folder")
        if (!folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) throw IOException("could not make ${folder.path}")
        val temp = File.createTempFile(".${target.name}.", ".tmp", folder)
        try {
            FileOutputStream(temp).use { out ->
                fill(out)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (notAtomic: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Throwable) {
            temp.delete()
            throw failure
        }
    }
}
