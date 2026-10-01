package dev.droidtop.samples.statustile

import dev.droidtop.pluginhost.DroidtopPlugin
import dev.droidtop.pluginhost.PluginArgs
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginContext
import dev.droidtop.pluginhost.PluginJobProgress
import dev.droidtop.pluginhost.PluginResult
import dev.droidtop.pluginhost.PluginReply
import dev.droidtop.pluginhost.PluginCall
import dev.droidtop.pluginhost.PluginErrorCode
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class StatusTilePlugin : DroidtopPlugin {
    private var loadCount = 0
    private val lastPercent = AtomicInteger(-1)
    private val cancelledJobs = ConcurrentHashMap.newKeySet<String>()

    override fun onLoad(context: PluginContext) {
        loadCount += 1
    }

    override fun invoke(capability: PluginCapability, args: PluginArgs): PluginResult {
        if (capability != PluginCapability.STATUS_TILE) {
            return PluginResult.failure("StatusTilePlugin only implements status_tile")
        }
        if (args.string("query") == FORCE_CRASH_QUERY) {
            throw IllegalStateException("forced crash for dq-plugins-01")
        }
        return PluginResult.success(
            mapOf(
                "label" to "Sample tile",
                "value" to "loaded $loadCount time(s), called OK",
            ),
        )
    }

    override fun handle(call: PluginCall): PluginReply {
        return when (call.point) {
            "ui.status_tile" -> when (call.op) {
                "state" -> PluginReply.ok(
                    JSONObject().put("label", "Sample tile").put("value", "loaded $loadCount time(s), called OK")
                )
                else -> PluginReply.error(PluginErrorCode.UNSUPPORTED, "Unsupported op: ${call.op}")
            }
            "ui.settings" -> when (call.op) {
                "view" -> PluginReply.ok(buildView())
                else -> PluginReply.error(PluginErrorCode.UNSUPPORTED, "Unsupported op: ${call.op}")
            }
            else -> PluginReply.error(PluginErrorCode.UNSUPPORTED, "Unsupported point: ${call.point}")
        }
    }

    override fun startJob(jobId: String, capability: PluginCapability, args: PluginArgs, progress: PluginJobProgress) {
        if (capability != PluginCapability.SETTINGS_ROWS) {
            progress.complete(PluginResult.failure("StatusTilePlugin only supports settings_rows jobs"))
            return
        }
        val callText = args.string("call") ?: ""
        val envelope = PluginCall.fromJson(callText)
        if (envelope == null || envelope.op != "count") {
            progress.complete(PluginResult.failure("Unsupported job op: ${envelope?.op ?: "none"}"))
            return
        }
        Thread {
            try {
                for (i in 0..10) {
                    if (cancelledJobs.contains(jobId)) {
                        progress.complete(PluginResult.failure("Cancelled"))
                        return@Thread
                    }
                    val percent = i * 10
                    lastPercent.set(percent)
                    progress.report(percent, "Running sample task")
                    Thread.sleep(300)
                }
                if (cancelledJobs.contains(jobId)) {
                    progress.complete(PluginResult.failure("Cancelled"))
                    return@Thread
                }
                progress.complete(PluginResult.success(mapOf("message" to "Sample task finished")))
            } catch (e: Exception) {
                progress.complete(PluginResult.failure(e.message ?: "Job failed"))
            } finally {
                cancelledJobs.remove(jobId)
            }
        }.start()
    }

    override fun cancelJob(jobId: String) {
        cancelledJobs.add(jobId)
    }

    private fun buildView(): JSONObject {
        val percent = lastPercent.get()
        return JSONObject().apply {
            put("view", 1)
            put("title", "Sample settings")
            put("sections", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "main")
                    put("items", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "info")
                            put("id", "info")
                            put("title", "Sample plugin settings")
                            put("value", "Contract 2 settings view")
                        })
                        put(JSONObject().apply {
                            put("type", "progress")
                            put("id", "progress")
                            put("title", "Last job progress")
                            put("value", percent)
                        })
                        put(JSONObject().apply {
                            put("type", "button")
                            put("id", "count")
                            put("title", "Run a sample task")
                            put("action", JSONObject().apply {
                                put("kind", "job")
                                put("op", "count")
                                put("title", "Sample task")
                            })
                        })
                    })
                })
            })
        }
    }

    companion object {
        const val FORCE_CRASH_QUERY = "force-crash"
    }
}
