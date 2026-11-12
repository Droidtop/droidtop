package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.pluginhost.DownloadGate
import dev.droidtop.pluginhost.PluginErrorCode
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import org.json.JSONObject

/**
 * The bulk refresh of a source's index (owner, 2026-11-12, Droidtop/tracker#937: "bulk refresh on by default"): a
 * source that publishes its whole list (`"bulkIndex": true` on its `library.sources` entry) is asked for it page by
 * page with `library.sources index {limit, cursor}` → `{results, next}` (docs/plugin-api.md 1.6), and each page is
 * added to its index ([SourceIndex]). It runs as an automatic download job: the download policy (Wi-Fi, while
 * playing) decides when, it is in Downloads like any other, and a restart goes on from the page it reached. Each
 * source at most every [EVERY_MS], started from the scheduled update pass.
 */
object SourceIndexRefresh {
    const val KIND = "source-index"
    const val EVERY_MS = 7L * 24 * 60 * 60 * 1000
    private const val PAGE = 500
    private const val MAX_PAGES = 400
    private const val PAGE_TIMEOUT_MS = 60_000L
    private const val ARG_PLUGIN = "plugin"

    /** Registers the job; called once at process start, before jobs are restored. */
    fun register(context: Context) {
        val app = context.applicationContext
        PluginJobsCenter.registerNative(KIND, reattachOnRestart = true, download = true) { args, checkpoint, report ->
            run(app, args, checkpoint) { percent, line, next -> report(percent, line, next) }
        }
    }

    /** Whether [record]'s source publishes its whole list and lets droidtop index it. */
    fun offers(record: PluginRecord): Boolean = SourceIndex.allowed(record) &&
        record.manifest.v2.provides.any { point ->
            point.point == "library.sources" && runCatching { JSONObject(point.extra).optBoolean("bulkIndex", false) }.getOrDefault(false)
        }

    /** Starts the refresh of every source that offers one and was last refreshed [EVERY_MS] or more ago. Disk reads. */
    fun startDue(context: Context, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        PluginStore.installed(app).filter { it.enabled && offers(it) }.forEach { record ->
            val last = SourceIndex.refreshedAt(app, sourceIdOf(record)) ?: 0L
            if (now - last < EVERY_MS) return@forEach
            PluginJobsCenter.startNative(
                app,
                KIND,
                "Index of ${record.manifest.label}",
                mapOf(ARG_PLUGIN to record.manifest.id, DownloadGate.ARG_AUTOMATIC to "1"),
                owner = "Downloads",
            )
        }
    }

    internal fun sourceIdOf(record: PluginRecord) = "plugin_${record.manifest.id}"

    private suspend fun run(
        context: Context,
        args: Map<String, String>,
        checkpoint: String?,
        report: (percent: Int, statusLine: String, checkpoint: String?) -> Unit,
    ): String {
        val id = args[ARG_PLUGIN] ?: throw IllegalStateException("this index refresh does not say which source")
        val record = PluginStore.installed(context).firstOrNull { it.manifest.id == id }
            ?: throw IllegalStateException("that source is no longer installed")
        if (!offers(record)) return "${record.manifest.label} no longer publishes its list"
        val sourceId = sourceIdOf(record)
        var cursor = checkpoint
        var added = 0
        var pages = 0
        while (pages < MAX_PAGES) {
            val callArgs = JSONObject().put("limit", PAGE).apply { cursor?.let { put("cursor", it) } }
            val reply = PluginViews.call(context, record, "library.sources", "index", callArgs, timeoutMs = PAGE_TIMEOUT_MS)
            if (!reply.ok) {
                if (reply.code == PluginErrorCode.UNSUPPORTED) break
                throw IllegalStateException("${record.manifest.label}: ${dev.droidtop.pluginhost.SourceFailures.plain(reply.message)}")
            }
            SourceIndex.record(context, sourceId, reply.data)
            added += reply.data.optJSONArray("results")?.length() ?: 0
            pages++
            cursor = reply.data.optString("next").takeIf { it.isNotBlank() } ?: break
            report(-1, "$added entries so far", cursor)
        }
        SourceIndex.markRefreshed(context, sourceId, System.currentTimeMillis())
        return "${record.manifest.label}: $added entries indexed"
    }
}
