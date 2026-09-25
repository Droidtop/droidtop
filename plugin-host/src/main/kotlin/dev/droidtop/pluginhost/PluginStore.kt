package dev.droidtop.pluginhost

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * The install/uninstall/enable-disable/approve surface (docs/SPEC.md
 * 12a) -- droidtop's own install path now, not routed through
 * enginehost (that withdrawn text is gone from the spec). One plugin
 * lives at `filesDir/plugins/<id>/` (payload + `record.json`); nothing
 * here is synced, bundled or auto-downloaded -- a plugin arrives the way
 * an [dev.droidtop.library.integrations.Integration] file does: the user
 * picks it, or (future work) a catalog repo lists it and the user picks
 * from there.
 */
object PluginStore {
    fun root(context: Context): File = File(context.filesDir, "plugins")

    fun dataDirFor(context: Context, pluginId: String): File =
        File(root(context), "$pluginId/data").apply { mkdirs() }

    fun payloadDirFor(context: Context, pluginId: String): File = File(root(context), pluginId)

    /** Every installed plugin, valid or not, for the approval screen -- a plugin whose files fail re-verification is still listed, just shown as broken rather than silently vanishing. */
    fun installed(context: Context): List<PluginRecord> {
        val dir = root(context)
        val ids = dir.listFiles { f -> f.isDirectory }?.map { it.name } ?: return emptyList()
        return ids.mapNotNull { PluginBundleInstaller.readRecord(dir, it) }.sortedBy { it.manifest.label.lowercase() }
    }

    /** Installed, approved, enabled and re-verified plugins that declared [capability] -- what an actual call site (a status tile row, a metadata pass) should iterate. */
    fun runnableFor(context: Context, capability: PluginCapability): List<PluginRecord> =
        installed(context).filter { it.runnable() && capability in it.manifest.capabilities }
            .filter { PluginBundleInstaller.verifyInstalled(root(context), it) == null }

    /**
     * Installs a bundle the user picked via the system file picker (the
     * same "Add integration file"-shaped flow [dev.droidtop.library.integrations.IntegrationStore.import]
     * already uses). Returns a message for the approval screen to show.
     */
    fun importFromPicker(context: Context, uri: Uri): String {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return "Couldn't read that file"
        val tmp = File.createTempFile("plugin-import", ".droidplugin.tar.xz", context.cacheDir)
        return try {
            tmp.writeBytes(bytes)
            when (val result = PluginBundleInstaller.install(tmp, root(context))) {
                is PluginInstallResult.Installed ->
                    "Installed ${result.record.manifest.label} -- approve it below before it runs"
                is PluginInstallResult.Refused -> "Refused: ${result.error.reason}"
            }
        } finally {
            tmp.delete()
        }
    }

    /** The one action that moves a plugin from PENDING to APPROVED (or DENIED) -- the approval screen's confirm/decline buttons, and nothing else in the codebase may set this. */
    fun setApproval(context: Context, pluginId: String, approved: Boolean, grantRoot: Boolean) {
        val dir = root(context)
        val record = PluginBundleInstaller.readRecord(dir, pluginId) ?: return
        PluginBundleInstaller.writeRecord(
            dir,
            record.copy(
                trust = if (approved) PluginTrustState.APPROVED else PluginTrustState.DENIED,
                enabled = approved,
                // Root is only ever granted alongside approval, and only
                // when the plugin actually asked for it -- a user can't
                // grant root to a plugin that never declared requestsRoot
                // (there is no UI path that would even offer it), and
                // approving without checking that box leaves rootApproved
                // false even if the plugin wanted it.
                rootApproved = approved && grantRoot && record.manifest.requestsRoot,
                disabledReason = null,
            ),
        )
    }

    fun setEnabled(context: Context, pluginId: String, enabled: Boolean) {
        val dir = root(context)
        val record = PluginBundleInstaller.readRecord(dir, pluginId) ?: return
        PluginBundleInstaller.writeRecord(dir, record.copy(enabled = enabled, disabledReason = if (enabled) null else record.disabledReason))
    }

    /** [PluginCrashPolicy]'s write path -- the only other place [PluginRecord.disabledReason] gets set. */
    fun disableWithReason(context: Context, pluginId: String, reason: String) {
        val dir = root(context)
        val record = PluginBundleInstaller.readRecord(dir, pluginId) ?: return
        PluginBundleInstaller.writeRecord(dir, record.copy(enabled = false, disabledReason = reason))
    }

    fun uninstall(context: Context, pluginId: String) {
        File(root(context), pluginId).deleteRecursively()
    }
}
