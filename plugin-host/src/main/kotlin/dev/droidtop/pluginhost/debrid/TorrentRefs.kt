package dev.droidtop.pluginhost.debrid

import java.net.URLDecoder
import java.security.MessageDigest

/** The info hash of a magnet or a `.torrent`, for asking a debrid service about it (Droidtop/tracker#638). Pure. */
object TorrentRefs {
    private val HEX = Regex("^[0-9a-fA-F]{40}$")
    private val BASE32 = Regex("^[A-Za-z2-7]{32}$")

    /** A magnet's `xt=urn:btih:` hash, hex or base32, as 40 lower-case hex digits; null when it names none. */
    fun ofMagnet(magnet: String): TorrentRef? {
        if (!magnet.startsWith("magnet:?")) return null
        val hash = magnet.substringAfter('?').split('&')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 && it[0] == "xt" }?.get(1) }
            .map { URLDecoder.decode(it, "UTF-8") }
            .firstOrNull { it.startsWith("urn:btih:", ignoreCase = true) }
            ?.substring("urn:btih:".length)
            ?: return null
        val hex = when {
            HEX.matches(hash) -> hash.lowercase()
            BASE32.matches(hash) -> base32ToHex(hash.uppercase())
            else -> return null
        }
        return TorrentRef(hex, magnet)
    }

    /** A `.torrent`'s info hash (the SHA-1 of its bencoded `info`), a magnet for it and its `private` flag; null when it cannot be read. */
    fun ofTorrent(bytes: ByteArray): TorrentRef? {
        val span = runCatching { infoSpan(bytes) }.getOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(span.first, span.last + 1))
        val hex = digest.joinToString("") { "%02x".format(it) }
        val private = runCatching { privateFlag(bytes, span.first) }.getOrDefault(true) // unreadable: kept off debrid
        return TorrentRef(hex, "magnet:?xt=urn:btih:$hex", private)
    }

    /** Whether the `info` dictionary at [from] says `private` 1 (BEP 27). */
    private fun privateFlag(bytes: ByteArray, from: Int): Boolean {
        if (bytes[from] != 'd'.code.toByte()) return false
        var at = from + 1
        while (bytes[at] != 'e'.code.toByte()) {
            val (key, afterKey) = readString(bytes, at)
            val end = skip(bytes, afterKey)
            if (key == "private") return String(bytes, afterKey, end - afterKey, Charsets.ISO_8859_1) == "i1e"
            at = end
        }
        return false
    }

    /** Where the top-level dictionary's `info` value lies in [bytes], first to last byte. */
    private fun infoSpan(bytes: ByteArray): IntRange? {
        if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) return null
        var at = 1
        while (at < bytes.size && bytes[at] != 'e'.code.toByte()) {
            val (key, afterKey) = readString(bytes, at)
            val end = skip(bytes, afterKey)
            if (key == "info") return afterKey until end
            at = end
        }
        return null
    }

    private fun readString(bytes: ByteArray, from: Int): Pair<String, Int> {
        var at = from
        var length = 0
        while (bytes[at] != ':'.code.toByte()) {
            val digit = bytes[at] - '0'.code.toByte()
            require(digit in 0..9) { "not a string" }
            length = length * 10 + digit
            require(length <= bytes.size) { "too long" }
            at++
        }
        val start = at + 1
        require(start + length <= bytes.size) { "cut short" }
        return String(bytes, start, length, Charsets.ISO_8859_1) to start + length
    }

    /** The index just after the bencoded value at [from]. */
    private fun skip(bytes: ByteArray, from: Int): Int = when (bytes[from].toInt().toChar()) {
        'i' -> bytes.indexOf('e'.code.toByte(), from).also { require(it > from) } + 1
        'l', 'd' -> {
            var at = from + 1
            while (bytes[at] != 'e'.code.toByte()) at = skip(bytes, at)
            at + 1
        }
        in '0'..'9' -> readString(bytes, from).second
        else -> throw IllegalArgumentException("not bencode")
    }

    private fun ByteArray.indexOf(value: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == value) return i
        return -1
    }

    private fun base32ToHex(text: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0L
        var bits = 0
        val out = StringBuilder()
        for (c in text) {
            buffer = (buffer shl 5) or alphabet.indexOf(c).toLong()
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.append("%02x".format(((buffer shr bits) and 0xFF).toInt()))
            }
        }
        return out.toString()
    }
}
