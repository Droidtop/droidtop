package dev.droidtop.app.settings

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.words
import dev.droidtop.runtime.R
import dev.droidtop.runtime.tasks.DeviceSetup
import dev.droidtop.runtime.tasks.DeviceSetupItem
import dev.droidtop.runtime.tasks.SetupState
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The device-setup items as Settings rows (docs/SPEC.md "Device setup", Droidtop/tracker#816): each asks first and
 * runs on the one privileged shell, and one droidtop made offers its undo, asked the same way. Reads the device
 * through the shell: build off the main thread.
 */
internal object DeviceSetupRows {
    /** The "Device setup" group under Accounts and sources, every item this Android has. */
    suspend fun group(context: Context): CatalogGroup? {
        val rows = DeviceSetup.items.mapNotNull { row(context, it, "device_setup_${it.id}") }
        if (rows.isEmpty()) return null
        return CatalogGroup(id = "accounts_device_setup", title = words(R.string.device_setup_group), items = rows)
    }

    /**
     * [item] as one row with [id]; [title], [value] and [noShell] let a page that shows the same setting in its own
     * words (Child processes on Desktop's page) keep them. Null when this Android has nothing to change.
     */
    suspend fun row(
        context: Context,
        item: DeviceSetupItem,
        id: String,
        title: String = words(item.title),
        value: String? = null,
        noShell: String = words(DeviceSetup.NO_SHELL),
    ): CatalogItem? {
        val shell = TaskManager.shell
        val (state, canRun) = withContext(Dispatchers.IO) { item.state(context, shell) to DeviceSetup.canRun(shell) }
        return when (state) {
            SetupState.NOT_NEEDED -> null
            SetupState.DONE -> ActionItem(id = id, title = title, subtitle = words(item.line(state)), value = value)
            SetupState.TO_DO, SetupState.DONE_BY_DROIDTOP -> {
                if (!canRun) return ActionItem(id = id, title = title, subtitle = noShell, value = value)
                val undoing = state == SetupState.DONE_BY_DROIDTOP
                AsyncActionItem(
                    id = id,
                    title = title,
                    subtitle = words(item.line(state)),
                    value = value,
                    confirmTitle = words(if (undoing) item.undoAsk else item.ask),
                    run = { ctx, _ ->
                        val done = withContext(Dispatchers.IO) { if (undoing) item.undo(ctx, shell) else item.apply(ctx, shell) }
                        when {
                            !done -> words(R.string.device_setup_failed)
                            undoing -> words(R.string.device_setup_undone)
                            else -> words(R.string.device_setup_done)
                        }
                    },
                )
            }
        }
    }
}
