package dev.droidtop.library.integrations

import android.content.Context
import android.content.pm.PackageManager
import dev.droidtop.library.LaunchDisplay
import dev.droidtop.library.consoles.AmStartCommandToIntentConverter
import java.io.File
import org.json.JSONObject

/**
 * Loads and runs the user's own [Integration] declarations (docs/SPEC.md
 * §12).
 *
 * Integrations live as individual `.json` files in [userDir], a plain
 * folder in droidtop's own storage. Nothing here is bundled, downloaded,
 * or synced: an integration names a specific third-party app the user
 * chose to install, and which apps someone hooks into their own launcher
 * is their business, not something droidtop should ship a public
 * catalogue of. Adding one is dropping a file in; removing one is
 * deleting it.
 *
 * Unparseable or incomplete files are skipped rather than failing the
 * whole load, the same defensive posture the player and theme databases
 * already take -- one malformed integration must not cost the user the
 * others.
 */
object IntegrationStore {

    /** Where a user's own integration files live. */
    fun userDir(context: Context): File = File(context.filesDir, "integrations")

    /**
     * Every declared integration whose target app is actually installed.
     *
     * The installed check is not cosmetic: an integration for an app that
     * isn't present would surface a button that could only ever fail, and
     * droidtop already holds this line everywhere else (see
     * `availablePlayers`, which never offers an emulator that isn't
     * installed).
     */
    fun available(context: Context, capability: IntegrationCapability? = null): List<Integration> =
        all(context)
            .filter { capability == null || it.capability == capability }
            .filter { isInstalled(context, it.packageName) }

    /** Everything declared, installed or not — for a settings screen that must explain why one is unavailable. */
    fun all(context: Context): List<Integration> {
        val dir = userDir(context)
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
            ?: return emptyList()
        return files.sortedBy { it.name.lowercase() }.mapNotNull { file ->
            runCatching { Integration.fromJson(JSONObject(file.readText())) }.getOrNull()
        }
    }

    fun isInstalled(context: Context, packageName: String): Boolean =
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /**
     * Fires [integration] for a system, expanding its placeholders and
     * handing the result to the very same am-start parser that launches
     * emulators — so an integration inherits, for free, the real
     * component/action/extras handling, the FileProvider content:// URI
     * behaviour, and the read-permission grants that path already had to
     * get right (see [AmStartCommandToIntentConverter]'s own doc comment
     * about a live FileUriExposedException and a real PS2 launch failure).
     *
     * Routed through [LaunchDisplay] like every other launch, so the
     * "which display?" choice applies here too rather than integrations
     * being a second, inconsistent launch path.
     */
    fun run(
        context: Context,
        integration: Integration,
        systemId: String? = null,
        systemName: String? = null,
        systemFolder: File? = null,
        query: String? = null,
    ) {
        check(isInstalled(context, integration.packageName)) {
            "${integration.label} needs ${integration.packageName}, which isn't installed."
        }
        val placeholders = IntegrationPlaceholders.values(
            systemId = systemId,
            systemName = systemName,
            systemFolder = systemFolder,
            query = query,
        )
        // Passed as the converter's file anchor only when there genuinely
        // is one, so a template that references {file.uri} for the
        // destination folder still works, and one that doesn't is never
        // made to invent a file it has no use for.
        LaunchDisplay.start(
            context,
            AmStartCommandToIntentConverter.toIntent(
                context,
                integration.argumentsTemplate,
                systemFolder?.absolutePath,
                placeholders,
            ),
        )
    }

    /**
     * Writes an example the user can copy, so the format is discoverable
     * without documentation hunting. Only ever created when the folder is
     * empty -- droidtop must not keep resurrecting a file someone deleted.
     */
    fun seedExampleIfEmpty(context: Context) {
        val dir = userDir(context)
        if (dir.isDirectory && (dir.listFiles()?.isNotEmpty() == true)) return
        if (!dir.isDirectory && !dir.mkdirs()) return
        File(dir, "example.json.txt").writeText(EXAMPLE)
    }

    private val EXAMPLE = """
        Drop .json files in this folder to hook other installed apps into
        droidtop (docs/SPEC.md section 12). Rename a copy of this to
        something like "my-downloader.json" to activate it.

        {
          "id": "my-downloader",
          "label": "Get games",
          "description": "Search my ROM downloader for this system",
          "package": "com.example.downloader",
          "capability": "acquire_content",
          "argumentsTemplate": "-a android.intent.action.VIEW -n com.example.downloader/.MainActivity --es system {system.id} --es dest {system.folder}"
        }

        argumentsTemplate uses the same am-start syntax as the emulator
        player database. Placeholders droidtop fills in:

          {system.id}      the system being acted on, e.g. psx
          {system.name}    its display name, e.g. Sony PlayStation
          {system.folder}  absolute path droidtop scans for that system
          {query}          a search string, when the surface collected one

        capability is one of: acquire_content, open_with.

        The target app must actually accept what you send it. An app that
        only declares MAIN/LAUNCHER can be opened but not directed, so
        extras will simply be ignored by it.
    """.trimIndent()
}
