package dev.droidtop.input.chinese

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Writes the tar entries RimeTar reads (and some it refuses), for the tests. */
class TarWriter {
    private val out = ByteArrayOutputStream()

    fun file(name: String, body: ByteArray, type: Char = '0', link: String = "", ustarPrefix: String = ""): TarWriter {
        header(name, body.size.toLong(), type, link, ustarPrefix)
        out.write(body)
        pad(body.size)
        return this
    }

    fun directory(name: String): TarWriter {
        header(name, 0, '5', "", "")
        return this
    }

    /** GNU's long name: a `././@LongLink` entry holding [name], then the file under a cut name. */
    fun longName(name: String, body: ByteArray): TarWriter {
        file("././@LongLink", (name + "\u0000").toByteArray(), 'L')
        return file(name.take(99), body)
    }

    /** A pax header naming the next file [path], then the file under another name. */
    fun pax(path: String, body: ByteArray): TarWriter {
        val content = " path=$path\n"
        var length = content.length + 1
        while ("$length$content".toByteArray().size != length) length = "$length$content".toByteArray().size
        file("PaxHeaders/x", "$length$content".toByteArray(), 'x')
        return file("placeholder", body)
    }

    /** The archive, ended with its two zero blocks unless [ended] is false. */
    fun bytes(ended: Boolean = true): ByteArray {
        if (ended) out.write(ByteArray(1024))
        return out.toByteArray()
    }

    fun gzip(ended: Boolean = true): ByteArray = gzip(bytes(ended))

    private fun pad(size: Int) {
        val rest = size % 512
        if (rest != 0) out.write(ByteArray(512 - rest))
    }

    private fun header(name: String, size: Long, type: Char, link: String, prefix: String) {
        val h = ByteArray(512)
        fun put(at: Int, text: String) {
            val bytes = text.toByteArray()
            System.arraycopy(bytes, 0, h, at, bytes.size)
        }
        put(0, name)
        put(100, "0000644\u0000")
        put(108, "0000000\u0000")
        put(116, "0000000\u0000")
        put(124, String.format("%011o\u0000", size))
        put(136, "00000000000\u0000")
        put(148, "        ")
        h[156] = type.code.toByte()
        put(157, link)
        put(257, "ustar\u0000")
        put(263, "00")
        put(345, prefix)
        val sum = h.sumOf { it.toInt() and 0xff }
        put(148, String.format("%06o\u0000 ", sum))
        out.write(h)
    }

    companion object {
        fun gzip(bytes: ByteArray): ByteArray =
            ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()
    }
}
