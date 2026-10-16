package dev.droidtop.library.integrations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginTileProtocol
import dev.droidtop.pluginhost.PluginWidgetProtocol
import dev.droidtop.pluginhost.ProvidedPoint
import dev.droidtop.pluginhost.TileState
import dev.droidtop.pluginhost.WidgetImage
import dev.droidtop.pluginhost.WidgetTemplate
import java.util.concurrent.ConcurrentHashMap

/**
 * Plugins on Android app widgets (docs/plugin-api.md 3 C10 and C2, Droidtop/tracker#563): what droidtop's one widget
 * renderer asks of plugins. A plugin supplies data only, a C10 `ui.widget` snapshot from a fixed set of templates or
 * a C2/C3 tile's state, and droidtop draws it; a tap comes back here through a broadcast and is a call through the
 * broker, off the main thread. No plugin code runs in a launcher's process.
 *
 * Which plugins can be on a widget is read from manifests ([PluginWidgetProtocol.widgetCapable]), so the widget maker
 * lists them without loading one. droidtop decides when to ask: at most once every [MIN_REFRESH_MS] for one source at
 * one size, sooner only right after the person pressed something on it.
 */
object PluginWidgets {
    /** A snapshot is asked at most this often (docs/plugin-api.md 3 C10). */
    const val MIN_REFRESH_MS = 30_000L

    /** `snapshot` draws a widget nobody is waiting on: a miss keeps the last one and is not a crash, like a tile's state. */
    const val SNAPSHOT_BUDGET_MS = 5_000L

    /** An action is a person's press: the default quick-call budget. */
    const val ACTION_BUDGET_MS = 15_000L

    /** The longest side an image is decoded to: more than any widget cell shows. */
    const val IMAGE_MAX_PX = 512

    enum class Kind { WIDGET, STATUS, QUICK }

    /** One thing a plugin can put on a widget; [key] is what the widget's config stores. */
    data class Source(val record: PluginRecord, val entry: ProvidedPoint) {
        val key: String get() = record.manifest.id + "/" + entry.point + "/" + (entry.id ?: "")
        val kind: Kind get() = when (entry.point) {
            PluginWidgetProtocol.POINT -> Kind.WIDGET
            PluginTiles.QUICK_POINT -> Kind.QUICK
            else -> Kind.STATUS
        }
        val label: String get() = entry.label ?: record.manifest.label
        val pluginLabel: String get() = record.manifest.label
        internal val tile: PluginTiles.Tile get() = PluginTiles.Tile(record, entry)
    }

    /** What one `snapshot` drew: [image] is the template's image, decoded once. */
    data class Snapshot(val template: WidgetTemplate, val image: Bitmap?, val atMs: Long)

