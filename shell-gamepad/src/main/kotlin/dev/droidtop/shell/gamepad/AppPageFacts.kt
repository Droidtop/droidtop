package dev.droidtop.shell.gamepad

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.PackageInfoCompat
import dev.droidtop.library.AppCategoryRules
import dev.droidtop.library.AppUsageAccess
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.appSourceLabel
import dev.droidtop.library.format.ValueFormat
import dev.droidtop.library.integrations.AppCatalogs
import dev.droidtop.shell.gamepad.pc.PageFact
import dev.droidtop.shell.gamepad.pc.PageTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What an installed Android app's page knows of it beyond its library entry (docs/SPEC.md 7i, "Apps on the page"), read
 * once when the page opens, off the main thread ([read]): Android's own record of the package (its version, when it was
 * installed and last updated, how many permissions it asks for), when it was last used (Android's usage record, with
 * Usage access), whether an app catalog offers a newer version ([AppCatalogs.updateFor], what the Updates page lists),
 * and whether it counts as a game (the Apps tab's category rules, for Options' Mark as game).
 */
internal data class AppPageFacts(
    val versionName: String?,
    val versionCode: Long,
    val installedAt: Long,
    val updatedAt: Long,
    val permissions: Int,
    val lastUsed: Long?,
    val update: AppCatalogs.UpdateState?,
    val isGame: Boolean,
) {
    /** The installed version as the page names it: the app's own version name, else its version code. */
    val version: String get() = versionName ?: versionCode.toString()

    /** The newer version an app catalog offers that can be installed over this one; null for none (or one Android would refuse). */
    val availableUpdate: String? get() = (update as? AppCatalogs.UpdateState.Available)?.offer?.version

    companion object {
        /** Reads [entry]'s facts; null when the package is no longer installed. The package manager and disk: never the main thread. */
        fun read(context: Context, entry: LibraryEntry): AppPageFacts? {
            val packageName = entry.id
            @Suppress("DEPRECATION")
            val info = runCatching { context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS) }.getOrNull()
                ?: return null
            return AppPageFacts(
                versionName = info.versionName?.takeIf { it.isNotBlank() },
                versionCode = PackageInfoCompat.getLongVersionCode(info),
                installedAt = info.firstInstallTime,
                updatedAt = info.lastUpdateTime,
                permissions = info.requestedPermissions?.size ?: 0,
                lastUsed = AppUsageAccess.lastUsed(context)[packageName],
                update = runCatching { AppCatalogs.updateFor(context, packageName) }.getOrNull(),
                isGame = AppCategoryRules.load(context).isGame(packageName, entry.appFacts),
            )
        }
    }
}

/** [AppPageFacts] for an app's page, read on IO once per [entry] and again when [token] changes; null while reading, or for anything not [enabled]. */
@Composable
internal fun rememberAppPageFacts(entry: LibraryEntry, enabled: Boolean, token: Int): AppPageFacts? {
    val context = LocalContext.current
    return produceState<AppPageFacts?>(null, entry.id, enabled, token) {
        value = if (enabled) withContext(Dispatchers.IO) { AppPageFacts.read(context, entry) } else null
    }.value
}

/**
 * An app's own rows, each under its tab (docs/SPEC.md 7i, "Apps on the page"): Versions the installed version and, when
 * an app catalog offers a newer one, the Update row ([onUpdate] starts it, the Updates page's own start; [updateStatus]
 * is its line while it runs); Details where it came from (the installing store, the installer's package its tip), when
 * it was installed and last updated, and how many permissions it asks for. Activity's rows are the page's
 * ([dev.droidtop.shell.gamepad.pc.activityRows], with [AppPageFacts.lastUsed]). Only facts that exist. Pure.
 */
internal fun appPageRows(entry: LibraryEntry, app: AppPageFacts, now: Long, updateStatus: String?, onUpdate: () -> Unit): List<PageFact> = buildList {
    add(PageFact("Installed version", app.version, subtitle = app.versionName?.let { "Version code ${app.versionCode}" }, tab = PageTab.VERSIONS))
    when (val update = app.update) {
        is AppCatalogs.UpdateState.Available -> add(
            PageFact(
                // "Update" leads Versions (groupRowsByTab), as a game's does.
                APP_UPDATE_ROW,
                update.offer.version,
                subtitle = updateStatus ?: "From ${update.offer.source ?: update.pluginLabel}. Select to install it",
                onActivate = if (updateStatus == null) onUpdate else null,
                tab = PageTab.VERSIONS,
            ),
        )
        // Android would refuse it, so it is said and not offered, as on the Updates page.
        is AppCatalogs.UpdateState.KeyDiffers -> add(
            PageFact(
                APP_UPDATE_ROW,
                update.offer.version,
                subtitle = "From ${update.offer.source ?: update.pluginLabel}, signed by a different key than the installed app, so Android refuses it",
                tab = PageTab.VERSIONS,
            ),
        )
        null -> Unit
    }
    entry.appFacts?.let { facts ->
        add(PageFact("Source", appSourceLabel(facts), tip = facts.installer, tab = PageTab.DETAILS))
    }
    app.installedAt.takeIf { it > 0 }?.let { at ->
        add(PageFact("Installed", ValueFormat.daysAgo(now, at).replaceFirstChar { it.uppercase() }, tab = PageTab.DETAILS))
    }
    app.updatedAt.takeIf { it > 0 && it != app.installedAt }?.let { at ->
        add(PageFact("Last updated", ValueFormat.daysAgo(now, at).replaceFirstChar { it.uppercase() }, tab = PageTab.DETAILS))
    }
    app.permissions.takeIf { it > 0 }?.let { add(PageFact("Permissions", "$it asked for", tab = PageTab.DETAILS)) }
}

/** The Versions row of an app's newer version. */
internal const val APP_UPDATE_ROW = "Update"
