package dev.droidtop.app.settings

import android.content.Context
import android.os.Build
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.runtime.tasks.DeviceSetup
import dev.droidtop.runtime.tasks.PhantomProcesses
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Child processes" on Desktop's and Windows games' pages (docs/SPEC.md "Child processes and Android's limit",
 * Droidtop/tracker#816): how many droidtop runs against Android's limit, with the device-setup item's lift and undo
 * ([DeviceSetup.childProcesses], the same as Settings > Device setup). Absent before Android 12, which has no limit.
 */
internal object PhantomProcessRow {
    suspend fun item(context: Context, id: String): CatalogItem? {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < PhantomProcesses.FIRST_SDK) return null
        val status = withContext(Dispatchers.IO) {
            PhantomProcesses.read(TaskManager.shell, sdk, android.os.Process.myUid(), context.packageName)
        }
        val limitText = status.limit?.takeIf { it < PhantomProcesses.LIFTED }?.toString() ?: PhantomProcesses.DEFAULT_LIMIT.toString()
        val value = if (status.applies) "${status.ours} of $limitText" else "${status.ours}, no limit"
        val noShell = if (sdk >= PhantomProcesses.FLAG_SDK) {
            "Android ends extras. Developer options: Disable child process restrictions"
        } else {
            "Android ends extras. Shizuku or wireless debugging can lift that limit"
        }
        val row = DeviceSetupRows.row(context, DeviceSetup.childProcesses, id, title = "Child processes", value = value, noShell = noShell)
        // Over the limit, the warning leads whatever the row offers.
        if (!status.over || row == null) return row
        val warning = "Over Android's limit: it may end some at any time"
        return when (row) {
            is AsyncActionItem -> AsyncActionItem(row.id, row.title, warning, row.value, row.confirmTitle, run = row.run)
            is ActionItem -> ActionItem(id = row.id, title = row.title, subtitle = "$warning. ${row.subtitle}", value = row.value)
            else -> row
        }
    }
}
