package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.consoles.BiosFileSpec
import dev.droidtop.library.consoles.EmulatorSetup
import dev.droidtop.library.consoles.SystemBiosSpec
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.pluginhost.AcquireDownloads
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginResult
import dev.droidtop.runtime.tasks.ElevatedFiles
import dev.droidtop.runtime.tasks.RiskyPrompts
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * `emulator.bios@1` (docs/plugin-api.md 3 A11, docs/SPEC.md "Emulator setup helper", Droidtop/tracker#415): a plugin that
 * can supply BIOS files. The emulator setup helper offers "Get the BIOS for this system" only while one is installed and
 * allowed; the plugin says which files it can supply (`list`) and hands over a download (`acquire`), and droidtop does the
 * rest: it names the target from its own BIOS database, never from the plugin, checks the bytes, and writes the file into
 * the emulator's BIOS folder by the helper's one write path ([EmulatorSetup.write], which is the Risky actions gate for
 * another app's folder).
 */
object PluginBios {
    const val POINT = "emulator.bios"

    /** The download post step that places a downloaded BIOS file; registered at process start so a restored job finds it. */
    const val POST = "place_bios"

    const val MAX_CANDIDATES = 500
    private const val MAX_NAME = 200

    /** A file a provider says it can supply. */
    class Candidate(val name: String, val md5: List<String>, val size: Long?, val source: String?)

    /** A candidate matched to the entry of droidtop's BIOS database it would fill. */
    class Offer(val entry: BiosFileSpec, val candidate: Candidate)

    /** The installed, running plugins that provide the point and that the person has not switched it off for. Manifests only. */
    fun providers(context: Context): List<PluginRecord> {
        val grants = PluginGrants.forContext(context)
        return providersOf(context, POINT).map { it.first }.distinctBy { it.manifest.id }
            .filter { PluginGrants.pointRefusal(it, grants.read(it.manifest.id), POINT) == null }
    }

    /** `list`'s reply read: at most [MAX_CANDIDATES] files with a name, md5 values lower-cased; anything else dropped. Pure. */
    fun parseList(data: JSONObject): List<Candidate> {
        val array = data.optJSONArray("files") ?: return emptyList()
        return (0 until minOf(array.length(), MAX_CANDIDATES)).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            val name = o.optString("name").trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME } ?: return@mapNotNull null
            val md5 = o.optJSONArray("md5")?.let { list -> (0 until list.length()).map { list.optString(it).trim().lowercase() } }.orEmpty()
                .filter { it.matches(Regex("[0-9a-f]{32}")) }
            Candidate(name, md5, o.optLong("size", 0L).takeIf { it > 0 }, o.optString("source").trim().take(60).takeIf { it.isNotEmpty() })
        }
    }

    /**
     * The candidates that would fill an entry of [needed]: a shared md5 decides, else the same file name (with or without
     * the folder, ignoring case). One offer per entry, the first candidate that fits. Pure.
     */
    fun offers(needed: List<BiosFileSpec>, candidates: List<Candidate>): List<Offer> = needed.mapNotNull { entry ->
        val relative = EmulatorSetup.biosRelative(entry.file)
        val known = entry.md5.map { it.lowercase() }.toSet()
        val byHash = candidates.firstOrNull { c -> c.md5.any { it in known } }
        val byName = candidates.firstOrNull { c ->
            c.name.equals(relative, ignoreCase = true) || c.name.equals(relative.substringAfterLast('/'), ignoreCase = true)
        }
        (byHash ?: byName)?.let { Offer(entry, it) }
    }

    /**
     * Places a downloaded BIOS file: its md5 must be one the database lists for the entry (when it lists any), then it is
     * written whole by [write]. A failure keeps the download and says where it is. [write] is [EmulatorSetup.write].
     */
    internal fun placeDownloaded(file: File, args: Map<String, String>, write: (String, ByteArray) -> Boolean = EmulatorSetup::write): String {
        check(file.length() <= ElevatedFiles.MAX_WRITE_BYTES) { "that file is too large to be a BIOS file" }
        val bytes = file.readBytes()
        val known = args["biosMd5s"].orEmpty().split(',').filter { it.isNotBlank() }
        val folder = requireNotNull(args["biosFolder"]) { "the BIOS folder is missing" }
        val target = requireNotNull(args["biosTarget"]) { "the BIOS file name is missing" }
        check(known.isEmpty() || EmulatorSetup.md5(bytes) in known) {
            "the downloaded file is not a $target dump droidtop knows of. It is kept at ${file.absolutePath}"
        }
        check(write("$folder/$target", bytes)) { "droidtop could not write to $folder. The download is kept at ${file.absolutePath}" }
        return "Added $target to ${args["biosApp"] ?: "the emulator"}'s BIOS folder"
    }

    fun registerDownloadPost() {
        DownloadJobs.registerPost(POST) { _, file, args -> withContext(Dispatchers.IO) { placeDownloaded(file, args) } }
    }

    /**
     * The row the helper shows under "Add a BIOS file": "Get the BIOS for this system", only while a provider is
     * installed and allowed, opening a screen that asks each provider (when it opens, off the main thread) which of the
     * missing files it can supply. Null when there is no provider.
     */
    fun row(context: Context, systemId: String, systemName: String, bios: SystemBiosSpec, folder: String, app: String, viaHelper: Boolean): CatalogItem? {
        val providers = providers(context)
        if (providers.isEmpty()) return null
        return NestedScreenItem(
            id = "emulator_bios_get_$systemId",
            title = "Get the BIOS for this system",
            subtitle = "Download the missing files with " + providers.joinToString(", ") { it.manifest.label },
            inline = CatalogScreen(
                id = "emulator_bios_get_screen_$systemId",
                title = "Get the BIOS",
                subtitle = "for $systemName",
                groups = { ctx -> withContext(Dispatchers.IO) { groups(ctx, providers, systemId, bios, folder, app, viaHelper) } },
            ),
        )
    }

    private suspend fun groups(context: Context, providers: List<PluginRecord>, systemId: String, bios: SystemBiosSpec, folder: String, app: String, viaHelper: Boolean): List<CatalogGroup> {
        val have = EmulatorSetup.listFolder(folder).orEmpty()
        val missing = bios.files.filter { entry -> have.none { it.equals(EmulatorSetup.biosRelative(entry.file), ignoreCase = true) } }
        if (missing.isEmpty()) {
            return listOf(CatalogGroup("bios_none", null, listOf(ActionItem("bios_none_row", "Every file droidtop knows of is already in $folder", run = {}))))
        }
        return providers.map { record ->
            val label = record.manifest.label
            val reply = PluginViews.call(context, record, POINT, "list", JSONObject().put("system", systemId))
            val items: List<CatalogItem> = if (!reply.ok) {
                listOf(ActionItem("bios_failed_${record.manifest.id}", "$label: ${reply.message ?: "it did not answer"}", run = {}))
            } else {
                val found = offers(missing, parseList(reply.data))
                if (found.isEmpty()) {
                    listOf(ActionItem("bios_empty_${record.manifest.id}", "$label has none of the missing files", run = {}))
                } else {
                    found.map { offer ->
                        val target = EmulatorSetup.biosRelative(offer.entry.file)
                        AsyncActionItem(
                            id = "bios_get_${record.manifest.id}_$target",
                            title = "Get $target",
                            subtitle = listOfNotNull(offer.candidate.source, offer.candidate.size?.let { "${it / 1024 / 1024 + 1} MB" }).joinToString(" · ").ifEmpty { null },
                            confirmTitle = RiskyPrompts.getBiosConfirm(label, offer.candidate.source, "$folder/$target", app, viaHelper),
                            run = { ctx, onStatus ->
                                val result = get(ctx, record, systemId, offer, folder, app, onStatus)
                                if (result.ok) result.values["summary"] ?: "Added $target to $app's BIOS folder" else "Not added: ${result.error ?: "it failed"}"
                            },
                        )
                    }
                }
            }
            CatalogGroup("bios_${record.manifest.id}", label, items)
        }
    }

    /** Asks the provider for [offer]'s file (`acquire`, a job), then runs its download as a Downloads job that places it. */
    internal suspend fun get(
        context: Context,
        record: PluginRecord,
        systemId: String,
        offer: Offer,
        folder: String,
        app: String,
        onStatus: (String) -> Unit,
    ): PluginResult {
        val target = EmulatorSetup.biosRelative(offer.entry.file)
        val args = JSONObject().put("system", systemId).put("name", offer.candidate.name).put("md5", JSONArray(offer.entry.md5))
        val job = PluginViews.runJob(context, record, POINT, "acquire", args, "Get $target", onStatus)
        if (!job.ok) return PluginResult.failure("${record.manifest.label}: ${job.error ?: "it failed"}")
        val descriptor = AcquireDownloads.parse(job.values)?.singleOrNull()
            ?: return PluginResult.failure("${record.manifest.label} returned no usable download")
        if (descriptor.session != null) return PluginResult.failure("a BIOS download cannot use a web session")
        if (descriptor.sha256 == null && descriptor.sha1 == null && descriptor.md5 == null && offer.entry.md5.isEmpty()) {
            return PluginResult.failure("${record.manifest.label} gave no digest to check the file with")
        }
        return DownloadJobs.run(
            context = context,
            title = "Get $target",
            post = POST,
            url = descriptor.url,
            name = "bios_${System.currentTimeMillis()}",
            sha256 = descriptor.sha256,
            sha1 = descriptor.sha1,
            md5 = descriptor.md5,
            maxBytes = descriptor.size ?: 0L,
            headers = descriptor.headers,
            extra = mapOf(
                "biosFolder" to folder,
                "biosTarget" to target,
                "biosMd5s" to offer.entry.md5.joinToString(","),
                "biosApp" to app,
            ),
            onStatus = onStatus,
        )
    }
}
