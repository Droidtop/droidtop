package dev.droidtop.library.settings

import androidx.annotation.VisibleForTesting
import java.io.File
import java.util.Locale

/**
 * Words for a plain-JVM unit test (Droidtop/tracker#177): a row builder that now reads `words(R.string.x)` is tested
 * without an Application, so [install] maps each module's R.string ids to their names by reflection and reads the
 * English from that module's own `src/main/res/values/strings.xml`, found from the repository root. A test sees the
 * same words a person reads; nothing here runs in the app.
 */
@VisibleForTesting
object WordsForTests {
    /** Each module whose strings a catalog reads: its R.string class name and its folder. */
    private val MODULES = listOf(
        "dev.droidtop.runtime.R\$string" to "runtime-common",
        "dev.droidtop.shell.gamepad.R\$string" to "shell-gamepad",
        "dev.droidtop.app.R\$string" to "app",
        "dev.droidtop.shell.desktop.R\$string" to "shell-desktop",
        "dev.droidtop.ui.shared.R\$string" to "ui-shared",
    )

    fun install() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("WordsForTests: no repository root above ${File("").absolutePath}")
        val byId = HashMap<Int, String>()
        for ((className, module) in MODULES) {
            val ids = runCatching { Class.forName(className) }.getOrNull() ?: continue
            val texts = read(File(root, "$module/src/main/res/values/strings.xml"))
            for (field in ids.fields) {
                val text = texts[field.name] ?: continue
                runCatching { byId[field.getInt(null)] = text }
            }
        }
        Words.installForTests { id, args ->
            val text = byId[id] ?: error("WordsForTests: string $id is in no module's strings.xml")
            String.format(Locale.ROOT, text, *args)
        }
    }

    /** A strings.xml's entries, unescaped as aapt does for these files (\' \" \\n and the XML entities). Pure. */
    internal fun read(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(file.readText())
            .associate { match -> match.groupValues[1] to unescape(match.groupValues[2]) }
    }

    internal fun unescape(raw: String): String = raw
        .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
