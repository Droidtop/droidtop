package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.pluginhost.AcquireDownloadDescriptor
import dev.droidtop.pluginhost.AcquireFileName
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.DownloadPart
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginResult
import dev.droidtop.pluginhost.WebSessions
import org.json.JSONObject

/**
 * Starts the Downloads job for the download part of a source's acquire reply (docs/plugin-api.md 1.6): where it goes
 * (the screen's folder, or the reply's `system` folder), the file names, the digests, the headers a web session captured,
 * and the engine hint. Several descriptors are one job.
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
    ): PluginResult {
        if (descriptors.isEmpty()) return PluginResult.failure("${record.manifest.label} returned an invalid download descriptor")
        // The source's system hint: the game's own system folder, unless this screen is already that system's.
        val systemHint = AcquireSystemHint.valid(values[AcquireSystemHint.KEY])
        val destination = if (AcquireSystemHint.overrides(systemHint, hostContext)) {
            AcquireSystemHint.folderFor(context, systemHint!!)?.absolutePath
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
                if (systemHint != null) "there is no games folder for $systemHint; add one under Settings > Game folders" else "the game folder is not available",
            )
        }
        if (descriptors.size > 1 && descriptors.any { it.unpack }) {
            return PluginResult.failure("${record.manifest.label} asked to unpack several files; unpacking takes one archive")
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
            unpack = if (descriptors.first().unpack) DownloadJobs.UNPACK_ARCHIVE else null,
            maxBytes = first.maxBytes,
            headers = first.headers,
            more = parts.drop(1),
            extra = buildMap {
                put("destinationPath", destination)
                put("targetName", first.targetName)
                AcquireEngineHint.valid(values[AcquireEngineHint.KEY])?.let { put(AcquireEngineHint.KEY, it) }
            },
            onStatus = onStatus,
        )
    }
}
