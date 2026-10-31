package dev.droidtop.pluginhost

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * The install/uninstall/enable-disable/approve surface (docs/SPEC.md
 * 12a) -- droidtop's own install path now, not routed through
 * enginehost (that withdrawn text is gone from the spec). One plugin
 * lives at `filesDir/plugins/<id>/`: its `record.json`, its data, and
 * each installed version read-only under `versions/<digest>/`, the
 * record naming the one that runs (12a, "Trust over updates"); nothing
 * here is synced, bundled or auto-downloaded -- a plugin arrives the way
 * an [dev.droidtop.library.integrations.Integration] file does: the user
 * picks it, or the catalog lists it and the user installs it from there
 * (docs/SPEC.md 12a "The catalog").
 */
object PluginStore {
    fun root(context: Context): File {
        AppInterfaces.attach(context)
        return File(context.filesDir, "plugins")
    }

    fun dataDirFor(context: Context, pluginId: String): File =
        File(root(context), "$pluginId/data").apply { mkdirs() }

    /** The folder [record]'s version runs from ([PluginBundleInstaller.installedDir]); what it was verified against. */
    fun payloadDirFor(context: Context, record: PluginRecord): File = PluginBundleInstaller.installedDir(root(context), record)

    /** The folder the plugin's current version runs from. Reads its record: prefer the overload taking the record. */
    fun payloadDirFor(context: Context, pluginId: String): File =
        PluginBundleInstaller.readRecord(root(context), pluginId)?.let { payloadDirFor(context, it) } ?: File(root(context), pluginId)

    /**
     * The plugin's own icon (its presentation's `icon`) as a path in its payload; null when it declares none. One
     * answer for the plugin's card, its page, its catalog row and its panel's title (Droidtop/tracker#513, #515).
     * It reads nothing, so any thread may ask: the manifest keeps only a .png or .webp among its signed payload files
     * (PluginPresentation.fromJson), a path that climbs out of the payload is refused here, and every renderer loads
     * the file off the main thread and draws nothing when it is missing.
     */
    fun iconFile(context: Context, record: PluginRecord): String? {
        val path = record.manifest.presentation.icon ?: return null
        if (path.startsWith("/") || path.split('/', '\\').any { it == ".." }) return null
        return File(payloadDirFor(context, record), path).path
    }

    /** Every installed plugin, valid or not, for the approval screen -- a plugin whose files fail re-verification is still listed, just shown as broken rather than silently vanishing. */
    fun installed(context: Context): List<PluginRecord> {
        val dir = root(context)
        val ids = dir.listFiles { f -> f.isDirectory }?.map { it.name } ?: return emptyList()
        return ids.mapNotNull { PluginBundleInstaller.readRecord(dir, it) }.sortedBy { it.manifest.label.lowercase() }
    }

    /** A plugin whose record this droidtop cannot read: its id, and the name its record gives (the id when none). */
    data class Unreadable(val id: String, val label: String)

