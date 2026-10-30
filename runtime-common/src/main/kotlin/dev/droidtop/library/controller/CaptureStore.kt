package dev.droidtop.library.controller

/**
 * Per-pad captures as one small text value, a line per pad:
 * `descriptor TAB confirmKeyCodeIsB TAB signature TAB stale`. The descriptor
 * is Android's own stable id for a physical device and holds no tab or
 * newline. Pure, so the format is unit-tested.
 */
object CaptureStore {
    fun parse(text: String?): Map<String, PadCapture> {
        if (text.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, PadCapture>()
        for (line in text.lineSequence()) {
            val parts = line.split('\t')
            if (parts.size != 4) continue
            out[parts[0]] = PadCapture(parts[1] == "1", parts[2], parts[3] == "1")
        }
        return out
    }

    fun format(captures: Map<String, PadCapture>): String =
        captures.entries.joinToString("\n") { (descriptor, c) ->
            listOf(descriptor, if (c.confirmKeyCodeIsB) "1" else "0", c.signature, if (c.stale) "1" else "0").joinToString("\t")
        }
}
