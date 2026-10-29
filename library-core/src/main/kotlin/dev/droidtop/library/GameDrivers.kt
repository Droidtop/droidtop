package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.TextBlockItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A Windows game's Drivers tab (docs/SPEC.md 5b, "Kernel drivers"; Droidtop/tracker#607, owner 2026-10-23): every
 * kernel driver the game has been seen to install, each with an Approved switch, and the game's default for the rest,
 * which is deny and asks before it changes. This is the list the prefix layer will enforce (#584 piece 3); until it
 * lands droidtop only reads the prefix, so the tab says plainly that a driver still installs as the game left it.
 */
object GameDrivers {
    const val SCREEN_ID = "windows_game_drivers"

    fun screen(entryId: String, title: String): CatalogScreen = CatalogScreen(
        id = SCREEN_ID,
        title = "Drivers",
        subtitle = title,
        groups = { context -> withContext(Dispatchers.IO) { groups(context, entryId) } },
        indexGroups = { _ -> emptyList() },
    )

    /** The game page's short line ("EasyAntiCheat driver"), or null when the game has installed none. Disk: IO only. */
    fun pageLine(context: Context, entryId: String): String? {
        val seen = KernelDriverPrefs.seen(context, entryId).ifEmpty { return null }
        val first = seen.first()
        val name = first.product?.let { "$it driver" } ?: first.fileName ?: first.name
        return if (seen.size == 1) name else "$name and ${seen.size - 1} more"
    }

    private fun groups(context: Context, entryId: String): List<CatalogGroup> {
        val allow = KernelDriverPrefs.allowUnlisted(context, entryId)
        val approved = KernelDriverPrefs.approved(context, entryId)
        val installed = KernelDriverPrefs.seen(context, entryId)
        val installedKeys = installed.map { it.key }.toSet()
        val alsoPresent = KernelDriverPrefs.present(context, entryId).filter { it.key !in installedKeys }

        val note = TextBlockItem(
            id = "drivers_note",
            text = "Kernel drivers are what anti-cheat and some copy protection install. droidtop will keep a driver that " +
                "is not approved out of the prefix, so it can never hang it. That protection is not in place yet: for now a " +
                "driver installs as the game left it, and this list and each run's record name it.",
        )
        val default = AsyncActionItem(
            id = "drivers_default",
            title = "Drivers not approved",
            subtitle = if (allow) "Let in as the game installs them" else "Kept out (a placeholder in their place)",
            value = if (allow) "Allowed" else "Placeholder",
            confirmTitle = if (allow) null else "Let in every kernel driver this game installs? A driver Wine cannot run can stop the prefix from starting. Placeholder is the safe choice.",
            run = { ctx, _ ->
                withContext(Dispatchers.IO) { KernelDriverPrefs.setAllowUnlisted(ctx, entryId, !allow) }
                if (allow) "Drivers not approved are kept out" else "Drivers not approved are let in"
            },
        )
        fun row(driver: KernelDriver, origin: String): CatalogItem {
            val on = driver.key in approved
            return AsyncActionItem(
                id = "driver_${driver.key}",
                title = driver.product?.let { "${driver.name} ($it)" } ?: driver.name,
                subtitle = listOfNotNull(driver.fileName, origin, if (driver.start == 4) "disabled in the prefix" else null).joinToString(" · "),
                value = if (on) "Approved" else if (allow) "Allowed" else "Placeholder",
                confirmTitle = if (on) null else "Approve ${driver.fileName ?: driver.name} for this game? It will be let in as the game installs it.",
                run = { ctx, _ ->
                    withContext(Dispatchers.IO) { KernelDriverPrefs.setApproved(ctx, entryId, driver.key, !on) }
                    if (on) "No longer approved" else "Approved"
                },
            )
        }
        return listOfNotNull(
            CatalogGroup("drivers_rules", null, listOf(note, default)),
            installed.takeIf { it.isNotEmpty() }?.let { list ->
                CatalogGroup("drivers_installed", "Installed by this game", list.map { row(it, "installed during one of its runs") })
            },
            alsoPresent.takeIf { it.isNotEmpty() }?.let { list ->
                CatalogGroup("drivers_present", "Already in its prefix", list.map { row(it, "there before this game ran") })
            },
            if (installed.isEmpty() && alsoPresent.isEmpty()) {
                CatalogGroup("drivers_none", null, listOf(TextBlockItem(id = "drivers_empty", text = "No kernel driver seen yet. A run lists any the game installs.")))
            } else {
                null
            },
        )
    }
}
