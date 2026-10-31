package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.consoles.BiosDatabase
import dev.droidtop.pluginhost.AcquireDownloadDescriptor
import dev.droidtop.pluginhost.ExtensionPoints
import dev.droidtop.pluginhost.AcquireFileName
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.DownloadPart
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginResult
import dev.droidtop.pluginhost.SplitArchives
import dev.droidtop.pluginhost.WebSessions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Starts the Downloads job for the download part of a source's acquire reply (docs/plugin-api.md 1.6): where it goes
 * (the screen's folder, or the reply's `system` folder), the file names, the digests, the headers a web session captured,
 * and the engine hint. Several descriptors are one job. An update ([update], docs/SPEC.md 7g "Getting an update") goes
 * where it says instead: its new version folder beside the game's newest, with the saves to bring along.
 */
internal object AcquireDownload {
    suspend fun run(
        context: Context,
        record: PluginRecord,
        title: String,
        hostContext: JSONObject,
        values: Map<String, String>,
        descriptors: List<AcquireDownloadDescriptor>,
        onStatus: (String) -> Unit,
        /** An update's placement: `destinationPath`, the `versionFolder` and the saves' arguments ([UpdatePlacement]). */
        update: Map<String, String>? = null,
        /** The download's percent each time it changes (0 to 100; -1 while the size is not known). */
        onPercent: (Int) -> Unit = {},
    ): PluginResult {
        if (descriptors.isEmpty()) return PluginResult.failure("${record.manifest.label} returned an invalid download descriptor")
        // The source's system hint: the game's own system folder, unless this screen is already that system's. An
        // update goes beside the game's other versions whatever the source hints.
        val systemHint = AcquireSystemHint.valid(values[AcquireSystemHint.KEY]).takeIf { update == null }
        val destination = if (update != null) {
            update["destinationPath"]
        } else if (AcquireSystemHint.overrides(systemHint, hostContext)) {
            AcquireSystemHint.folderFor(context, systemHint!!) { made -> onStatus("Made a ${made.name} folder in ${made.parentFile?.name ?: made.parent}") }?.absolutePath
        } else {
            hostContext.optString("destination").takeIf { it.isNotBlank() }
        }
        // Downloads a page started in the plugin's web session: droidtop's own headers for each, by its token.
        val now = System.currentTimeMillis()
        val captured = descriptors.map { descriptor ->
            descriptor.session?.let { WebSessions.take(record.manifest.id, it, descriptor.url, now) }
        }
        if (descriptors.indices.any { descriptors[it].session != null && captured[it] == null }) {
            return PluginResult.failure("the download from ${record.manifest.label}'s web page is no longer waiting; open it again")
        }
        if (destination == null) {
            return PluginResult.failure(
                if (systemHint != null) "droidtop doesn't know the system $systemHint, so it has no folder for it; add one under Settings > Game folders" else "the game folder is not available",
            )
        }
        // A BIOS file goes to the games folder's bios folder under the database's name, not among the games (#558).
        val systemId = systemHint ?: hostContext.optJSONObject("system")?.optString("id")?.takeIf { it.isNotBlank() }
        val bios = descriptors.singleOrNull()?.takeIf { !it.unpack && it.swarm == null && systemId != null && update == null }?.let { only ->
            withContext(Dispatchers.IO) {
                val files = BiosDatabase.forSystem(context, systemId!!)?.files.orEmpty()
                val provides = ExtensionPoints.declares(record.manifest, PluginBios.POINT)
                AcquireBios.entryFor(files, only.fileName, only.md5, provides)?.let { entry ->
                    AcquireBios.targetFor(AcquireBios.rootFor(GamesRoots.current(context), File(destination)), entry)
                }
            }
        }
        if (descriptors.size > 1 && descriptors.any { it.unpack } &&
            (!descriptors.all { it.unpack } || SplitArchives.detect(descriptors.map { it.fileName }) == null)
        ) {
            return PluginResult.failure("${record.manifest.label} asked to unpack files that are not the parts of one split archive")
        }
        descriptors.singleOrNull()?.takeIf { it.swarm != null }?.let { swarm ->
            // A torrent or a magnet: its files arrive as one folder, placed as one entry (or as the update's version folder).
            return DownloadJobs.run(
                context = context,
                title = title,
                post = DownloadJobs.POST_PLACE_IN_FOLDER,
                url = swarm.url,
                name = AcquireFileName.areaName(now, ""),
                maxBytes = swarm.size ?: 0L,
                headers = swarm.headers,
                extra = buildMap {
                    put("destinationPath", destination)
                    put("targetName", swarm.fileName)
                    AcquireEngineHint.valid(values[AcquireEngineHint.KEY])?.let { put(AcquireEngineHint.KEY, it) }
                    update?.let { putAll(it) }
                },
                onStatus = onStatus,
                onPercent = onPercent,
                swarm = swarm.swarm,
                torrent = swarm.torrent,
            )
        }
        val parts = descriptors.mapIndexed { index, descriptor ->
            DownloadPart(
                url = descriptor.url,
                name = AcquireFileName.areaName(now + index, descriptor.fileName),
                targetName = descriptor.fileName,
                sha256 = descriptor.sha256,
                sha1 = descriptor.sha1,
                md5 = descriptor.md5,
                maxBytes = descriptor.size ?: 0L,
                headers = descriptor.headers + captured[index]?.headers.orEmpty(),
            )
        }
        val first = parts.first()
        return DownloadJobs.run(
            context = context,
            title = title,
            post = DownloadJobs.POST_PLACE_IN_FOLDER,
            url = first.url,
            name = first.name,
            sha256 = first.sha256,
            sha1 = first.sha1,
            md5 = first.md5,
            // An update that is one archive is the game's new version only once unpacked into its version folder, whether or
            // not the source said so (a download taken over from a file host's page says nothing).
            unpack = if (descriptors.any { it.unpack } || (update != null && descriptors.size == 1 && DownloadJobs.archiveStem(first.targetName) != null)) {
                DownloadJobs.UNPACK_ARCHIVE
            } else {
                null
            },
            maxBytes = first.maxBytes,
            headers = first.headers,
            more = parts.drop(1),
            extra = buildMap {
                put("destinationPath", bios?.parentFile?.absolutePath ?: destination)
                put("targetName", bios?.name ?: first.targetName)
                AcquireEngineHint.valid(values[AcquireEngineHint.KEY])?.let { put(AcquireEngineHint.KEY, it) }
                update?.let { putAll(it) }
            },
            onStatus = onStatus,
            onPercent = onPercent,
        )
    }
}
