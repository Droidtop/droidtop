package dev.droidtop.pluginhost

import android.app.ActivityManager
import android.content.Context
import android.os.Binder
import android.os.Process

/**
 * Who is calling one of droidtop's own binders, components or providers (docs/plugin-api.md 5.6, "Every path but the
 * API", Droidtop/tracker#53). An open or full-access plugin runs as droidtop's own UID, so Android lets it reach
 * droidtop's non-exported services and providers; the UID cannot tell the two apart, the process can. A process's name
 * comes from the system's own record of the processes it started ([ActivityManager.getRunningAppProcesses]), never from
 * the process itself, which could rewrite its own command line.
 *
 * The names are the manifests': droidtop itself is the package name with no suffix; full access runs in
 * `:pluginhost` and `:pluginhost1` to `:pluginhost7`, open plugins in `:plugin_open0` to `:plugin_open7`, contained ones
 * in `:plugin_sandbox*` under an isolated UID of their own.
 */
object DroidtopCallers {
    private const val PLUGIN_SUFFIXES = "pluginhost|plugin_open|plugin_sandbox"
    private val pluginSuffix = Regex(":($PLUGIN_SUFFIXES)\\d*")

    /** True when [processName] is one of droidtop's plugin processes. */
    fun isPluginProcess(packageName: String, processName: String): Boolean =
        processName.startsWith("$packageName:") && pluginSuffix.matches(processName.removePrefix(packageName))

    /** True when [processName] is droidtop's own main process (`:app`, the package name alone). */
    fun isAppProcess(packageName: String, processName: String): Boolean = processName == packageName

    /**
     * Android's isolated UIDs (`android.os.Process` FIRST_ISOLATED_UID 99000 to LAST_ISOLATED_UID 99999, and the app
     * zygotes' 90000 to 98999), in any user: a contained plugin's.
     */
    fun isIsolatedUid(uid: Int): Boolean = (uid % 100_000) in 90_000..99_999

    /** The name the system gave process [pid] of this UID, or null when it is not one of this UID's running processes. */
    fun processName(context: Context, pid: Int): String? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching { manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName }.getOrNull()
    }

    /**
     * True when the binder call being served comes from droidtop's own main process. Only that process drives plugin
     * processes; a plugin process of droidtop's UID asking is refused. Answers false outside a binder call from another
     * process, so a missing caller is never taken for droidtop.
     */
    fun callerIsApp(context: Context): Boolean {
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        if (uid != context.applicationInfo.uid || pid == Process.myPid()) return false
        return processName(context, pid)?.let { isAppProcess(context.packageName, it) } == true
    }

    /**
     * True when the call being served comes from a plugin: an isolated UID, or a process of droidtop's own UID that is
     * one of its plugin processes. Another app (a grant through a content URI) and droidtop's own processes are not.
     * A caller of droidtop's UID whose process the system does not list is treated as a plugin, never trusted.
     */
    fun callerIsPlugin(context: Context): Boolean {
        val uid = Binder.getCallingUid()
        if (isIsolatedUid(uid)) return true
        if (uid != context.applicationInfo.uid) return false
        val pid = Binder.getCallingPid()
        if (pid == Process.myPid()) return isPluginProcess(context.packageName, currentProcessName(context))
        val name = processName(context, pid) ?: return true
        return isPluginProcess(context.packageName, name)
    }

    /**
     * True when the caller may have Shizuku's binder: another app (the Shizuku server delivering it), droidtop itself,
     * or a full-access plugin process (`:pluginhost*`). Never an open or contained plugin (the OS rule, 5.6).
     */
    fun callerMayUseShizuku(context: Context): Boolean {
        val uid = Binder.getCallingUid()
        if (isIsolatedUid(uid)) return false
        if (uid != context.applicationInfo.uid) return true
        val pid = Binder.getCallingPid()
        val name = if (pid == Process.myPid()) currentProcessName(context) else processName(context, pid) ?: return false
        return mayUseShizuku(context.packageName, name)
    }

    /** The process names of droidtop's UID that may use Shizuku: droidtop itself and full access. Pure, for the test. */
    fun mayUseShizuku(packageName: String, processName: String): Boolean =
        isAppProcess(packageName, processName) || Regex(":pluginhost\\d*").matches(processName.removePrefix(packageName))

    /** This process's own name, from the system's record. */
    fun currentProcessName(context: Context): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else processName(context, Process.myPid()) ?: context.packageName
}

/**
 * The one caller a plugin process serves: droidtop's main process (docs/plugin-api.md 5.6). A call must come from
 * droidtop's UID ([appUid]); its pid is then droidtop's when the system says so ([verify], asked once and remembered).
 * An [isolated] process cannot ask the system about other processes, so it keeps the pid of the first caller of
 * droidtop's UID: droidtop, which starts the process by binding it. Pure apart from [verify], so it is unit-tested.
 */
internal class AppCaller(val isolated: Boolean, private val appUid: Int, private val verify: (Int) -> Boolean) {
    private var pid = 0

    @Synchronized
    fun allows(callingUid: Int, callingPid: Int): Boolean {
        if (callingUid != appUid || callingPid <= 0) return false
        if (callingPid == pid) return true
        if (isolated) {
            if (pid != 0) return false
            pid = callingPid
            return true
        }
        if (!verify(callingPid)) return false
        pid = callingPid
        return true
    }

    /** Droidtop's process [deadPid] died: the next caller is asked about again. */
    @Synchronized
    fun forget(deadPid: Int) {
        if (pid == deadPid) pid = 0
    }
}

/**
 * Whose reports a plugin process's callback believes (docs/plugin-api.md 5.6): a report about a plugin counts only when
 * it comes from the process that plugin runs in ([owner], the process the runner loaded it on). Pure, so it is
 * unit-tested; the processes are compared by identity, as binders are.
 */
internal object PluginProcessOwnership {
    fun reports(owner: Any?, process: Any): Boolean = owner != null && owner === process
}
