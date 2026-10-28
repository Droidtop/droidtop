package org.pocketworkstation.pckeyboard

/**
 * Writes the keyboard engine's binary dictionary (Droidtop/tracker#339; docs/SPEC.md 6a "Dictionaries"): the format
 * AOSP's 2011 LatinIME reads, as the native reader in src/main/cpp/dictionary.cpp does, version 200 with bigrams.
 * Pure: words and bigrams in, bytes out.
 *
 * The layout, as the reader walks it:
 * - two header bytes, the version (200) and whether there are bigrams (1 or 0);
 * - node groups: a count byte, then each child: its character (one byte, or 0xFF and the 16-bit code), a flags byte
 *   (0x80 a word ends here, 0x40 it has children; with children its low six bits and two more bytes are the 22-bit
 *   address of their group), and for a word end its frequency byte and its bigram list (one 0 byte for none, or 4
 *   bytes each: 0x80 and the target's 22-bit address, then 0x80 if another follows and a 7-bit frequency);
 * - groups in depth-first order, each child's group (with all below it) after its parent's, in sibling order, which
 *   the reader's bigram search (searchForTerminalNode) relies on: a bigram's address is the target word's last
 *   character byte, found by descending into the last sibling whose group starts at or before it.
 *
 * That search steps over every character as one byte, so a dictionary with any character at or above U+00FF is
 * written without bigrams. Addresses are 22 bits, so a dictionary is at most 4 MB ([ADDRESS_LIMIT]; [writeFitting]
 * drops the rarest words until it fits), and a group holds at most 255 characters.
 */
object DictionaryWriter {
    const val VERSION = 200
    const val ADDRESS_LIMIT = 1 shl 22
    const val MAX_BIGRAMS = 15

    /** The engine's longest word (LatinIME's MAX_WORD_LENGTH). */
    const val MAX_WORD_LENGTH = 48

    class TooLarge(val bytes: Int) : IllegalArgumentException("a dictionary of $bytes bytes is past the engine's 4 MB")

    class Unsupported(message: String) : IllegalArgumentException(message)

    private class Node(val ch: Char) {
        val children = sortedMapOf<Char, Node>()
        var freq = -1
        var bigrams: List<Pair<Node, Int>> = emptyList()
        var charPos = 0
        var childAddr = 0
        val terminal: Boolean get() = freq >= 0
    }

    /** The bytes for [words] (word to frequency 0..255) and [bigrams] (word to the words that follow it, each 1..127). */
    fun write(words: Map<String, Int>, bigrams: Map<String, List<Pair<String, Int>>>): ByteArray {
        val root = Node(' ')
        for ((word, freq) in words) {
            if (word.isEmpty() || word.length > MAX_WORD_LENGTH) continue
            var node = root
            for (ch in word) node = node.children.getOrPut(ch) { Node(ch) }
            node.freq = freq.coerceIn(0, 255)
        }
        val withBigrams = words.keys.all { word -> word.all { it.code < 0xFF } }
        if (withBigrams) {
            for ((previous, next) in bigrams) {
                val source = find(root, previous) ?: continue
                source.bigrams = next.mapNotNull { (word, freq) -> find(root, word)?.let { it to freq } }.take(MAX_BIGRAMS)
            }
        }
        val end = layout(root.children.values.toList(), 2, withBigrams)
        if (end > ADDRESS_LIMIT) throw TooLarge(end)
        val out = ByteArray(end)
        out[0] = VERSION.toByte()
        out[1] = (if (withBigrams) 1 else 0).toByte()
        emit(out, root.children.values.toList(), 2, withBigrams)
        return out
    }

    /**
     * [write], dropping the rarest tenth of the words (and the bigrams naming them) until the dictionary fits the
     * engine's 4 MB.
     */
    fun writeFitting(words: Map<String, Int>, bigrams: Map<String, List<Pair<String, Int>>>): ByteArray {
        var kept = words
        while (true) {
            try {
                val keep = kept.keys
                return write(kept, bigrams.filterKeys { it in keep }.mapValues { (_, next) -> next.filter { it.first in keep } })
            } catch (e: TooLarge) {
                if (kept.size < 10) throw e
                kept = kept.entries.sortedByDescending { it.value }.take(kept.size * 9 / 10).associate { it.key to it.value }
            }
        }
    }

    private fun find(root: Node, word: String): Node? {
        var node = root
        for (ch in word) node = node.children[ch] ?: return null
        return node.takeIf { it.terminal }
    }

    private fun entrySize(node: Node, withBigrams: Boolean): Int {
        var size = (if (node.ch.code < 0xFF) 1 else 3) + (if (node.children.isEmpty()) 1 else 3)
        if (node.terminal) size += 1 + if (withBigrams) maxOf(1, 4 * node.bigrams.size) else 1
        return size
    }

    /** Places [group] at [offset] and everything below it after; returns where the next group goes. */
    private fun layout(group: List<Node>, offset: Int, withBigrams: Boolean): Int {
        if (group.size > 255) throw Unsupported("a node with ${group.size} characters after it; the engine holds 255")
        var pos = offset + 1
        for (node in group) {
            node.charPos = pos
            pos += entrySize(node, withBigrams)
        }
        var next = pos
        for (node in group) {
            if (node.children.isNotEmpty()) {
                node.childAddr = next
                next = layout(node.children.values.toList(), next, withBigrams)
            }
        }
        return next
    }

    private fun emit(out: ByteArray, group: List<Node>, offset: Int, withBigrams: Boolean) {
        out[offset] = group.size.toByte()
        for (node in group) {
            var p = node.charPos
            val c = node.ch.code
            if (c < 0xFF) {
                out[p++] = c.toByte()
            } else {
                out[p++] = 0xFF.toByte()
                out[p++] = (c shr 8).toByte()
                out[p++] = c.toByte()
            }
            val flags = if (node.terminal) 0x80 else 0
            if (node.children.isNotEmpty()) {
                val a = node.childAddr
                out[p++] = (flags or 0x40 or ((a shr 16) and 0x3F)).toByte()
                out[p++] = (a shr 8).toByte()
                out[p++] = a.toByte()
            } else {
                out[p++] = flags.toByte()
            }
            if (node.terminal) {
                out[p++] = node.freq.toByte()
                if (withBigrams && node.bigrams.isNotEmpty()) {
                    node.bigrams.forEachIndexed { i, (target, freq) ->
                        val a = target.charPos
                        out[p++] = (0x80 or ((a shr 16) and 0x3F)).toByte()
                        out[p++] = (a shr 8).toByte()
                        out[p++] = a.toByte()
                        out[p++] = ((if (i < node.bigrams.lastIndex) 0x80 else 0) or freq.coerceIn(1, 127)).toByte()
                    }
                } else {
                    out[p++] = 0
                }
            }
        }
        for (node in group) {
            if (node.children.isNotEmpty()) emit(out, node.children.values.toList(), node.childAddr, withBigrams)
        }
    }
}
