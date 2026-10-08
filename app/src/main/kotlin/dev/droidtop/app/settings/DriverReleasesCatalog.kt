package dev.droidtop.app.settings

import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.runtime.windows.utils.DriverReleases
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turnip driver builds from the projects that publish them (docs/SPEC.md 5a,
 * [DriverReleases]): check, then install one; it becomes a choice of the
 * "Driver build" row. Reached from Wine and graphics on an arm64 device.
 */
object DriverReleasesCatalog {

    fun screen(): CatalogScreen = CatalogScreen(
        id = "wine_driver_releases",
        title = "Driver builds",
        groups = { context ->
            val check = withContext(Dispatchers.IO) { DriverReleases.cached(context) }
            val refresh = AsyncActionItem(
                id = "wine_driver_releases_check",
                title = "Check for driver builds",
                value = check?.let { "Checked " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.checkedAt)) }
                    ?: "Not checked yet",
                run = { ctx, _ ->
                    runCatching { DriverReleases.refresh(ctx) }.fold(
                        { found ->
                            buildString {
                                append("${found.offers.size} driver builds")
                                if (found.failed.isNotEmpty()) append("; not reached: " + found.failed.joinToString(", "))
                            }
                        },
                        { "Couldn't check: ${it.message ?: it}" },
                    )
                },
            )
            val offers = check?.offers.orEmpty()
            val installed = withContext(Dispatchers.IO) { offers.associateWith { DriverReleases.installedId(context, it) } }
            listOf(CatalogGroup(id = "wine_driver_releases_top", title = null, items = listOf(refresh))) +
                offers.groupBy { it.source }.map { (source, list) ->
                    CatalogGroup(
                        id = "wine_driver_releases_$source",
                        title = source,
                        items = list.map { offer ->
                            AsyncActionItem(
                                id = "wine_driver_release_${offer.name}",
                                title = offer.label,
                                subtitle = offer.tag,
                                value = if (installed[offer] != null) "Installed" else "${offer.size / (1024 * 1024)} MB",
                                run = { ctx, onStatus ->
                                    if (installed[offer] != null) {
                                        "Already installed; choose it under Driver build"
                                    } else {
                                        runCatching {
                                            DriverReleases.install(ctx, offer) { onStatus("Downloading… ${(it.coerceIn(0f, 1f) * 100).toInt()}%") }
                                        }.fold(
                                            { "Installed; choose it under Driver build" },
                                            { "Couldn't install: ${it.message ?: it}" },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
        },
        // Network-backed rows belong to the screen, not to settings search.
        indexGroups = { _ -> emptyList() },
    )
}