    /**
     * The plugins [installed] leaves out because their record does not read (Droidtop/tracker#854): an app's plugin
     * made for another droidtop, a kind or shape this build does not know. Listed, never dropped silently, so the
     * person sees it and can remove it. Reads every record: off the main thread.
     */
    fun unreadable(context: Context): List<Unreadable> {
        val dir = root(context)
        val ids = dir.listFiles { f -> f.isDirectory }?.map { it.name } ?: return emptyList()
        return ids.mapNotNull { id ->
            val file = File(dir, "$id/record.json")
            if (!file.isFile || PluginBundleInstaller.readRecord(dir, id) != null) return@mapNotNull null
            val label = runCatching { org.json.JSONObject(file.readText()).optString("label") }.getOrNull()?.takeIf { it.isNotBlank() }
            Unreadable(id, label ?: id)
        }.sortedBy { it.label.lowercase() }
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
        val tmp = File.createTempFile("plugin-import", ".droidplugin.tar.xz", context.cacheDir)
        return try {
            // Copied to disk, never read whole into the heap (Droidtop/tracker#545).
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } != null
            }.getOrDefault(false)
            if (!copied) return "Couldn't read that file"
            val userKeys = UserOriginKeys.loadBase64(UserOriginKeys.storeFile(context))
            when (val result = PluginBundleInstaller.install(tmp, root(context), userKeys, hostPermissions = dev.droidtop.pluginhost.AndroidPermissions.heldBy(context), succeeds = dev.droidtop.pluginhost.UserOriginKeys.successorCheck(context))) {
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
                replacedKeySha256 = null,
                replacedOrigin = null,
            )
        PluginBundleInstaller.writeRecord(dir, updated)
        runCatching { PluginProcesses.retire(pluginId) }
        if (approved) PluginGrants.forContext(context).initialiseOnApproval(updated, ticked)
    }

    /**
     * After the person confirmed [origin]'s new key in place of its old one ([UserOriginKeys.replace]): every plugin of
     * that origin an update had sent back to waiting only because it was signed by the new key, approved before under
     * the key it replaced, is approved again with the grants it had (new items wait at Ask first, as in any update), so
     * a publisher's key change is one decision, not one per plugin (Droidtop/tracker#270). Root, which needs its own
     * yes, is not restored. Returns how many came back.
     */
    fun keyReplaced(context: Context, origin: String): Int {
        val dir = root(context)
        val keys = UserOriginKeys.storeFile(context)
        var restored = 0
        for (record in installed(context)) {
            if (record.manifest.origin != origin || record.trust != PluginTrustState.PENDING) continue
            val replaced = record.replacedKeySha256 ?: continue
            if (record.replacedOrigin != null && record.replacedOrigin != origin) continue
            if (!UserOriginKeys.succeeds(keys, origin, replaced, record.approvedKeySha256)) continue
            val back = record.copy(trust = PluginTrustState.APPROVED, enabled = true, disabledReason = null, replacedKeySha256 = null, replacedOrigin = null)
            PluginBundleInstaller.writeRecord(dir, back)
            // The grants file still holds what the person answered for this plugin; kept as an update keeps it.
            PluginGrants.forContext(context).applyUpdate(back, back)
            runCatching { PluginProcesses.retire(record.manifest.id) }
            restored++
        }
        return restored
    }

    fun setEnabled(context: Context, pluginId: String, enabled: Boolean) {
        val dir = root(context)
        val record = PluginBundleInstaller.readRecord(dir, pluginId) ?: return
        PluginBundleInstaller.writeRecord(dir, record.copy(enabled = enabled, disabledReason = if (enabled) null else record.disabledReason, disabledDetail = if (enabled) null else record.disabledDetail))
        // A plugin that is off records nothing the companion should still show.
        if (!enabled) PluginRecording.clear(pluginId)
    }

    /** [PluginCrashPolicy]'s write path -- the only other place [PluginRecord.disabledReason] gets set. */
    fun disableWithReason(context: Context, pluginId: String, reason: String, detail: String? = null) {
        val dir = root(context)
        val record = PluginBundleInstaller.readRecord(dir, pluginId) ?: return
        PluginBundleInstaller.writeRecord(dir, record.copy(enabled = false, disabledReason = reason, disabledDetail = detail, disabledBuild = hostBuild(context)))
        PluginRecording.clear(pluginId)
    }

    /** This droidtop's build number (its version code), or 0 when the system will not say. */
    @Suppress("DEPRECATION")
    fun hostBuild(context: Context): Int =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionCode }.getOrDefault(0)

    /**
     * Once per new droidtop build, on start: plugins an older droidtop disabled after a failure get another go
     * ([PluginRecord.afterHostUpdate]). Rig 2026-10-09: build 1535 disabled a status tile plugin (its Kotlin classes
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

    /**
     * Plugins an earlier droidtop disabled because their files did not match their record, which now verify: switched
     * back on. Before installs were atomic, a check during an update could read the new manifest against the old record
     * and disable a plugin that was fine (12a, "Trust over updates"). A plugin whose files match its signed hashes is
     * the plugin that was approved, so nothing is lost by trusting it again. Disk: off the main thread.
     */
    fun recoverVerified(context: Context): List<String> {
        val dir = root(context)
        val userKeys = UserOriginKeys.loadBase64(UserOriginKeys.storeFile(context))
        return installed(context).mapNotNull { record ->
            if (!record.recoverableOnceVerified()) return@mapNotNull null
            if (PluginBundleInstaller.verifyInstalled(dir, record, userKeys) != null) return@mapNotNull null
            val current = PluginBundleInstaller.readRecord(dir, record.manifest.id) ?: return@mapNotNull null
            if (current != record) return@mapNotNull null
            PluginBundleInstaller.writeRecord(dir, record.copy(enabled = true, disabledReason = null, disabledDetail = null, disabledBuild = 0))
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
        PluginRecording.clear(pluginId)
        ProviderLevels.forContext(context).forget(pluginId)
        // What it announced to the paired devices is withdrawn there too (docs/plugin-api.md D21): network, so on its own thread.
        val appContext = context.applicationContext
        Thread({ runCatching { dev.droidtop.net.peer.PairedDevices.forget(appContext, pluginId) } }, "devices-forget").start()
        File(root(context), pluginId).deleteRecursively()
        runCatching { PluginProcesses.retire(pluginId) }
        // An app's plugin: droidtop lets the app go now. The app itself stays installed (Droidtop/tracker#547).
        runCatching { AppInterfaceConnections.forget(context, pluginId) }
        PluginEpoch.bump()
    }
}