    /**
     * Set by the widget renderer: redraw [appWidgetId] now, asking again (`force`). Called on a background thread
     * after a press on that widget was answered.
     */
    @Volatile
    var onActed: ((Context, Int) -> Unit)? = null

    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    /**
     * Every source on the running plugins, by plugin and then label. Manifests only: call it off the main thread. A
     * tile meant only while a game runs (`whileGame`, `gamePackages`) is never offered: a home screen has no game.
     */
    fun sources(context: Context): List<Source> =
        PluginWidgetProtocol.POINTS.flatMap { point -> providersOf(context, point) }
            .filter { (_, entry) -> PluginWidgetProtocol.widgetCapable(entry) && PluginTileProtocol.forGame(entry, gameRunning = false, runningPackage = null) }
            .map { (record, entry) -> Source(record, entry) }
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.pluginLabel.lowercase() }, { it.label.lowercase() }))

    /** The source a widget stored, or null when its plugin is gone, stopped or no longer offers it. */
    fun find(context: Context, key: String): Source? = sources(context).firstOrNull { it.key == key }

    private fun cacheKey(source: Source, widthDp: Int, heightDp: Int) = "${source.key}@${widthDp}x$heightDp"

    /**
     * A `ui.widget` source's snapshot at the widget's size. Inside [MIN_REFRESH_MS] of the last one it is that one,
     * unless [force] (right after a press). A plugin that does not answer in time, fails or answers what droidtop
     * cannot draw leaves the last snapshot standing; null only when there has never been one.
     */
    suspend fun snapshot(context: Context, source: Source, widthDp: Int, heightDp: Int, force: Boolean = false, now: Long = System.currentTimeMillis()): Snapshot? {
        if (source.kind != Kind.WIDGET) return null
        val key = cacheKey(source, widthDp, heightDp)
        val last = snapshots[key]
        if (last != null && !force && now - last.atMs < MIN_REFRESH_MS) return last
        val policy = PluginCrashPolicy(context.applicationContext)
        val fresh = try {
            val reply = policy.handle(
                source.record,
                newCall(PluginWidgetProtocol.POINT, "snapshot", PluginWidgetProtocol.SURFACE, PluginWidgetProtocol.snapshotArgs(widthDp, heightDp), SNAPSHOT_BUDGET_MS),
                timeoutMs = SNAPSHOT_BUDGET_MS,
                crashOnTimeout = false,
                // Nobody pressed anything: a widget's refresh never asks the person a question.
                userInitiated = false,
            )
            if (reply.ok) PluginWidgetProtocol.parse(reply.data) else null
        } finally {
            policy.shutdown()
        }
        fresh ?: return last
        return Snapshot(fresh, imageOf(fresh)?.let(::decode), now).also { snapshots[key] = it }
    }

    /**
     * A tile source's state, asked as the Quick Menu asks it (`state`, 5 s, a miss keeps the last value) with
     * `context.surface` `android.widget`. Inside [MIN_REFRESH_MS] of any answer it is the last one, unless [force].
     */
    suspend fun tileState(context: Context, source: Source, force: Boolean = false, now: Long = System.currentTimeMillis()): TileState? {
        if (source.kind == Kind.WIDGET) return null
        val stamp = tileTimes[source.key]
        val cached = PluginTiles.cached(source.tile)
        if (cached != null && stamp != null && !force && now - stamp < MIN_REFRESH_MS) return cached
        val state = PluginTiles.refresh(context, listOf(source.tile), PluginWidgetProtocol.SURFACE, userInitiated = false)[source.tile.key]
        if (state != null) tileTimes[source.key] = now
        return state
    }

    private val tileTimes = ConcurrentHashMap<String, Long>()

    /**
     * A press on a widget, from the widget renderer's broadcast. For a `ui.widget` source [actionId] must be one the
     * last snapshot offered ([PluginWidgetProtocol.actionIds]); it is sent as `action {id}`. For a quick tile it is the
     * C2 press (`toggle` or `action`); a status tile and an unknown source do nothing. A reply view is not opened from
     * a launcher: the reply's message is what the person sees. Blocks on the plugin: never on the main thread.
     */
    suspend fun act(context: Context, key: String, actionId: String?): PluginOutcome? {
        val source = find(context, key) ?: return null
        return when (source.kind) {
            Kind.QUICK -> PluginTiles.press(context, source.tile, PluginTiles.cached(source.tile), PluginWidgetProtocol.SURFACE)?.copy(screen = null)
            Kind.STATUS -> null
            Kind.WIDGET -> {
                val id = actionId ?: return null
                val offered = snapshots.entries.filter { it.key.startsWith(source.key + "@") }.flatMap { PluginWidgetProtocol.actionIds(it.value.template) }
                if (id !in offered) return null
                val policy = PluginCrashPolicy(context.applicationContext)
                try {
                    val reply = policy.handle(
                        source.record,
                        newCall(PluginWidgetProtocol.POINT, "action", PluginWidgetProtocol.SURFACE, PluginWidgetProtocol.actionArgs(id), ACTION_BUDGET_MS),
                    )
                    if (!reply.ok) {
                        PluginOutcome(message = "${source.label} failed: ${reply.message}", screen = null)
                    } else {
                        // A reply view has nowhere to open on a launcher's page; its message still shows.
                        PluginOutcome(message = reply.data.optString("message").takeIf { it.isNotBlank() }, screen = null)
                    }
                } finally {
                    policy.shutdown()
                }
            }
        }
    }

    private fun imageOf(template: WidgetTemplate): WidgetImage? = when (template) {
        is WidgetTemplate.Media -> template.image
        is WidgetTemplate.Picture -> template.image
        else -> null
    }

    /** Decodes [image] once, sampled down so its longer side is at most [IMAGE_MAX_PX]. Null when it does not decode. */
    private fun decode(image: WidgetImage): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > IMAGE_MAX_PX * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = IMAGE_MAX_PX.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale >= 1f) decoded else Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
    }.getOrNull()
}
