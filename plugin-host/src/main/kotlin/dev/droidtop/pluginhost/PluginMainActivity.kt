package dev.droidtop.pluginhost

import android.content.Context
import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor

/**
 * Hosts a plugin's `ui.main` ([PluginMainUi]) full-screen, in `:pluginhost`,
 * the process the plugin already runs in. It is an ordinary `FlutterActivity`
 * over an engine [FlutterEngineHost] built from the plugin's own payload
 * (the process-wide `FlutterInjector` is never initialised here, so the
 * engine is handed to the activity through [provideFlutterEngine], and the
 * entrypoint is started before that so the activity does not look for one).
 * That gives the plugin UI Flutter's own handling of the keyboard, the
 * controller keys, text input and Back: Back pops the plugin's routes and,
 * when none are left, finishes this activity, which returns to droidtop
 * because the activity sits in droidtop's task.
 *
 * Not exported: only droidtop starts it, and only [PluginMainUi.open], which
 * has already checked approval, the per-item grant and the payload's
 * signature. The engine is destroyed with the activity.
 */
class PluginMainActivity : FlutterActivity() {
    override fun provideFlutterEngine(context: Context): FlutterEngine? {
        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID) ?: throw IllegalStateException("no plugin id")
        val function = intent.getStringExtra(EXTRA_ENTRYPOINT) ?: throw IllegalStateException("no entrypoint")
        val library = intent.getStringExtra(EXTRA_LIBRARY)
        val built = FlutterEngineHost.build(applicationContext, pluginId, FlutterSource.installed(applicationContext, pluginId, PluginStore.payloadDirFor(applicationContext, pluginId)))
        val entrypoint = if (library.isNullOrBlank()) {
            DartExecutor.DartEntrypoint(built.appBundlePath, function)
        } else {
            DartExecutor.DartEntrypoint(built.appBundlePath, library, function)
        }
        built.engine.dartExecutor.executeDartEntrypoint(entrypoint)
        return built.engine
    }

    /** The plugin's generated plugins are registered by [FlutterEngineHost.build] with the plugin's own classloader; the default would look on this one and find none. */
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) = Unit

    companion object {
        private const val EXTRA_PLUGIN_ID = "dev.droidtop.pluginhost.PLUGIN_ID"
        private const val EXTRA_ENTRYPOINT = "dev.droidtop.pluginhost.ENTRYPOINT"
        private const val EXTRA_LIBRARY = "dev.droidtop.pluginhost.LIBRARY"

        fun intentFor(context: Context, pluginId: String, entry: PluginMainUi.Entry): Intent =
            Intent(context, PluginMainActivity::class.java)
                .putExtra(EXTRA_PLUGIN_ID, pluginId)
                .putExtra(EXTRA_ENTRYPOINT, entry.entrypoint)
                .putExtra(EXTRA_LIBRARY, entry.library)
    }
}
