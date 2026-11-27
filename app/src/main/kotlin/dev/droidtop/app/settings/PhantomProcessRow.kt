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
 * mytop-project/tracker#816): how many droidtop runs against Android's limit, with the device-setup item's lift and undo
 * ([DeviceSetup.childProcesses], the same as Settings > Device setup). Absent before Android 12, which has no limit.
 */
internal object PhantomProcessRow {
    suspend fun item(context: Context, id: String): CatalogItem? {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < PhantomProcesses.FIRST_SDK) return null
        val status = withContext(Dispatchers.IO) {
            PhantomProcesses.read(TaskManager.shell, sdk, android.os.Process.myUid(), context.packageName)
        }
        // Shown only while Android's limit applies to droidtop: lifting it, or putting it back, is More controls' device
        // setup row (Settings > System > More controls); here it is the in-place hint where the limit bites
        // (mytop-project/tracker#964 step 8).
        if (!status.applies) return null
        val limitText = status.limit?.takeIf { it < PhantomProcesses.LIFTED }?.toString() ?: PhantomProcesses.DEFAULT_LIMIT.toString()
        val value = when {
            status.applies -> "${status.ours} of $limitText"
            status.ours == 0 -> "No limit"
            else -> "${status.ours} running, no limit"
        }
        val noShell = if (sdk >= PhantomProcesses.FLAG_SDK) {
            "Android ends extras. Developer options: Disable child process restrictions"
        } else {
            "Android ends extras. More controls can lift that limit"
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
