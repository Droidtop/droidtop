package dev.droidtop.app.settings

import android.content.Context
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.net.DownloadPolicy
import dev.droidtop.net.NetworkRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings, Download rules (docs/SPEC.md, "Download rules", Droidtop/tracker#226): the one place the download
 * policy is set. Every row writes [DownloadPolicy], which every download job reads, so nothing here is specific to
 * a store or a source. The same screen is reached from "Downloads and installs" and from PC setup.
 */
object DownloadRulesCatalog {
    const val SCREEN_ID = "download_rules"

    fun screen() = CatalogScreen(
        id = SCREEN_ID,
        title = "Download rules",
        subtitle = "Which network downloads use, when updates may run, and whether games keep themselves up to date",
        groups = { context -> groups(context) },
    )

    private suspend fun groups(context: Context): List<CatalogGroup> {
        withContext(Dispatchers.IO) { DownloadPolicy.load(context) }
        val s = DownloadPolicy.settings.value
        return listOf(
            CatalogGroup(
                id = "download_rules_network",
                title = "Network",
                items = listOf(
                    ChoiceItem(
                        id = "download_rules_network_rule",
                        title = "Download over",
                        subtitle = "A download that is held waits and says why. Resume a held download to run it anyway",
                        options = NetworkRule.values().map { ChoiceOption(it.key, it.label) },
                        current = s.network.key,
                        onSelect = { ctx, value -> DownloadPolicy.update(ctx) { it.copy(network = NetworkRule.fromKey(value)) } },
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
                        subtitle = "The default for games that follow it. A game's own menu can turn it on or off for that game",
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
}
