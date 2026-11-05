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
 * The row that picks where elevated actions come from: Auto, the Shizuku app, the Shizuku plugin, wireless debugging or Off
 * (docs/SPEC.md "The task manager"). It offers only what is there and is not drawn at all when neither backend
 * is, so no privileged control has a reason to appear. While the Shizuku app is running but has not allowed droidtop,
 * a second row asks it to. Reads binder state: build it off the main thread.
 */
object ElevatedAccessCatalog {
    fun rows(context: Context): List<CatalogItem> = listOfNotNull(hostRow(context), choiceRow(context), allowRow(), wirelessRow(context))

    /**
     * The enhancement provider host (docs/SPEC.md "Enhancement providers", Droidtop/tracker#852): set it up, update it, or
     * replace one droidtop did not make. Once it is current the row says so; its plugin page is where it is approved.
     */
    private fun hostRow(context: Context): CatalogItem? {
        val (title, subtitle) = when (dev.droidtop.app.ProviderHostSetup.plan(context)) {
            dev.droidtop.pluginhost.ProviderHost.Plan.NONE -> return null
            dev.droidtop.pluginhost.ProviderHost.Plan.INSTALL -> "Set up the provider host" to
                "An app droidtop makes for itself to hold root, Shizuku and wireless debugging, so droidtop holds none. Android asks you once"
            dev.droidtop.pluginhost.ProviderHost.Plan.UPDATE -> "Update the provider host" to "A newer provider host comes with this droidtop"
            dev.droidtop.pluginhost.ProviderHost.Plan.FOREIGN -> "Replace the provider host" to
                "The one installed wasn't made by this droidtop. Remove it, then set it up again"
            dev.droidtop.pluginhost.ProviderHost.Plan.CURRENT -> "Provider host" to "Installed and up to date. Approve it and choose what it may do in Plugins"
        }
        return dev.droidtop.library.settings.AsyncActionItem(
            id = "accounts_provider_host",
            title = title,
            subtitle = subtitle,
            run = { ctx, _ -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.droidtop.app.ProviderHostSetup.setUp(ctx) } },
        )
    }

    /**
     * Wireless debugging, the backend that needs nothing installed (docs/SPEC.md "Wireless debugging"): Pair until
     * paired, then whether Android's switch is on, and Forget. Android 11 and later only.
     */
    private fun wirelessRow(context: Context): CatalogItem? {
        val wireless = ElevatedAccessHost.wireless(context)
        if (!wireless.supported) return null
        if (!wireless.paired) {
            return dev.droidtop.library.settings.AsyncActionItem(
                id = "accounts_wireless_debugging",
                title = "Pair with wireless debugging",
                subtitle = "More controls with nothing to install. Android shows a code; reply with it",
                run = { ctx, _ -> dev.droidtop.app.WirelessPairing.start(ctx) },
            )
        }
        val on = wireless.state() == BackendState.READY
        return dev.droidtop.library.settings.AsyncActionItem(
            id = "accounts_wireless_debugging",
            title = "Wireless debugging",
            subtitle = if (on) "Paired and on. Press to forget the pairing" else "Paired, but it's off. Turn it on in Developer options",
            value = if (on) "On" else "Off",
            confirmTitle = "Forget droidtop's pairing with wireless debugging? Pairing again needs a new code",
            run = { _, _ ->
                wireless.forget()
                "Forgotten. Remove droidtop under Paired devices in Wireless debugging too"
            },
        )
    }

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
            // Nothing answering is said, with the one step that changes it, rather than a backend that cannot act
            // (Droidtop/tracker#851).
            subtitle = if (active == null) shell.unavailableReason() else null,
            options = shown.map { ChoiceOption(it.id, label(it, active?.label)) },
            current = current.id,
            onSelect = { ctx, value -> ElevatedAccessHost.setChoice(ctx, ElevatedChoice.fromId(value)) },
        )
    }

    private fun label(choice: ElevatedChoice, activeLabel: String?): String = when (choice) {
        ElevatedChoice.AUTO -> "${choice.label} (${activeLabel ?: "none available"})"
        else -> choice.label
    }
}
