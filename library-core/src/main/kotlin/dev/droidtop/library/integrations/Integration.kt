package dev.droidtop.library.integrations

import java.io.File
import org.json.JSONObject

/**
 * A user-declared hook into another installed Android app (docs/SPEC.md
 * §12). droidtop drives the other app through its own real Activities and
 * Intents; the integration is a declarative description of which one to
 * call and what to hand it, with no new code running.
 *
 * This is the **JSON** half of the two integration types. The PLUGIN half
 * (an APK or Python module, for things a bare Intent call genuinely
 * cannot express) is deliberately not built here -- nothing in the first
 * real use case needs it, and a sandbox/trust model for running foreign
 * code is a much larger design than a declarative Intent description.
 *
 * The template is an `am start`-style argument string, exactly the format
 * `players-database.json` already uses for emulators, and it is parsed by
 * the very same [dev.droidtop.library.consoles.AmStartCommandToIntentConverter].
 * That reuse is the point: droidtop already had a real, tested, real-world
 * mechanism for "describe how to launch another app in data", and an
 * integration is the same problem wearing a different hat. No second
 * mechanism, no new syntax for a user to learn.
 *
 * Integrations are loaded from files the user owns
 * ([IntegrationStore.userDir]) rather than being compiled in or shipped in
 * a database. That is deliberate: an integration names a specific
 * third-party app the user chose to install, and some of those are
 * nobody's business but theirs.
 */
data class Integration(
    /** Stable id, also the filename stem. */
    val id: String,
    /** What the user sees on the button/row. */
    val label: String,
    /** The app this drives. Integrations whose package isn't installed are hidden, never offered and broken. */
    val packageName: String,
    val capability: IntegrationCapability,
    /** `am start`-style arguments, with placeholders — see [IntegrationPlaceholders]. */
    val argumentsTemplate: String,
    /** Shown under the label; the author's own note about what this does. */
    val description: String? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): Integration? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val pkg = json.optString("package").takeIf { it.isNotBlank() } ?: return null
            val template = json.optString("argumentsTemplate").takeIf { it.isNotBlank() } ?: return null
            val capability = IntegrationCapability.fromId(json.optString("capability")) ?: return null
            return Integration(
                id = id,
                label = json.optString("label").ifBlank { id },
                packageName = pkg,
                capability = capability,
                argumentsTemplate = template,
                description = json.optString("description").takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * What a given integration is *for*. This is what decides where droidtop
 * offers it, and it exists as a closed set rather than a free string
 * because the trust shape genuinely differs per capability: "open this
 * video in my preferred player" hands over one file, while
 * "acquire content into my library" hands over a writable games folder
 * and expects something to appear in it (SPEC §12's own point).
 */
enum class IntegrationCapability(val id: String, val display: String) {
    /**
     * Fetch content into one of droidtop's own configured library
     * folders -- the first real use case: a per-system "get games"
     * action that hands a ROM downloader the system and the exact
     * destination folder, so the result lands where droidtop already
     * scans.
     */
    ACQUIRE_CONTENT("acquire_content", "Get content"),

    /** Open a file droidtop would otherwise handle itself (video, document) in a preferred app. */
    OPEN_WITH("open_with", "Open with"),
    ;

    companion object {
        fun fromId(id: String): IntegrationCapability? = entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

/**
 * The substitutions an integration template may use, beyond the
 * `{file.path}`/`{file.uri}` pair the emulator-player templates already
 * support.
 *
 * Deliberately small and concrete. Every one of these is something
 * droidtop genuinely knows at the moment it offers the action, so a
 * template can never reference a value that has to be guessed at.
 */
object IntegrationPlaceholders {
    /** The ES-DE system id being acted on, e.g. `psx`. */
    const val SYSTEM_ID = "{system.id}"

    /** That system's real display name, e.g. `Sony PlayStation`. */
    const val SYSTEM_NAME = "{system.name}"

    /** Absolute path of the folder droidtop scans for that system's games. */
    const val SYSTEM_FOLDER = "{system.folder}"

    /** A user-supplied search string, when the surface collected one. */
    const val QUERY = "{query}"

    fun expand(
        template: String,
        systemId: String? = null,
        systemName: String? = null,
        systemFolder: File? = null,
        query: String? = null,
    ): String {
        var out = template
        systemId?.let { out = out.replace(SYSTEM_ID, it) }
        systemName?.let { out = out.replace(SYSTEM_NAME, it) }
        systemFolder?.let { out = out.replace(SYSTEM_FOLDER, it.absolutePath) }
        query?.let { out = out.replace(QUERY, it) }
        return out
    }

    /** Which placeholders [template] actually uses -- lets a surface skip prompting for a query nobody asked for. */
    fun usedIn(template: String): Set<String> =
        setOf(SYSTEM_ID, SYSTEM_NAME, SYSTEM_FOLDER, QUERY).filter { template.contains(it) }.toSet()
}
