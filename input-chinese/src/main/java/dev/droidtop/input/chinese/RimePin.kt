package dev.droidtop.input.chinese

/**
 * input-chinese/rime-engine.pin (Droidtop/tracker#903 H8b), as the build hands it over in BuildConfig.RIME_PIN: the
 * release librime came from, and the checks a download of its data must pass. The same file fetches librime.so at
 * build time (build-scripts/fetch-rime-engine.sh), so the library and its data cannot come from different releases.
 * Pure.
 */
class RimePin(
    val tag: String,
    val engineSha256: String,
    val dataGzSha256: String,
    /** The SHA-256 of rime-data-luna.files.sha256, the list of every unpacked file's SHA-256 ([RimeManifest]). */
    val dataFilesSha256: String,
    /** The unpacked files' total size in bytes. */
    val dataSize: Long,
) {
    /** Where the data's archive is: the release's rime-data-luna.tar.gz on this repository. */
    val dataUrl: String get() = "$RELEASES/$tag/$DATA_GZ"

    /** Where the data's LGPL-3.0 sources are published, beside it. */
    val sourcesUrl: String get() = "$RELEASES/$tag/$SOURCES_GZ"

    companion object {
        const val RELEASES = "https://github.com/Droidtop/droidtop/releases/download"
        const val DATA_GZ = "rime-data-luna.tar.gz"
        const val SOURCES_GZ = "rime-data-luna-sources.tar.gz"

        private val TAG = Regex("[A-Za-z0-9._-]+")
        private val SHA256 = Regex("[0-9a-f]{64}")

        /**
         * Reads the pin's `key=value` lines; `#` starts a comment line. Every key is required, each once; a missing,
         * repeated or malformed one throws [IllegalArgumentException] naming it, so a broken pin fails on its first use
         * rather than downloading something it cannot check. MozcPin reads mozc-engine.pin the same way.
         */
        @JvmStatic
        fun parse(text: String): RimePin {
            val values = HashMap<String, String>()
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                require(eq > 0) { "rime-engine.pin: not key=value: $line" }
                val key = line.substring(0, eq).trim()
                require(values.put(key, line.substring(eq + 1).trim()) == null) { "rime-engine.pin: $key given twice" }
            }
            fun get(key: String, pattern: Regex): String {
                val value = requireNotNull(values[key]) { "rime-engine.pin: no $key" }
                require(pattern.matches(value)) { "rime-engine.pin: bad $key: $value" }
                return value
            }
            return RimePin(
                tag = get("tag", TAG),
                engineSha256 = get("sha256", SHA256),
                dataGzSha256 = get("data_gz_sha256", SHA256),
                dataFilesSha256 = get("data_files_sha256", SHA256),
                dataSize = get("data_size", Regex("[1-9][0-9]{0,11}")).toLong(),
            )
        }
    }
}
