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
 * picks it, or the catalog lists it and the user installs it from there
 * (docs/SPEC.md 12a "The catalog").
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
    fun runnableFor(context: Context, capability: PluginCapability): List<PluginRecord> {
        val dir = root(context)
        val userKeys = UserOriginKeys.loadBase64(UserOriginKeys.storeFile(context))
        return installed(context).filter { it.runnable() && capability in it.manifest.capabilities }
            .filter { PluginBundleInstaller.verifyInstalled(dir, it, userKeys) == null }
    }

    /**
     * Installs a bundle the user picked via the system file picker (the
     * same "Add integration file"-shaped flow [dev.droidtop.library.integrations.IntegrationStore.import]
     * already uses). Returns a message for the approval screen to show.
     * Verifies against the user-trusted keys too ("Keys you trust"),
     * so a bundle from an origin the user trusted installs here exactly
     * like an official one.
     */
    fun importFromPicker(context: Context, uri: Uri): String {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return "Couldn't read that file"
        val tmp = File.createTempFile("plugin-import", ".droidplugin.tar.xz", context.cacheDir)
        return try {
            tmp.writeBytes(bytes)
            val userKeys = UserOriginKeys.loadBase64(UserOriginKeys.storeFile(context))
            when (val result = PluginBundleInstaller.install(tmp, root(context), userKeys, hostPermissions = dev.droidtop.pluginhost.AndroidPermissions.heldBy(context))) {
                is PluginInstallResult.Installed ->
                    "Installed ${result.record.manifest.label} -- approve it below before it runs"
                is PluginInstallResult.Refused -> "Refused: ${result.error.reason}"
            }
        } finally {
            tmp.delete()
        }
    }

    /**
     * The one action that moves a plugin from PENDING to APPROVED (or DENIED) -- the approval screen's confirm/decline buttons, and nothing else in the codebase may set this.
     * Approving also writes the plugin's grants ([PluginGrants.initialiseOnApproval]): exactly the items in [ticked] are granted, and null [ticked] is the list as first shown.
     */
    fun setApproval(context: Context, pluginId: String, approved: Boolean, grantRoot: Boolean, ticked: Set<String>? = null) {
        val dir = root(context)
        val record = PluginBundleInstaller.readRecord(dir, pluginId) ?: return
        val updated = record.copy(
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
            )
        PluginBundleInstaller.writeRecord(dir, updated)
        if (approved) PluginGrants.forContext(context).initialiseOnApproval(updated, ticked)
    }

    fun setEnabled(context: Context, pluginId: String, enabled: Boolean) {
        val dir = root(context)
        val record = PluginBundleInstaller.readRecord(dir, pluginId) ?: return
        PluginBundleInstaller.writeRecord(dir, record.copy(enabled = enabled, disabledReason = if (enabled) null else record.disabledReason, disabledDetail = if (enabled) null else record.disabledDetail))
    }

    /** [PluginCrashPolicy]'s write path -- the only other place [PluginRecord.disabledReason] gets set. */
    fun disableWithReason(context: Context, pluginId: String, reason: String, detail: String? = null) {
        val dir = root(context)
        val record = PluginBundleInstaller.readRecord(dir, pluginId) ?: return
        PluginBundleInstaller.writeRecord(dir, record.copy(enabled = false, disabledReason = reason, disabledDetail = detail, disabledBuild = hostBuild(context)))
    }

    /** This droidtop's build number (its version code), or 0 when the system will not say. */
    @Suppress("DEPRECATION")
    fun hostBuild(context: Context): Int =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionCode }.getOrDefault(0)

    /**
     * Once per new droidtop build, on start: plugins an older droidtop disabled after a failure get another go
     * ([PluginRecord.afterHostUpdate]). Rig 2026-10-09: build 1535 disabled the sample status tile (its Kotlin classes
     * were shrunk away), build 1649 fixed that, and the plugin still read "Crashed" because nothing ever switched it
     * back on. Disk: off the main thread. Returns the ids it switched on.
     */
    fun retryAfterHostUpdate(context: Context): List<String> {
        val dir = root(context)
        val build = hostBuild(context)
        return installed(context).mapNotNull { record ->
            val again = record.afterHostUpdate(build) ?: return@mapNotNull null
            PluginBundleInstaller.writeRecord(dir, again)
            record.manifest.id
        }
    }

    /** The technical reason a plugin was last disabled for, or null. Reads the record: off the main thread. */
    fun disabledDetail(context: Context, pluginId: String): String? =
        PluginBundleInstaller.readRecord(root(context), pluginId)?.disabledDetail

    /** Removes the plugin, its data and its grants; its activity ring is kept 7 days and marked removed (docs/plugin-api.md 1.5, 4.6). */
    fun uninstall(context: Context, pluginId: String) {
        val audit = PluginAudit.forContext(context)
        audit.markRemoved(pluginId)
        audit.purgeExpired()
        PluginGrants.forContext(context).delete(pluginId)
        // Its secrets and their key go with it (docs/plugin-api.md 3 G1), and so do the files it was handed (D4).
        PluginVault.forContext(context).clear(pluginId)
        PluginFileTokens.forPluginsRoot(root(context)).delete(pluginId)
        PluginBrokers.forget(pluginId)
        ProviderLevels.forContext(context).forget(pluginId)
        File(root(context), pluginId).deleteRecursively()
        PluginEpoch.bump()
    }
}
