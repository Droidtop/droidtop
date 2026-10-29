package dev.droidtop.pluginhost

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The door (docs/plugin-api.md 1.3, Droidtop/tracker#547): the one thing droidtop exports for app interface plugins. An
 * app whose plugin is installed, approved, turned on and still verified knocks when it needs its broker and has none;
 * droidtop connects it as a call would ([PluginCrashPolicy.connectApp]). The caller is told only yes or no, and the
 * door carries no data: everything after it goes through the plugin's broker, under its grants.
 */
class AppInterfaceDoorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val door = object : IAppInterfaceDoor.Stub() {
        override fun knock(): Boolean {
            val uid = Binder.getCallingUid()
            val context: Context = applicationContext
            val identity = Binder.clearCallingIdentity()
            try {
                val record = recordFor(context, uid) ?: run {
                    // An app not added yet announces itself (Droidtop/tracker#547): droidtop looks at it and tells the
                    // person once per version. Who knocked is the caller's uid; the answer is still no.
                    AppInterfaceAnnouncements.knocked(context, context.packageManager.getPackagesForUid(uid)?.toList().orEmpty())
                    return false
                }
                // Checked again here, before saying yes: the app may have been re-signed since it was approved.
                if (AppInterfaces.verifyRecord(context, record) != null) return false
                scope.launch {
                    val policy = PluginCrashPolicy(context)
                    try {
                        when (val refusal = policy.connectApp(record)) {
                            null -> PluginBrokers.enginesChanged(record.manifest.id)
                            else -> Log.w("droidtop.plugin", "${record.manifest.id}: the door did not connect it: $refusal")
                        }
                    } finally {
                        policy.shutdown()
                    }
                }
                return true
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == AppInterfaceTrust.DOOR_ACTION) door else null

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    companion object {
        /**
         * The plugin of the app calling as [uid]: an app interface plugin whose app is one of the uid's packages, and
         * which may run (approved and turned on). Null for anyone else.
         */
        internal fun recordFor(context: Context, uid: Int): PluginRecord? {
            val packages = context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
            if (packages.isEmpty()) return null
            return PluginStore.installed(context).firstOrNull {
                it.manifest.kind == PluginKind.APP_INTERFACE && it.manifest.app?.packageName in packages && it.runnable()
            }
        }
    }
}
