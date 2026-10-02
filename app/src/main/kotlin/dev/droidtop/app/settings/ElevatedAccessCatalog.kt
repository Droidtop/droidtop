package dev.droidtop.app.settings

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.pluginhost.ElevatedAccessHost
import dev.droidtop.runtime.tasks.BackendState
import dev.droidtop.runtime.tasks.ElevatedChoice
import dev.droidtop.runtime.tasks.ElevatedChoicePrefs

/**
 * The row that picks where elevated actions come from: Auto, the Shizuku app, the Shizuku plugin or Off
 * (docs/SPEC.md "The task manager"). It offers only what is there and is not drawn at all when neither backend
 * is, so no privileged control has a reason to appear. While the Shizuku app is running but has not allowed droidtop,
 * a second row asks it to. Reads binder state: build it off the main thread.
 */
object ElevatedAccessCatalog {
    fun rows(context: Context): List<CatalogItem> = listOfNotNull(choiceRow(context), allowRow())

    private fun allowRow(): CatalogItem? {
        if (ElevatedAccessHost.shizukuAppState() != BackendState.NEEDS_PERMISSION) return null
        return ActionItem(
            id = "accounts_elevated_allow",
            title = "Allow droidtop in Shizuku",
            run = { ElevatedAccessHost.requestShizukuPermission() },
        )
    }

    private fun choiceRow(context: Context): CatalogItem? {
        val shell = ElevatedAccessHost.shell ?: return null
        val offered = shell.options()
        if (offered.isEmpty()) return null
        val active = shell.active()
        val current = ElevatedChoicePrefs.get(context)
        // A stored pick whose backend has since gone stays visible, so the row never shows a raw id.
        val shown = if (current in offered) offered else offered + current
        return ChoiceItem(
            id = "accounts_elevated_access",
            title = "Elevated access",
            options = shown.map { ChoiceOption(it.id, label(it, active?.label)) },
            current = current.id,
            onSelect = { ctx, value -> ElevatedAccessHost.setChoice(ctx, ElevatedChoice.fromId(value)) },
        )
    }

    private fun label(choice: ElevatedChoice, activeLabel: String?): String = when (choice) {
        ElevatedChoice.AUTO -> if (activeLabel == null) choice.label else "${choice.label} ($activeLabel)"
        else -> choice.label
    }
}
