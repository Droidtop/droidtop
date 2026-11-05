package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import dev.droidtop.app.apps.ApkInstaller
import dev.droidtop.pluginhost.AppInterfaces
import dev.droidtop.pluginhost.ProviderHost

/**
 * Setting up the enhancement provider host (docs/SPEC.md "Enhancement providers", Droidtop/tracker#852): the one app
 * that may hold elevated access, so droidtop's own code holds none. droidtop signs the template it carries with its own
 * Keystore key ([ProviderHost.signed]) and installs it through the one installer ([ApkInstaller]). The first install is
 * the person's choice and Android asks once; later versions install over it without asking where Android allows that
 * (Android 12+, droidtop being its installer). Once installed it is added as an app plugin, waiting for the person's
 * approval like any other: installing the host grants nothing.
 *
 * Every call blocks on the Keystore, the disk and the installer: a worker thread only.
 */
object ProviderHostSetup {
    private const val TAG = "droidtop.providerhost"
    const val LABEL = "Provider host"

    /** How long [setUp] waits for Android's answer, the person's included. */
    private const val OUTCOME_WAIT_MS = 5 * 60_000L

    /** What [setUp] would do now, for the Settings row. */
    fun plan(context: Context): ProviderHost.Plan = ProviderHost.plan(context)

    /**
     * Installs or updates the host, then adds it as a plugin waiting for approval; returns the sentence for the person.
     * The person's own press: Android may ask them to confirm.
     */
    fun setUp(context: Context): String {
        val app = context.applicationContext
        return when (val plan = ProviderHost.plan(app)) {
            ProviderHost.Plan.NONE -> "This build of droidtop carries no provider host"
            ProviderHost.Plan.FOREIGN -> {
                removeForeign(app)
                NOT_OURS
            }
            ProviderHost.Plan.CURRENT -> addPlugin(app) ?: "The provider host is up to date"
            ProviderHost.Plan.INSTALL, ProviderHost.Plan.UPDATE -> install(app, plan, silent = false)
        }
    }

    /**
     * At droidtop's start, after its first screen (PluginHostStart): a newer host than the one installed goes in over it,
     * only where Android installs it without asking. Nothing is installed that was not installed before, and nothing asks.
     */
    fun updateQuietly(context: Context) {
        val app = context.applicationContext
        if (ProviderHost.plan(app) != ProviderHost.Plan.UPDATE) return
        if (Build.VERSION.SDK_INT < 31 || !ProviderHost.installedByDroidtop(app) || !ApkInstaller.silentUpdates(app)) return
        Log.i(TAG, "updating the provider host from ${ProviderHost.installedVersion(app)} to ${ProviderHost.templateVersion(app)}")
        Log.i(TAG, install(app, ProviderHost.Plan.UPDATE, silent = true))
    }

    private fun install(app: Context, plan: ProviderHost.Plan, silent: Boolean): String {
        if (!app.packageManager.canRequestPackageInstalls()) {
            return "Android has not allowed droidtop to install apps. In Settings > Apps > Special app access > " +
                "Install unknown apps > droidtop, turn on \"Allow from this source\", then try again"
        }
        val signed = runCatching { ProviderHost.signed(app) }.getOrElse {
            Log.w(TAG, "signing the provider host failed", it)
            return "droidtop couldn't sign its provider host: ${it.message ?: it::class.java.simpleName}"
        }
        return try {
            val session = ApkInstaller.commit(
                app, signed, ProviderHost.PACKAGE, LABEL,
                // An update of droidtop's own host may skip Android's confirmation; the first install never does.
                silent = silent || (plan == ProviderHost.Plan.UPDATE && ProviderHost.installedByDroidtop(app)),
                requestOwnership = plan == ProviderHost.Plan.INSTALL,
            )
            val outcome = ApkInstaller.awaitOutcome(session, OUTCOME_WAIT_MS)
                ?: return "Android is asking you to confirm installing the $LABEL"
            if (!outcome.startsWith("Installed")) return outcome
            addPlugin(app) ?: outcome
        } finally {
            ProviderHost.cleanUp(app)
        }
    }

    /**
     * Adds the installed host as a plugin, waiting for approval, or updates its record; null when it is already added in
     * this version. The verdict is droidtop's own check of the host's signature against its key.
     */
    private fun addPlugin(app: Context): String? {
        AppInterfaces.recheck(ProviderHost.PACKAGE)
        val found = AppInterfaces.inspect(app, ProviderHost.PACKAGE, ProviderHost.SERVICE)
        if (AppInterfaces.isAdded(app, found)) return null
        val record = AppInterfaces.install(app, found).getOrElse { return "The $LABEL is installed, but droidtop can't add it: ${it.message}" }
        return if (record.trust == dev.droidtop.pluginhost.PluginTrustState.APPROVED) {
            "The $LABEL is updated"
        } else {
            "The $LABEL is installed and waiting for your approval in Plugins"
        }
    }

    /** Android's own uninstall screen for a host droidtop did not sign: an update signed by droidtop's key would be refused. */
    private fun removeForeign(app: Context) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${ProviderHost.PACKAGE}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
    }

    private const val NOT_OURS = "A provider host droidtop didn't make is installed (droidtop was reinstalled, which deletes its key). " +
        "Remove it on Android's screen, then set up the provider host again"
}
