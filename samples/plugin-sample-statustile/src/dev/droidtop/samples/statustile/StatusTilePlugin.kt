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
    private val scheduledRuns = AtomicInteger(0)
    private val cancelledJobs = ConcurrentHashMap.newKeySet<String>()
    private var host: PluginContext? = null

    override fun onLoad(context: PluginContext) {
        loadCount += 1
        host = context
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
            // Its Quick Menu panel (docs/plugin-api.md 3 C17): a view like any other, drawn by droidtop.
            "ui.panel" -> when (call.op) {
                // The same panel in every mode (docs/plugin-api.md 1.9); droidtop says where it is drawn.
                "panel" -> PluginReply.ok(panelView(call.args.optJSONObject("context")?.optString("surface").orEmpty()))
                "remember" -> {
                    // A secret of this plugin's own, sealed by droidtop (G1); the panel's text box is in values.
                    val word = call.args.optJSONObject("values")?.optString("word").orEmpty().ifBlank { "hello" }
                    val reply = hostCall("vault", "put", JSONObject().put("key", "word").put("value", word))
                    PluginReply.ok(JSONObject().put("message", if (reply?.optBoolean("ok") == true) "Remembered" else "droidtop did not keep it"))
                }
                "hello" -> {
                    // A plugin to droidtop call through the broker: droidtop shows the toast and names this plugin on it.
                    val reply = host?.call("ui.toast", 1, "show", JSONObject().put("text", "Hello from the sample panel").toString())
                    val shown = reply?.let { runCatching { JSONObject(it).optBoolean("ok") }.getOrDefault(false) } == true
                    PluginReply.ok(JSONObject().put("message", if (shown) "Said hello" else "droidtop did not show the toast"))
                }
                else -> PluginReply.error(PluginErrorCode.UNSUPPORTED, "Unsupported op: ${call.op}")
            }
            // Rows on a game's page (C18): the game is named only when droidtop handed its identity over (library.read).
            "ui.game_section" -> when (call.op) {
                "section" -> PluginReply.ok(sectionView(call.args.optJSONObject("context")?.optJSONObject("target")))
                else -> PluginReply.error(PluginErrorCode.UNSUPPORTED, "Unsupported op: ${call.op}")
            }
            // A shelf on Home (C11): the person's favourites, named by id from the library droidtop described.
            "gaming.rows" -> when (call.op) {
                "rows" -> PluginReply.ok(shelves(call.args.optJSONObject("context")?.optJSONArray("library")))
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
        if (envelope != null && envelope.point == "jobs.schedule" && envelope.op == "run") {
            // A scheduled task (E9): droidtop starts it when it is due; it reports and ends.
            scheduledRuns.incrementAndGet()
            progress.complete(PluginResult.success(mapOf("message" to "Sample check ran")))
            return
        }
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

    private fun item(type: String, id: String, title: String, build: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject().put("type", type).put("id", id).put("title", title).apply(build)

    private fun view(vararg items: JSONObject): JSONObject = JSONObject()
        .put("view", 1)
        .put("sections", JSONArray().put(JSONObject().put("id", "main").put("items", JSONArray(items.toList()))))

    /** A broker call's reply as JSON, or null when there is no host. */
    private fun hostCall(api: String, op: String, args: JSONObject): JSONObject? =
        host?.call(api, 1, op, args.toString())?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun panelView(surface: String): JSONObject = view(
        item("info", "mode", "Drawn in") { put("value", hostCall("host", "info", JSONObject())?.optJSONObject("data")?.optString("mode") ?: "unknown") },
        item("info", "surface", "Place") { put("value", surface.ifBlank { "unknown" }) },
        item("info", "loads", "Loaded") { put("value", "$loadCount time(s)") },
        item("info", "ticks", "Scheduled checks run") { put("value", scheduledRuns.get().toString()) },
        item("info", "word", "Remembered word") {
            put("value", hostCall("vault", "get", JSONObject().put("key", "word"))?.optJSONObject("data")?.optString("value")?.takeIf { it.isNotBlank() && it != "null" } ?: "none")
        },
        item("text", "word", "A word to remember") { put("value", "") },
        item("button", "remember", "Remember it") {
            put("subtitle", "Keeps it in droidtop's vault for this plugin")
            put("action", JSONObject().put("kind", "call").put("op", "remember"))
        },
        item("button", "hello", "Say hello") {
            put("subtitle", "Asks droidtop to show a short message")
            put("action", JSONObject().put("kind", "call").put("op", "hello"))
        },
    )

    private fun sectionView(target: JSONObject?): JSONObject {
        val title = target?.optString("title")?.takeIf { it.isNotBlank() }
        return view(
            item("info", "game", "This game") { put("value", title ?: "Not shared with this plugin") },
            item("info", "about", "Rows from a plugin") { put("subtitle", "Drawn by droidtop on the game's own page") },
        )
    }

    private fun shelves(library: JSONArray?): JSONObject {
        val favourites = JSONArray()
        if (library != null) {
            for (i in 0 until library.length()) {
                val entry = library.optJSONObject(i) ?: continue
                if (entry.optBoolean("favorite") && favourites.length() < 24) favourites.put(entry.optString("id"))
            }
        }
        val shelves = JSONArray()
        if (favourites.length() > 0) shelves.put(JSONObject().put("id", "favourites").put("title", "Your favourites").put("entries", favourites))
        return JSONObject().put("shelves", shelves)
    }

    companion object {
        const val FORCE_CRASH_QUERY = "force-crash"
    }
}
