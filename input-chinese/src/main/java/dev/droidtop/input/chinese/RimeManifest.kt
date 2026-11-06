package dev.droidtop.input.chinese

import java.security.MessageDigest

/**
 * rime-data-luna.files.sha256 (Droidtop/tracker#903 H8b): every file the data's archive unpacks to and its SHA-256, in
 * sha256sum's format ("<hash>  shared/build/luna_pinyin.table.bin"), as rime-engine.yml publishes it beside the archive.
 * The module carries a copy (input-chinese/rime-data-luna.files.sha256, handed over as BuildConfig.RIME_DATA_FILES),
 * and the pin names that copy's own SHA-256, so neither can change without the other. Pure.
 */
class RimeManifest private constructor(
    /** Each file's path under the unpacked root, "shared/...", to its SHA-256. */
    val files: Map<String, String>,
) {
    companion object {
        private val LINE = Regex("([0-9a-f]{64}) [ *](.+)")

        /**
         * Parses [text], checking it is the list [pin] names. Every path is relative, under `shared/`, without `.` or
         * `..` parts, and listed once. Throws [IllegalArgumentException] naming what is wrong.
         */
        @JvmStatic
        fun parse(text: String, pin: RimePin): RimeManifest {
            require(sha256(text.toByteArray(Charsets.UTF_8)) == pin.dataFilesSha256) {
                "rime-data-luna.files.sha256 is not the list rime-engine.pin names"
            }
            return parse(text)
        }

        /** Parses [text] without checking it against a pin. */
        @JvmStatic
        fun parse(text: String): RimeManifest {
            val files = LinkedHashMap<String, String>()
            for (raw in text.lines()) {
                if (raw.isBlank()) continue
                val match = requireNotNull(LINE.matchEntire(raw.trimEnd())) { "rime-data-luna.files.sha256: not a sha256sum line: $raw" }
                val path = match.groupValues[2]
                require(safe(path)) { "rime-data-luna.files.sha256: bad path: $path" }
                require(files.put(path, match.groupValues[1]) == null) { "rime-data-luna.files.sha256: $path listed twice" }
            }
            require(files.isNotEmpty()) { "rime-data-luna.files.sha256: no files" }
            return RimeManifest(files)
        }

        /** Whether [path] is a plain relative path under `shared/`. */
        @JvmStatic
        fun safe(path: String): Boolean {
            if (!path.startsWith("shared/") || path.startsWith("/") || path.contains('\\') || path.contains('\u0000')) return false
            val parts = path.split('/')
            return parts.none { it.isEmpty() || it == "." || it == ".." }
        }

        @JvmStatic
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
