package dev.droidtop.app.settings

import android.content.Context
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.net.DownloadCategory
import dev.droidtop.net.DownloadPolicy
import dev.droidtop.net.DownloadPools
import dev.droidtop.net.MeteredGrants
import dev.droidtop.net.NetworkRule
import dev.droidtop.net.engine.EnginePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings, Download rules (docs/SPEC.md, "Download rules", Droidtop/tracker#226): the one place the download
 * policy is set. Every row writes [DownloadPolicy], which every download job reads, so nothing here is specific to
 * a store or a source. The same screen is reached from "Downloads and installs".
 */
object DownloadRulesCatalog {
    const val SCREEN_ID = "download_rules"

    fun screen() = CatalogScreen(
        id = SCREEN_ID,
        title = "Download rules",
        subtitle = "Which network downloads use, when updates run, and whether games update themselves",
        groups = { context -> groups(context) },
    )

    private suspend fun groups(context: Context): List<CatalogGroup> {
        val isolated = withContext(Dispatchers.IO) {
            DownloadPolicy.load(context)
            MeteredGrants.load(context)
            EnginePrefs.androidIsolation(context)
        }
        val s = DownloadPolicy.settings.value
        return listOf(
            CatalogGroup(
                id = "download_rules_network",
                title = "Network",
                items = listOf(
                    ChoiceItem(
                        id = "download_rules_network_rule",
                        title = "Download over",
                        subtitle = "A held download says why. On a metered network you allow the network, never one download",
                        options = NetworkRule.values().map { ChoiceOption(it.key, it.label) },
                        current = s.network.key,
                        onSelect = { ctx, value -> DownloadPolicy.update(ctx) { it.copy(network = NetworkRule.fromKey(value)) } },
                    ),
                ),
            ),
        ) + listOfNotNull(MeteredConnectionCatalog.alwaysGroup()) + listOf(
            // The pools (docs/SPEC.md 12a, "One download engine"): the owner, "Configurable, intelligent".
            CatalogGroup(
                id = "download_rules_pools",
                title = "How many at once",
                items = DownloadCategory.entries.map { category ->
                    ChoiceItem(
                        id = "download_rules_pool_${category.key}",
                        title = category.label,
                        subtitle = POOL_SUBTITLES.getValue(category),
                        options = POOL_OPTIONS,
                        current = s.poolLimits[category.key]?.toString() ?: AUTOMATIC,
                        onSelect = { ctx, value ->
                            DownloadPolicy.update(ctx) { settings ->
                                val chosen = value.toIntOrNull()?.takeIf { it in 1..DownloadPools.MAX_LIMIT }
                                settings.copy(poolLimits = if (chosen == null) settings.poolLimits - category.key else settings.poolLimits + (category.key to chosen))
                            }
                        },
                    )
                },
            ),
            CatalogGroup(
                id = "download_rules_engine",
                title = "Download engine",
                items = listOf(
                    ToggleItem(
                        id = "download_rules_engine_isolation",
                        title = "Isolate the download engine with Android",
                        subtitle = "Android 11+. The engine runs apart with no file access; this also gives it its own user",
                        current = isolated,
                        onToggle = { ctx, value -> withContext(Dispatchers.IO) { EnginePrefs.setAndroidIsolation(ctx, value) } },
                    ),
                ),
            ),
            CatalogGroup(
                id = "download_rules_updates",
                title = "Automatic updates",
                items = listOf(
                    ToggleItem(
                        id = "download_rules_auto_default",
                        title = "Keep installed store games up to date",
                        subtitle = "The default for games that follow it. A game's own menu can turn it on or off for itself",
                        current = s.autoUpdateDefault,
                        onToggle = { ctx, value -> DownloadPolicy.update(ctx) { it.copy(autoUpdateDefault = value) } },
                    ),
                    ToggleItem(
                        id = "download_rules_window_on",
                        title = "Update only during a set time",
                        subtitle = "Automatic updates wait for this time of day. A download you start yourself does not",
                        current = s.windowEnabled,
                        onToggle = { ctx, value -> DownloadPolicy.update(ctx) { it.copy(windowEnabled = value) } },
                    ),
                    ChoiceItem(
                        id = "download_rules_window_start",
                        title = "From",
                        options = HOURS,
                        current = (s.windowStartMinute / 60).toString(),
                        onSelect = { ctx, value -> DownloadPolicy.update(ctx) { it.copy(windowStartMinute = value.toInt() * 60) } },
                    ),
                    ChoiceItem(
                        id = "download_rules_window_end",
                        title = "Until",
                        options = HOURS,
                        current = (s.windowEndMinute / 60).toString(),
                        onSelect = { ctx, value -> DownloadPolicy.update(ctx) { it.copy(windowEndMinute = value.toInt() * 60) } },
                    ),
                    ToggleItem(
                        id = "download_rules_while_playing",
                        title = "Update while a game is running",
                        subtitle = "Off: automatic updates wait until you stop playing",
                        current = s.whilePlaying,
                        onToggle = { ctx, value -> DownloadPolicy.update(ctx) { it.copy(whilePlaying = value) } },
                    ),
                ),
            ),
        )
    }

    private val HOURS = (0..23).map { ChoiceOption(it.toString(), DownloadPolicy.clock(it * 60)) }

    private const val AUTOMATIC = "auto"

    private val POOL_OPTIONS = listOf(ChoiceOption(AUTOMATIC, "Automatic")) +
        (1..DownloadPools.MAX_LIMIT).map { ChoiceOption(it.toString(), it.toString()) }

    /** What each pool holds, and what Automatic does for it ([DownloadPools.automaticLimit]). */
    private val POOL_SUBTITLES = mapOf(
        DownloadCategory.GAMES to "Games from your sources. Automatic: one at a time",
        DownloadCategory.UPDATES to "New versions of games, and droidtop's own update. Automatic: one at a time",
        DownloadCategory.PLUGINS to "Plugins and their updates. Automatic: two, one while you play or in battery saver",
        DownloadCategory.SYSTEM to "Windows support, runtimes, apps and BIOS files. Automatic: two, one while you play or in battery saver",
        DownloadCategory.ART to "Covers, screenshots and game details. Automatic: six on Wi-Fi, two on mobile data, one while you play or in battery saver",
    )
}
