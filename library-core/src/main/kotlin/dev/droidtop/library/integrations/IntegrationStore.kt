package dev.droidtop.library.integrations

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import dev.droidtop.library.LaunchDisplay
import dev.droidtop.library.consoles.AmStartCommandToIntentConverter
import java.io.File
import org.json.JSONObject

/**
 * Loads and runs the user's own [Integration] declarations (docs/SPEC.md
 * §12).
 *
 * Integrations live as individual `.json` files in [userDir], droidtop's
 * external files folder -- the one a person without root can reach over
 * USB, adb or a file manager -- or arrive there through [import] from the
 * system file picker. Nothing here is bundled, downloaded, or synced: an
 * integration names a specific third-party app the user chose to install,
 * and which apps someone hooks into their own launcher is their business,
 * not something droidtop should ship a public catalogue of. Adding one is
 * dropping a file in; removing one is deleting it.
 *
 * Unparseable or incomplete files are skipped rather than failing the
 * whole load, the same defensive posture the player and theme databases
 * already take -- one malformed integration must not cost the user the
 * others.
 */
object IntegrationStore {

    /**
     * Where a user's own integration files live:
     * `Android/data/<package>/files/integrations`. Not `filesDir`, which
     * nobody without root can open (rig, 2026-09-24). Falls back to
     * `filesDir` only while shared storage is unmounted, so a read never
     * throws.
     */
    fun userDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "integrations")

    /** The folder as a person finds it in a file manager, for the screen that names it. */
    fun userDirLabel(context: Context): String = "Android/data/${context.packageName}/files/integrations"

    /**
     * The folder integrations used to live in. Its files are moved into
     * [userDir] on the next load, so an integration written by someone who
     * could reach it keeps working after the move.
     */
    private fun legacyDir(context: Context): File = File(context.filesDir, "integrations")

    private fun migrateLegacy(context: Context) {
        val legacy = legacyDir(context)
        val dir = userDir(context)
        if (!legacy.isDirectory || legacy == dir) return
        if (!dir.isDirectory && !dir.mkdirs()) return
        legacy.listFiles()?.forEach { file ->
            val target = File(dir, file.name)
            // A same-named file already in the new folder is the newer
            // one; the old copy stays put rather than overwriting it.
            if (file.isFile && !target.exists() && runCatching { file.copyTo(target) }.isSuccess) file.delete()
        }
        legacy.delete() // only succeeds once it is empty
    }

    /**
     * Copies an integration file the person picked into [userDir], named
     * after its id so adding a newer copy replaces the old one. Returns
     * what happened, for the settings screen to show. A file that is not
     * a complete integration is refused rather than copied, so the folder
     * never collects files the loader would silently skip.
     */
    fun import(context: Context, uri: Uri): String {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return "Couldn't read that file"
        val integration = runCatching { Integration.fromJson(JSONObject(text)) }.getOrNull()
            ?: return "That file isn't an integration: it needs id, package, capability " +
                "(acquire_content or open_with) and argumentsTemplate"
        migrateLegacy(context)
        val dir = userDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) return "Couldn't create ${userDirLabel(context)}"
        val safeId = integration.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir, "$safeId.json")
        val replaced = target.exists()
        return runCatching {
            target.writeText(text)
            (if (replaced) "Replaced " else "Added ") + integration.label +
                if (isInstalled(context, integration.packageName)) "" else
                    " (hidden until ${integration.packageName} is installed)"
        }.getOrElse { "Couldn't save it: ${it.message}" }
    }

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
        migrateLegacy(context)
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
        // The one real file an OPEN_WITH integration is being pointed at
        // (see [openWithTargetsFor]). Kept separate from [systemFolder]
        // rather than overloading it: they are different trust shapes --
        // this one becomes a read-only, per-call FileProvider grant for
        // exactly this file, and nothing else on disk.
        file: File? = null,
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
                (file ?: systemFolder)?.absolutePath,
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
        Put .json files in this folder to hook other installed apps into
        droidtop (docs/SPEC.md section 12), or use "Add integration file"
        in Settings > Console systems > App integrations. Rename a copy of
        this to something like "my-downloader.json" to activate it.

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

        capability is one of the two below, and it decides both where
        droidtop offers the integration and what it hands over:

          acquire_content  Offered inside a system's own settings screen,
                           where the system and its real destination
                           folder are both known. Gets {system.*}.
                           droidtop hands over a folder PATH, not write
                           access -- the other app writes with its own
                           storage permissions, or not at all.

          open_with        Offered on a game's own detail screen, one
                           chip per file droidtop has but cannot open
                           itself: the scraped manual (PDF) and the
                           scraped preview video. Use {file.uri} (and
                           -t, e.g. -t application/pdf) to receive it.
                           The grant is READ-ONLY and covers that one
                           file only.

        open_with deliberately cannot claim the game itself. Which app
        launches a ROM is already owned by the player database, per
        system and per game, with its own override UI -- an integration
        must not become a second, silent way to change that.

        The target app must actually accept what you send it. An app that
        only declares MAIN/LAUNCHER can be opened but not directed, so
        extras will simply be ignored by it.
    """.trimIndent()
}
