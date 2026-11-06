package dev.droidtop.library.integrations

import android.content.Context
import android.net.Uri
import dev.droidtop.pluginhost.AuditEntry
import dev.droidtop.pluginhost.FileHandlers
import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.PickedDocument
import dev.droidtop.pluginhost.PluginAudit
import dev.droidtop.pluginhost.PluginFileTokens
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginModes
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import java.io.File

/**
 * Plugins in droidtop's Open with (docs/plugin-api.md 1.12, catalogue E4, Droidtop/tracker#221): each running plugin
 * whose `files.handler` entry has an association for the file, in Desktop, with the point's consent granted. Picking
 * one hands it the file as a read-only token and calls its `open`; every hand-over is in its activity log.
 */
object PluginOpenWith {
    /** A plugin that opens the file, and the association that matched. */
    data class Handler(val record: PluginRecord, val label: String)

    /** The plugins that open [fileName] of type [mime], by name. Reads manifests and grants only; call off the main thread. */
    fun handlersFor(context: Context, fileName: String, mime: String?): List<Handler> {
        val grants = PluginGrants.forContext(context)
        return providersOf(context, FileHandlers.POINT, PluginModes.DESKTOP)
            .filter { (record, _) -> PluginGrants.provideState(record, grants.read(record.manifest.id), FileHandlers.POINT) == GrantState.GRANTED }
            .mapNotNull { (record, entry) ->
                FileHandlers.match(entry, fileName, mime)?.let { Handler(record, it.label.ifBlank { record.manifest.label }) }
            }
            .distinctBy { it.record.manifest.id }
            .sortedBy { it.record.manifest.label.lowercase() }
    }

    /**
     * Hands [file] to [handler]'s plugin: a read-only token for it ([PluginFileTokens], as a pick in the system picker
     * makes, D4), then the plugin's `open {token, name, mime}`. Null when the plugin took it, or the sentence to show.
     */
    suspend fun open(context: Context, handler: Handler, file: File, mime: String?): String? {
        val record = handler.record
        val id = record.manifest.id
        val token = runCatching {
            PluginFileTokens.forPluginsRoot(PluginStore.root(context))
                .add(id, PickedDocument(Uri.fromFile(file).toString(), file.name, file.length(), writable = false))
        }.getOrElse { return "${record.manifest.label} couldn't be given the file: ${it.message}" }
        val reply = PluginViews.call(context, record, FileHandlers.POINT, FileHandlers.OP_OPEN, FileHandlers.openArgs(token, file.name, mime))
        // Every file a plugin is handed is listed in its activity (docs/plugin-api.md 4.6, Droidtop/tracker#61).
        runCatching {
            PluginAudit.forContext(context).append(
                id,
                AuditEntry(System.currentTimeMillis(), "provide:${FileHandlers.POINT}", FileHandlers.POINT, FileHandlers.OP_OPEN, file.name, emptyList(), reply.code?.name ?: "ok"),
            )
        }
        return if (reply.ok) null else "${record.manifest.label} couldn't open ${file.name}: ${reply.message ?: "it gave no reason"}"
    }
}
