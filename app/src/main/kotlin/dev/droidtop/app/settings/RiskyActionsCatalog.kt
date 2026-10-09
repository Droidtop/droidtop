package dev.droidtop.app.settings

import android.content.Context
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.runtime.tasks.RiskyActions
import dev.droidtop.runtime.tasks.RiskyClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings > Risky actions (docs/SPEC.md "Risky actions", Droidtop/tracker#248): the one place that says whether
 * droidtop may change another app or the system through the privileged helper. A master switch, off until the person
 * turns it on, and one switch per class of action, each off until turned on; a class does nothing while the master is
 * off. The gate itself is [dev.droidtop.runtime.tasks.ElevatedShell] (and the RetroArch core placement): this screen
 * only holds the switches. Even with a class on, every use asks for a confirmation that names the app and the exact
 * permission or file.
 */
object RiskyActionsCatalog {
    const val SCREEN_ID = "risky_actions"

    fun screen() = CatalogScreen(
        id = SCREEN_ID,
        title = "Risky actions",
        subtitle = "What droidtop may change in other apps for you. Everything here is off until you turn it on",
        groups = { context -> groups(context) },
    )

    /** The row that opens this screen; its value says whether anything is on. */
    fun linkRow(id: String) = NestedScreenItem(
        id = id,
        title = "Risky actions",
        subtitle = "Let droidtop give emulators access or write their files, instead of you doing it by hand",
        registryId = SCREEN_ID,
        valueLabel = { if (RiskyActions.masterOn()) "On" else "Off" },
        icon = CatalogIcon.ANDROID_SETTINGS,
    )

    private suspend fun groups(context: Context): List<CatalogGroup> {
        val switches = withContext(Dispatchers.IO) { RiskyActions.get(context) }
        val master = CatalogGroup(
            id = "risky_master",
            title = null,
            items = listOf(
                ToggleItem(
                    id = "risky_master_switch",
                    title = "Allow risky actions",
                    subtitle = "The master switch. While it is off, none of the actions below can run, whatever their own switch says",
                    current = switches.master,
                    onToggle = { ctx, on -> withContext(Dispatchers.IO) { RiskyActions.setMaster(ctx, on) } },
                ),
            ),
        )
        val classes = CatalogGroup(
            id = "risky_classes",
            title = "Kinds of action",
            items = RiskyClass.entries.map { risk ->
                ToggleItem(
                    id = "risky_class_${risk.id}",
                    title = risk.title,
                    subtitle = risk.summary + if (switches.master) "" else ". Needs Allow risky actions first",
                    current = risk in switches.classes,
                    onToggle = { ctx, on -> withContext(Dispatchers.IO) { RiskyActions.setClass(ctx, risk, on) } },
                )
            },
        )
        return listOf(master, classes)
    }
}
