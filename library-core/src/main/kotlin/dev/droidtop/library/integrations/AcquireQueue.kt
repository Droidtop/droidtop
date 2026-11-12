package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.pluginhost.DownloadResolver
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A source's Download as a job of the download queue (Droidtop/tracker#354; the owner: "It's Steam's downloader flow,
 * button on the page only provide controls for that specific game's part of the download queue, and it just tells the
 * download queue what you do"). The page's press is [PluginViews.acquire]: one Downloads job from that moment. The job
 * asks the source ([PluginViews.acquireInQueue]), then fetches, places and indexes what it answered, whether or not
 * the page is still open; after a restart it asks the source again.
 */
object AcquireQueue {
    /** The resolver's name in [dev.droidtop.pluginhost.DownloadJobs]. */
    const val RESOLVER = "source.acquire"

    private const val ARG_PLUGIN = "acquirePlugin"
    private const val ARG_HOST = "acquireHost"
    private const val ARG_CALL = "acquireCall"
    private const val ARG_TITLE = "acquireTitle"

    /** The job's arguments: which source, and the call it is asked, as the page built them. Pure. */
    fun argsOf(record: PluginRecord, hostContext: JSONObject, callArgs: JSONObject): Map<String, String> = mapOf(
        ARG_PLUGIN to record.manifest.id,
        ARG_HOST to hostContext.toString(),
        ARG_CALL to callArgs.toString(),
    )

    /** Asks the source inside the download job; registered at process start, before jobs are restored. */
    val resolver = DownloadResolver { context, args, _ -> resolve(context, args) }

    private suspend fun resolve(context: Context, args: Map<String, String>): String {
        val id = args[ARG_PLUGIN] ?: throw IllegalStateException("this download does not say which source it is from")
        val record = withContext(Dispatchers.IO) { PluginStore.installed(context).firstOrNull { it.manifest.id == id } }
            ?: throw IllegalStateException("that source is no longer installed")
        val title = args[ARG_TITLE] ?: args["title"] ?: record.manifest.label
        val result = PluginViews.acquireInQueue(
            context = context,
            record = record,
            title = title,
            hostContext = JSONObject(args[ARG_HOST] ?: "{}"),
            callArgs = JSONObject(args[ARG_CALL] ?: "{}"),
            onStatus = {},
        )
        if (!result.ok) throw IllegalStateException(result.error ?: "${record.manifest.label} could not get it")
        return result.values["message"] ?: result.values["summary"] ?: "Added from ${record.manifest.label}"
    }
}
