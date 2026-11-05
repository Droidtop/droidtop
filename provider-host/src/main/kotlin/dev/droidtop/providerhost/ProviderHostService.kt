package dev.droidtop.providerhost

import android.os.Build
import android.os.ParcelFileDescriptor
import dev.droidtop.pluginhost.IPluginHostBroker
import dev.droidtop.pluginhost.PluginManifest
import dev.droidtop.pluginhost.PluginProcessService
import java.io.File

/**
 * The enhancement provider host's one service (docs/SPEC.md "Enhancement providers", Droidtop/tracker#852): droidtop
 * binds it as an app interface plugin (docs/plugin-api.md 1.3) and drives it with the same [dev.droidtop.pluginhost.IPluginRuntime]
 * as any plugin process. It answers droidtop alone: the caller must be droidtop's user id, droidtop must be the app that
 * installed this host, and the first droidtop process to bind it is the only one it serves until every binding is gone.
 */
class ProviderHostService : PluginProcessService() {
    override val driverIsRemote: Boolean get() = true

    override fun driverUid(): Int = droidtopUid(this) ?: NO_UID

    override fun loadFromFolder(pluginId: String, dir: File, entryClass: String, rootApproved: Boolean, broker: IPluginHostBroker): Boolean =
        failLoad(pluginId, "the provider host loads only what droidtop hands it")

    override fun loadFromFiles(pluginId: String, manifest: PluginManifest, files: Map<String, ParcelFileDescriptor>, broker: IPluginHostBroker): Boolean {
        if (pluginId != packageName) return failLoad(pluginId, "the provider host does not run $pluginId")
        return start(pluginId, HostPlugin(this), brokerContext(broker))
    }

    companion object {
        /** droidtop's package: the only app that drives the host. */
        const val DROIDTOP = "dev.droidtop.app"

        /** No user id is this: a host droidtop did not install answers nobody. */
        private const val NO_UID = -1

        /**
         * droidtop's user id, when droidtop is installed and is the app that installed this host (its installer of record);
         * null otherwise. A host anyone else installed serves no one.
         */
        fun droidtopUid(context: android.content.Context): Int? {
            val pm = context.packageManager
            val installer = runCatching {
                if (Build.VERSION.SDK_INT >= 30) {
                    pm.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(context.packageName)
                }
            }.getOrNull()
            if (installer != DROIDTOP) return null
            @Suppress("DEPRECATION")
            return runCatching { pm.getApplicationInfo(DROIDTOP, 0).uid }.getOrNull()
        }
    }
}
