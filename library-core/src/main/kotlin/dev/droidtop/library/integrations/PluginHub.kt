package dev.droidtop.library.integrations

import android.content.Context
import android.content.Intent
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.PluginShellHooks
import dev.droidtop.pluginhost.PluginEpoch
import dev.droidtop.pluginhost.PluginModes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where a plugin page opens outside Gaming and Settings (docs/plugin-api.md 1.9): Standard's home-screen menu and
 * Desktop's taskbar have no catalog navigator of their own, so they hand the page to
 * `:app`'s hub activity, which draws it with the same navigator, hint row and look as the Containers screen. The page
 * object stays in this process (a [CatalogScreen] holds code, not data); the intent carries a token for it.
 */
object PluginHub {
    const val ACTION = "dev.droidtop.app.action.PLUGIN_HUB"
    const val EXTRA_TOKEN = "dev.droidtop.app.extra.PLUGIN_HUB_TOKEN"

    private val screens = ConcurrentHashMap<String, CatalogScreen>()

    /** Opens [screen] in the hub, over whatever is in front. Main thread or not. */
    fun open(context: Context, screen: CatalogScreen) {
        val token = UUID.randomUUID().toString()
        screens[token] = screen
        val intent = Intent(ACTION)
            .setPackage(context.packageName)
            .putExtra(EXTRA_TOKEN, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** The page a hub activity was opened for; null when this process no longer has it (the hub then closes). */
    fun screen(token: String?): CatalogScreen? = token?.let { screens[it] }

    /** The hub activity is finishing: its page is no longer needed. */
    fun release(token: String?) {
        token?.let { screens.remove(it) }
    }
}

/**
 * The Standard launcher's plugin entry ([PluginShellHooks.Provider], docs/plugin-api.md 1.9): "Plugins" on the home
 * screen's menu while a plugin has a panel for Standard. Kept in memory because the launcher builds that menu on the
 * main thread, and worked out again off it whenever plugins or their grants change ([PluginEpoch]) or a minute passed.
 */
object PluginStandardHooks : PluginShellHooks.Provider {
    private class Snapshot(val epoch: Int, val atMs: Long, val panels: Boolean)

    private const val MAX_AGE_MS = 60_000L

    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var app: Context? = null
    private val refreshing = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun install(context: Context) {
        app = context.applicationContext
        PluginShellHooks.provider = this
        refresh()
    }

    private fun current(): Snapshot? {
        val s = snapshot
        if (s == null || s.epoch != PluginEpoch.current() || System.currentTimeMillis() - s.atMs > MAX_AGE_MS) refresh()
        return s
    }

    private fun refresh() {
        val context = app ?: return
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val epoch = PluginEpoch.current()
                snapshot = Snapshot(epoch, System.currentTimeMillis(), PluginPanels.panelsFor(context, PluginModes.STANDARD).isNotEmpty())
            } finally {
                refreshing.set(false)
            }
        }
    }

    override fun companionTabs(context: Context, mode: String): List<Pair<String, String>> =
        PluginPanels.companionTabs(context, mode)

    override fun homeMenu(context: Context): List<PluginShellHooks.MenuEntry> {
        if (current()?.panels != true) return emptyList()
        return listOf(
            PluginShellHooks.MenuEntry("Plugins") { ctx ->
                val appContext = ctx.applicationContext
                PluginHub.open(
                    ctx,
                    PluginPanels.quickMenuScreen(
                        game = null,
                        showManage = true,
                        onReplyScreen = { screen -> PluginHub.open(appContext, screen) },
                        surface = PluginModes.Surfaces.STANDARD_HOME,
                    ),
                )
            },
        )
    }
}
