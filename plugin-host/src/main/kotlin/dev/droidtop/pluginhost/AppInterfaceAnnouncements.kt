package dev.droidtop.pluginhost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Apps that carry a droidtop plugin making themselves known (docs/plugin-api.md 1.3, Droidtop/tracker#547). The owner:
 * "Might be worth having every app with a droidtop Application Interface plugin layer make a call to announce its
 * presence to droidtop, self-registering", with droidtop also noticing installs on its own, since droidtop is not
 * always running. Three ways in, one decision ([consider]):
 *
 * - **The app announces itself** by knocking on the door before it is added ([AppInterfaceDoorService]): Android
 *   starts droidtop to answer, so this works whether droidtop runs or not. Who knocked is the caller's uid, never
 *   anything the app says.
 * - **droidtop sees an install or update** while it runs ([watch]).
 * - **droidtop catches up when it starts** on what changed while it did not run ([catchUp]), from Android's changed-
 *   package sequence.
 *
 * An announcement never adds or trusts anything: droidtop checks the app's signature itself, and the person is told
 * once per app version ([notify]) and adds it under Plugins, "In your apps", as before.
 */
object AppInterfaceAnnouncements {
    private const val TAG = "droidtop.plugin"
    private const val PREFS = "droidtop_app_interfaces"
    private const val KEY_SEQUENCE = "changed_sequence"
    private const val NOTICED_PREFIX = "noticed:"
    private const val ENGINE_CORES = "engines.cores"

    /** How long a knock from one app waits before another is looked at: an app cannot nag by knocking. */
    const val KNOCK_GAP_MS = 60_000L

    /** :app sets this at start: tells the person [found] can be added (its notification). Called off the main thread. */
    @Volatile var notify: (Context, AppInterfaces.Found) -> Unit = { _, _ -> }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "app-interfaces").apply { isDaemon = true } }
    private val lastKnock = ConcurrentHashMap<String, Long>()
    @Volatile private var watching = false

    /**
     * Whether the person should be told about an app now. [usable]: droidtop's check passed, or only wants the person
     * to trust its key; [added]: it is in Installed in this very version; [noticedAt]: the app version (its update
     * time) the person was last told about. Pure.
     */
    fun shouldNotice(usable: Boolean, added: Boolean, noticedAt: Long?, updatedAt: Long): Boolean =
        usable && !added && noticedAt != updatedAt

    /** Looks at [packageName] off the calling thread: an app plugin not yet added is told to the person once per version. */
    fun consider(context: Context, packageName: String) {
        val app = context.applicationContext ?: context
        worker.execute { runCatching { considerNow(app, packageName) }.onFailure { Log.w(TAG, "could not look at $packageName", it) } }
    }

    /** A knock from [packages] (the caller's uid) before any of them was added: looked at, at most once a minute each. */
    fun knocked(context: Context, packages: Collection<String>, nowMs: Long = System.currentTimeMillis()) {
        for (pkg in packages) {
            val last = lastKnock[pkg]
            if (last != null && nowMs - last < KNOCK_GAP_MS) continue
            lastKnock[pkg] = nowMs
            consider(context, pkg)
        }
    }

    private fun considerNow(context: Context, packageName: String) {
        if (packageName == context.packageName) return
        val pm = context.packageManager
        val service = runCatching { pm.queryIntentServices(Intent(AppInterfaceTrust.ACTION).setPackage(packageName), 0) }
            .getOrNull()?.firstOrNull()?.serviceInfo ?: return
        val updatedAt = runCatching { pm.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)
        // An app plugin already added that lists engine cores is asked for them again when its app changes
        // (Droidtop/tracker#854): an update can bring cores, and a list from before would offer "Get … support" for one
        // that is installed.
        PluginStore.installed(context)
            .filter { it.manifest.kind == PluginKind.APP_INTERFACE && it.manifest.app?.packageName == packageName }
            .filter { record -> record.manifest.v2.provides.any { it.point == ENGINE_CORES } }
            .forEach { PluginBrokers.enginesChanged(it.manifest.id) }
        val found = AppInterfaces.inspect(context, packageName, service.name)
        val usable = found.problem == null && found.verdict !is AppInterfaceTrust.Verdict.Refused
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val noticed = prefs.getLong(NOTICED_PREFIX + packageName, -1L).takeIf { it >= 0 }
        if (!shouldNotice(usable, AppInterfaces.isAdded(context, found), noticed, updatedAt)) return
        prefs.edit().putLong(NOTICED_PREFIX + packageName, updatedAt).apply()
        Log.i(TAG, "$packageName carries a droidtop plugin that is not added yet")
        notify(context, found)
    }

    /**
     * What changed while droidtop did not run: the packages Android says changed since the sequence droidtop last saw,
     * or, the first time, every app that carries a plugin. Off the main thread.
     */
    fun catchUp(context: Context) {
        val app = context.applicationContext ?: context
        worker.execute {
            runCatching {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val seen = prefs.getInt(KEY_SEQUENCE, -1)
                val current = app.packageManager.getChangedPackages(0)?.sequenceNumber ?: 0
                val packages = if (seen < 0 || current < seen) {
                    // The first time, or the sequence started again (it does on every reboot): every app is looked at.
                    AppInterfaces.discover(app).map { it.packageName }
                } else {
                    app.packageManager.getChangedPackages(seen)?.packageNames.orEmpty()
                }
                prefs.edit().putInt(KEY_SEQUENCE, current).apply()
                packages.forEach { considerNow(app, it) }
            }.onFailure { Log.w(TAG, "could not catch up on apps that carry a plugin", it) }
        }
    }

    /** Installs and updates while droidtop runs; Android delivers these only to a running app since Android 8. */
    fun watch(context: Context) {
        val app = context.applicationContext ?: context
        if (watching) return
        watching = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.data?.schemeSpecificPart?.let { consider(context, it) }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(receiver, filter)
    }
}
