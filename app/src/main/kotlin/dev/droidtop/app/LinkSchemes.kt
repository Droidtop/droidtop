package dev.droidtop.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dev.droidtop.pluginhost.PluginEpoch
import dev.droidtop.pluginhost.PluginLinks
import dev.droidtop.pluginhost.PluginStore
import java.util.concurrent.Executors

/**
 * Switches droidtop's link-scheme aliases (docs/SPEC.md 12a "Links", Droidtop/tracker#459): the manifest declares
 * each scheme in [PluginLinks.MANIFEST_SCHEMES] as a disabled activity-alias of [LinkActivity]; an alias is enabled
 * while an installed, runnable plugin registers that scheme and disabled again when none does, so Android offers
 * droidtop for fdroidrepos:// only once something in droidtop opens it. Runs at process start and after every plugin
 * record or grant change ([PluginEpoch]), on one background thread; the package manager is asked only when an
 * alias's state has to change.
 */
object LinkSchemes {
    private const val TAG = "LinkSchemes"
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "LinkSchemes") }

    /** The alias class for each manifest scheme (app/src/main/AndroidManifest.xml). */
    private fun aliasFor(scheme: String): String = "dev.droidtop.app.links." + scheme.replaceFirstChar { it.uppercase() } + "Link"

    fun start(context: Context) {
        val app = context.applicationContext
        PluginEpoch.listen { worker.execute { sync(app) } }
        worker.execute { sync(app) }
    }

    private fun sync(context: Context) {
        runCatching {
            val wanted = PluginLinks.schemesWanted(PluginStore.installed(context))
            val pm = context.packageManager
            for (scheme in PluginLinks.MANIFEST_SCHEMES) {
                val component = ComponentName(context.packageName, aliasFor(scheme))
                val target = if (scheme in wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                val now = pm.getComponentEnabledSetting(component)
                val isOn = now == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                if (isOn == (target == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)) continue
                pm.setComponentEnabledSetting(component, target, PackageManager.DONT_KILL_APP)
                Log.i(TAG, "$scheme:// links ${if (scheme in wanted) "on" else "off"}")
            }
        }.onFailure { Log.w(TAG, "could not switch the link aliases", it) }
    }
}
