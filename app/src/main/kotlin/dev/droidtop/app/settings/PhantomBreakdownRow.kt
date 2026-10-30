package dev.droidtop.app.settings

import android.content.Context
import android.os.Build
import android.util.Log
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.runtime.tasks.PhantomProcesses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "What they are" under Child processes (docs/SPEC.md "Child processes and Android's limit", Droidtop/tracker#816,
 * "measure and cut"): droidtop's child processes by program, most first, so a Desktop session's or a Wine game's count
 * can be read on the device and the biggest of droidtop's own helpers cut. The whole list is logged under
 * `droidtop.ChildProcesses` each time the page opens. Absent before Android 12 and with none running.
 */
internal object PhantomBreakdownRow {
    suspend fun item(context: Context, id: String): CatalogItem? {
        if (Build.VERSION.SDK_INT < PhantomProcesses.FIRST_SDK) return null
        val breakdown = withContext(Dispatchers.IO) {
            PhantomProcesses.breakdown(android.os.Process.myUid(), context.packageName)
        }
        if (breakdown.isEmpty()) return null
        Log.i("droidtop.ChildProcesses", breakdown.joinToString(", ") { (name, count) -> "$name $count" })
        return ActionItem(id = id, title = "What they are", subtitle = PhantomProcesses.describe(breakdown))
    }
}
