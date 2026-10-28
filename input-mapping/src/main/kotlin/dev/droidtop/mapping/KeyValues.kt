package dev.droidtop.mapping

/**
 * One entry of a Valve KeyValues ("VDF") text file: a key with either a string [value] or a block of [children], and
 * the platform [condition] (`[$WIN32]`) some files put after it (Droidtop/tracker#618: Steam's controller layouts are
 * this format, and droidtop's layouts are stored in it). Keys repeat (a layout has many `group` blocks) and their order
 * is kept, so a file read and written back says the same thing. Keys are matched without regard to case, as Steam
 * does.
 */
data class KvNode(
    val key: String,
    val value: String? = null,
    val children: List<KvNode> = emptyList(),
    val condition: String? = null,
) {
    val isBlock: Boolean get() = value == null

    /** Every child named [key], in order. */
    fun all(key: String): List<KvNode> = children.filter { it.key.equals(key, ignoreCase = true) }

    /** The first child named [key]. */
    fun first(key: String): KvNode? = children.firstOrNull { it.key.equals(key, ignoreCase = true) }

    /** The string value of the first child named [key], or null when there is none or it is a block. */
    fun string(key: String): String? = first(key)?.value

    /** This block's string children as a map (the first of a repeated key wins), for a `settings` block. */
    fun strings(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (child in children) child.value?.let { out.putIfAbsent(child.key, it) }
        return out
    }

    companion object {
        fun block(key: String, vararg children: KvNode): KvNode = KvNode(key, null, children.toList())
        fun leaf(key: String, value: String): KvNode = KvNode(key, value)
    }
}

class KeyValuesException(val line: Int, message: String) : IllegalArgumentException("line $line: $message")

/**
 * Reads and writes KeyValues text. Pure.
 *
 * Read: quoted or bare tokens, `{` and `}`, `//` comments (dropped), the escapes `\"`, `\\`, `\n` and `\t` in quoted
 * tokens, and a `[...]` condition after a value or a block's key. Write: every token quoted, tab indents, the same
 * escapes, so [parse] of [write] gives back the same nodes.
 */
object KeyValues {
    fun parse(text: String): List<KvNode> {
        return readEntries(Tokenizer(text), topLevel = true)
    }

    fun write(nodes: List<KvNode>): String {
        val out = StringBuilder()
        for (node in nodes) write(out, node, 0)
        return out.toString()
    }

    private fun readEntries(tokens: Tokenizer, topLevel: Boolean): List<KvNode> {
        val entries = ArrayList<KvNode>()
        while (true) {
            val token = tokens.next()
            when {
                token == null -> {
                    if (!topLevel) throw KeyValuesException(tokens.line, "a block is not closed")
                    return entries
                }
                token.kind == Kind.CLOSE -> {
                    if (topLevel) throw KeyValuesException(tokens.line, "a } with no block to close")
                    return entries
                }
                token.kind == Kind.OPEN -> throw KeyValuesException(tokens.line, "a block with no key")
                token.kind == Kind.CONDITION -> throw KeyValuesException(tokens.line, "a condition with no key")
                else -> entries += readEntry(tokens, token.text)
            }
        }
    }

    private fun readEntry(tokens: Tokenizer, key: String): KvNode {
        var next = tokens.next() ?: throw KeyValuesException(tokens.line, "\"$key\" has no value")
        var condition: String? = null
        if (next.kind == Kind.CONDITION) {
            condition = next.text
            next = tokens.next() ?: throw KeyValuesException(tokens.line, "\"$key\" has no value")
        }
        return when (next.kind) {
            Kind.OPEN -> KvNode(key, null, readEntries(tokens, topLevel = false), condition)
            Kind.STRING -> {
                val after = tokens.peek()
                if (after?.kind == Kind.CONDITION) {
                    tokens.next()
                    KvNode(key, next.text, emptyList(), after.text)
                } else {
                    KvNode(key, next.text, emptyList(), condition)
                }
            }
            else -> throw KeyValuesException(tokens.line, "\"$key\" is followed by ${next.text}")
        }
    }

    private fun write(out: StringBuilder, node: KvNode, depth: Int) {
        indent(out, depth)
        quote(out, node.key)
        if (node.value != null) {
            out.append("\t\t")
            quote(out, node.value)
            node.condition?.let { out.append(' ').append(it) }
            out.append('\n')
        } else {
            node.condition?.let { out.append(' ').append(it) }
            out.append('\n')
            indent(out, depth)
            out.append("{\n")
            for (child in node.children) write(out, child, depth + 1)
            indent(out, depth)
            out.append("}\n")
        }
    }

    private fun indent(out: StringBuilder, depth: Int) {
        repeat(depth) { out.append('\t') }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\t' -> out.append("\\t")
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private enum class Kind { STRING, OPEN, CLOSE, CONDITION }

    private class Token(val kind: Kind, val text: String)

    private class Tokenizer(private val text: String) {
        private var pos = 0
        var line = 1
            private set
        private var peeked: Token? = null

        fun peek(): Token? {
            if (peeked == null) peeked = read()
            return peeked
        }

        fun next(): Token? {
            val p = peeked
            if (p != null) {
                peeked = null
                return p
            }
            return read()
        }

        private fun read(): Token? {
            skipSpaceAndComments()
            if (pos >= text.length) return null
            return when (text[pos]) {
                '{' -> { pos++; Token(Kind.OPEN, "{") }
                '}' -> { pos++; Token(Kind.CLOSE, "}") }
                '"' -> Token(Kind.STRING, quoted())
                '[' -> Token(Kind.CONDITION, condition())
                else -> Token(Kind.STRING, bare())
            }
        }

        private fun skipSpaceAndComments() {
            while (pos < text.length) {
                val c = text[pos]
                when {
                    c == '\n' -> { line++; pos++ }
                    c.isWhitespace() || c == '﻿' -> pos++
                    c == '/' && pos + 1 < text.length && text[pos + 1] == '/' -> {
                        while (pos < text.length && text[pos] != '\n') pos++
                    }
                    else -> return
                }
            }
        }

        private fun quoted(): String {
            val startLine = line
            pos++
            val out = StringBuilder()
            while (true) {
                if (pos >= text.length) throw KeyValuesException(startLine, "a quoted string is not closed")
                val c = text[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= text.length) throw KeyValuesException(startLine, "a quoted string is not closed")
                        when (val e = text[pos++]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            // Anything else is kept as written: a Windows path's backslash is not an escape.
                            else -> out.append('\\').append(e)
                        }
                    }
                    else -> {
                        if (c == '\n') line++
                        out.append(c)
                    }
                }
            }
        }

        private fun condition(): String {
            val start = pos
            while (pos < text.length && text[pos] != ']' && text[pos] != '\n') pos++
            if (pos >= text.length || text[pos] != ']') throw KeyValuesException(line, "a condition is not closed")
            pos++
            return text.substring(start, pos)
        }

        private fun bare(): String {
            val start = pos
            while (pos < text.length) {
                val c = text[pos]
                if (c.isWhitespace() || c == '{' || c == '}' || c == '"') break
                pos++
            }
            return text.substring(start, pos)
        }
    }
}
