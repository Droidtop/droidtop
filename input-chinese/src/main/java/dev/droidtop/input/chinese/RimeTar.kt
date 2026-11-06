package dev.droidtop.input.chinese

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Just enough of tar to unpack rime-data-luna.tar.gz (Droidtop/tracker#903 H8b), which build-data-luna.sh makes with
 * GNU tar: regular files and directories, GNU long names and pax `path` records. Links, devices and anything else are
 * refused rather than skipped, so the data is exactly the files its list names. Every header's checksum is checked.
 * Pure (java.io); the keyboard module's commons-compress is not reached for, as :input-chinese depends on the keyboard
 * only for the seam.
 */
object RimeTar {
    private const val BLOCK = 512

    /** The archive is not one this reads: a bad header, a link, or it ends early. */
    class Malformed(message: String) : IOException(message)

    /** A regular file in the archive: [path] as stored, [size] bytes, read from [body] until it ends. */
    fun interface Visitor {
        @Throws(IOException::class)
        fun file(path: String, size: Long, body: InputStream)
    }

    /** Reads every entry of [input], handing each regular file to [visitor]. */
    @JvmStatic
    @Throws(IOException::class)
    fun read(input: InputStream, visitor: Visitor) {
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var paxPath: String? = null
        while (true) {
            if (!fill(input, header)) throw Malformed("the archive ends without its end blocks")
            if (header.all { it == 0.toByte() }) return
            if (checksum(header) != octal(header, 148, 8)) throw Malformed("a header's checksum does not match")
            val size = size(header)
            val type = header[156].toInt().toChar()
            when (type) {
                'L' -> longName = cString(readAll(input, size))
                'x' -> paxPath = paxRecords(readAll(input, size))["path"] ?: paxPath
                'g' -> skip(input, padded(size))
                '0', '\u0000', '7', '5' -> {
                    val path = paxPath ?: longName ?: name(header)
                    longName = null
                    paxPath = null
                    if (type == '5') {
                        skip(input, padded(size))
                    } else {
                        val body = Bounded(input, size)
                        visitor.file(path, size, body)
                        skip(body, body.left)
                        skip(input, padded(size) - size)
                    }
                }
                else -> throw Malformed("entry type '$type' is not a file or a directory")
            }
        }
    }

    private fun padded(size: Long): Long = (size + BLOCK - 1) / BLOCK * BLOCK

    private fun name(header: ByteArray): String {
        val name = cString(header.copyOfRange(0, 100))
        // ustar (not GNU's "ustar  ") keeps a prefix for long paths.
        val ustar = String(header, 257, 6, Charsets.ISO_8859_1) == "ustar\u0000"
        val prefix = if (ustar) cString(header.copyOfRange(345, 500)) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun size(header: ByteArray): Long {
        if (header[124].toInt() and 0x80 != 0) {
            // GNU base-256, for sizes past 8 GB: never in this data.
            throw Malformed("an entry is too large")
        }
        return octal(header, 124, 12)
    }

    private fun octal(bytes: ByteArray, at: Int, length: Int): Long {
        var value = 0L
        var seen = false
        for (i in at until at + length) {
            val c = bytes[i].toInt().toChar()
            if (c == ' ' || c == '\u0000') {
                if (seen) break else continue
            }
            if (c !in '0'..'7') throw Malformed("a header field is not octal")
            seen = true
            value = value * 8 + (c - '0')
        }
        return value
    }

    private fun checksum(header: ByteArray): Long {
        var sum = 0L
        for (i in 0 until BLOCK) sum += if (i in 148 until 156) ' '.code else header[i].toInt() and 0xff
        return sum
    }

    private fun cString(bytes: ByteArray): String {
        val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    /** pax extended header records, "<length> <key>=<value>\n" each. */
    private fun paxRecords(bytes: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var at = 0
        while (at < bytes.size) {
            val space = (at until bytes.size).firstOrNull { bytes[it] == ' '.code.toByte() } ?: break
            val length = String(bytes, at, space - at, Charsets.US_ASCII).toIntOrNull() ?: throw Malformed("a pax record has no length")
            if (length <= 0 || at + length > bytes.size) throw Malformed("a pax record runs past its header")
            val record = String(bytes, space + 1, at + length - space - 2, Charsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            at += length
        }
        return out
    }

    private fun readAll(input: InputStream, size: Long): ByteArray {
        if (size > 1 shl 20) throw Malformed("an extended header is too large")
        val bytes = ByteArray(size.toInt())
        if (!fill(input, bytes)) throw Malformed("the archive ends inside a header")
        skip(input, padded(size) - size)
        return bytes
    }

    /** Fills [buffer] from [input]: false when the input ended before any byte, an error when it ends partway. */
    private fun fill(input: InputStream, buffer: ByteArray): Boolean {
        var at = 0
        while (at < buffer.size) {
            val n = input.read(buffer, at, buffer.size - at)
            if (n < 0) {
                if (at == 0) return false
                throw EOFException("the archive ends inside a block")
            }
            at += n
        }
        return true
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        val scratch = ByteArray(8192)
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n < 0) throw EOFException("the archive ends inside an entry")
            left -= n
        }
    }

    /** At most [size] bytes of [inner], and an error if it ends before them. */
    private class Bounded(private val inner: InputStream, size: Long) : InputStream() {
        var left = size
            private set

        override fun read(): Int {
            if (left <= 0) return -1
            val b = inner.read()
            if (b < 0) throw EOFException("the archive ends inside a file")
            left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw EOFException("the archive ends inside a file")
            left -= n
            return n
        }

        override fun close() {}
    }
}
