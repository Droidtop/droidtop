package dev.droidtop.app.settings

import android.content.Context
import android.os.Build
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.runtime.tasks.PhantomProcesses
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Child processes" on Desktop's and Windows games' pages (docs/SPEC.md "Child processes and Android's limit",
 * Droidtop/tracker#816): how many droidtop runs against Android's limit, and, with Shizuku, the person's way to lift
 * the limit and to put it back. Absent before Android 12, which has no limit.
 */
internal object PhantomProcessRow {
    private const val PREFS = "droidtop_phantom_processes"
    private const val KEY_LIFTED = "lifted_by_droidtop"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether droidtop lifted the limit: only then does the row offer to put Android's back. */
    private fun liftedByDroidtop(context: Context): Boolean = prefs(context).getBoolean(KEY_LIFTED, false)

    suspend fun item(context: Context, id: String): CatalogItem? {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < PhantomProcesses.FIRST_SDK) return null
        val shell = TaskManager.shell
        val status = withContext(Dispatchers.IO) {
            PhantomProcesses.read(shell, sdk, android.os.Process.myUid(), context.packageName)
        }
        val lifted = withContext(Dispatchers.IO) { liftedByDroidtop(context) }
        val canChange = withContext(Dispatchers.IO) { runCatching { shell.capabilities().shellCommand }.getOrDefault(false) }
        val limitText = status.limit?.takeIf { it < PhantomProcesses.LIFTED }?.toString() ?: PhantomProcesses.DEFAULT_LIMIT.toString()
        val value = if (status.applies) "${status.ours} of $limitText" else "${status.ours}, no limit"

        if (!status.applies) {
            if (!lifted || !canChange) {
                return ActionItem(id = id, title = "Child processes", subtitle = "Android doesn't limit them on this device now", value = value)
            }
            return AsyncActionItem(
                id = id,
                title = "Child processes",
                subtitle = "droidtop lifted Android's limit. Press to put Android's own back",
                value = value,
                confirmTitle = "Put Android's limit back? Android may then end droidtop's programs when there are too many",
                run = { ctx, _ ->
                    val done = withContext(Dispatchers.IO) { PhantomProcesses.run(shell, PhantomProcesses.restoreCommands(sdk)) }
                    if (done) withContext(Dispatchers.IO) { prefs(ctx).edit().putBoolean(KEY_LIFTED, false).apply() }
                    if (done) "Android's limit is back" else "Couldn't put it back. Shizuku may have stopped"
                },
            )
        }
        val warning = if (status.over) "Over Android's limit: it may end some at any time. " else ""
        if (!canChange) {
            return ActionItem(
                id = id,
                title = "Child processes",
                subtitle = warning + if (sdk >= PhantomProcesses.FLAG_SDK) {
                    "Android ends extras. Developer options: Disable child process restrictions"
                } else {
                    "Android ends extras. With Shizuku, droidtop can lift that limit"
                },
                value = value,
            )
        }
        return AsyncActionItem(
            id = id,
            title = "Child processes",
            subtitle = warning + "Android ends extras without a word. Press to lift its limit",
            value = value,
            confirmTitle = "Lift Android's limit on child processes? Android stops ending desktop programs and games. " +
                "It applies to every app, and you can put it back here",
            run = { ctx, _ ->
                val done = withContext(Dispatchers.IO) { PhantomProcesses.run(shell, PhantomProcesses.liftCommands(sdk)) }
                if (done) withContext(Dispatchers.IO) { prefs(ctx).edit().putBoolean(KEY_LIFTED, true).apply() }
                if (done) "Lifted. Press again to put Android's back" else "Couldn't lift it. Shizuku may have stopped"
            },
        )
    }
}
