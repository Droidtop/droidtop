package dev.droidtop.library

/**
 * One thing pinned to Desktop's taskbar (docs/SPEC.md 2b): a Linux app of the container or a library entry,
 * named by [key] ([TaskbarPins.linuxKey], [TaskbarPins.entryKey]), shown under [title], with the picture
 * [art] when the entry has one. The pin carries what the bar draws, so the bar needs neither the library
 * nor a running desktop to show it.
 */
data class TaskbarPin(val key: String, val title: String, val art: String? = null)

/** The pins as the bar keeps them: their order is the order they were pinned in. Pure, so the rules are tested. */
object TaskbarPins {
    private const val LINUX_PREFIX = "linux:"
    private const val ENTRY_PREFIX = "entry:"

    fun linuxKey(appId: String): String = LINUX_PREFIX + appId

    fun entryKey(entryId: String): String = ENTRY_PREFIX + entryId

    fun isLinux(key: String): Boolean = key.startsWith(LINUX_PREFIX)

    /** The library entry id behind an entry pin's key; null for a Linux app's. */
    fun entryIdOf(key: String): String? = if (key.startsWith(ENTRY_PREFIX)) key.removePrefix(ENTRY_PREFIX) else null

    /** [pin] added at the end, or taken out when its key is already pinned. */
    fun toggled(pins: List<TaskbarPin>, pin: TaskbarPin): List<TaskbarPin> =
        if (pins.any { it.key == pin.key }) pins.filter { it.key != pin.key } else pins + pin

    fun isPinned(pins: List<TaskbarPin>, key: String): Boolean = pins.any { it.key == key }

    /** One line per pin, `key TAB title TAB art`; a tab or a line break inside a field becomes a space. */
    fun encode(pins: List<TaskbarPin>): String =
        pins.joinToString("\n") { pin -> listOf(pin.key, pin.title, pin.art.orEmpty()).joinToString("\t") { clean(it) } }

    /** [encode]'s lines back; a line with no key or no title is dropped, a repeated key keeps its first place. */
    fun decode(raw: String?): List<TaskbarPin> {
        if (raw.isNullOrEmpty()) return emptyList()
        val out = ArrayList<TaskbarPin>()
        for (line in raw.split('\n')) {
            val fields = line.split('\t')
            val key = fields.getOrNull(0).orEmpty()
            val title = fields.getOrNull(1).orEmpty()
            if (key.isEmpty() || title.isEmpty() || out.any { it.key == key }) continue
            out.add(TaskbarPin(key, title, fields.getOrNull(2)?.takeIf { it.isNotEmpty() }))
        }
        return out
    }

    private fun clean(field: String): String = field.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
