package dev.droidtop.input.japanese

/**
 * input-japanese/mozc-engine.pin (Droidtop/tracker#903 H7c), as the build hands it over in BuildConfig.MOZC_PIN: the
 * release Mozc came from, and the dictionary's archive, size and SHA-256s that a download must match. The same file
 * fetches libmozc.so at build time (build-scripts/fetch-mozc-engine.sh), so the library and its dictionary cannot come
 * from different releases. Pure.
 */
class MozcPin(
    val tag: String,
    val engineSha256: String,
    val dataGzSha256: String,
    val dataSize: Long,
    val dataSha256: String,
) {
    /** Where the dictionary's archive is: the release's mozc.data.gz on this repository. */
    val dataUrl: String get() = "$RELEASES/$tag/$DATA_GZ"

    companion object {
        const val RELEASES = "https://github.com/Droidtop/droidtop/releases/download"
        const val DATA_GZ = "mozc.data.gz"

        private val TAG = Regex("[A-Za-z0-9._-]+")
        private val SHA256 = Regex("[0-9a-f]{64}")

        /**
         * Reads the pin's `key=value` lines; `#` starts a comment line. Every key is required, each once; a missing,
         * repeated or malformed one throws [IllegalArgumentException] naming it, so a broken pin fails on its first use
         * rather than downloading something it cannot check.
         */
        @JvmStatic
        fun parse(text: String): MozcPin {
            val values = HashMap<String, String>()
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                require(eq > 0) { "mozc-engine.pin: not key=value: $line" }
                val key = line.substring(0, eq).trim()
                require(values.put(key, line.substring(eq + 1).trim()) == null) { "mozc-engine.pin: $key given twice" }
            }
            fun get(key: String, pattern: Regex): String {
                val value = requireNotNull(values[key]) { "mozc-engine.pin: no $key" }
                require(pattern.matches(value)) { "mozc-engine.pin: bad $key: $value" }
                return value
            }
            val size = get("data_size", Regex("[1-9][0-9]{0,11}")).toLong()
            return MozcPin(
                tag = get("tag", TAG),
                engineSha256 = get("sha256", SHA256),
                dataGzSha256 = get("data_gz_sha256", SHA256),
                dataSize = size,
                dataSha256 = get("data_sha256", SHA256),
            )
        }
    }
}
