package dev.droidtop.shell.gamepad.theme

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Resolves real per-system logo artwork from the bundled DEcaffe theme
 * (see README.md's "Bundled theme" section for attribution/license) for
 * [dev.droidtop.shell.gamepad.GamesSection]'s system carousel -- the
 * `<syslogo>` path ES-DE's own theme.xml resolves via `${system.theme}`
 * (`./system/logos/system-logo-white/${system.theme}.svg`, confirmed by
 * reading the bundled theme.xml directly), applied here the same way:
 * [ConsoleSystemDef.id] *is* that `${system.theme}` value, since
 * EsDeConsoleSystems.kt was generated from the same real ES-DE system list
 * DEcaffe's own asset filenames are keyed by.
 *
 * Only covers real console systems for now -- droidtop's own invented
 * "systems" (RENPY, RPG_MAKER_*, KIRIKIRI) have no DEcaffe asset to draw
 * from; giving those their own logo/metadata in DEcaffe's exact format is
 * separately scoped, real per-engine design work, not attempted here (see
 * docs/SPEC.md's open items).
 */
internal object ThemeAssets {
    private const val ASSET_PREFIX = "themes/decaffe-es-de/system/logos/system-logo-white"

    /**
     * Copies the matching bundled SVG to [Context.getCacheDir] (Coil/Compose
     * load from a real file path, not an AssetManager stream directly) and
     * returns that path, or null if this system has no DEcaffe logo asset.
     * Cached on disk across calls -- these assets never change at runtime,
     * so there's no reason to re-copy on every recomposition/scan.
     */
    fun systemLogoPath(context: Context, systemId: String): String? {
        val assetPath = "$ASSET_PREFIX/$systemId.svg"
        val cacheFile = File(File(context.cacheDir, "theme_logos"), "$systemId.svg")
        if (cacheFile.exists()) return cacheFile.absolutePath

        return try {
            context.assets.open(assetPath).use { input ->
                cacheFile.parentFile?.mkdirs()
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            cacheFile.absolutePath
        } catch (t: Exception) {
            // Real, expected case for most systems -- DEcaffe's own asset
            // set (204 real logos) doesn't cover every one of ES-DE's 195
            // systems 1:1 with droidtop's alias list, and definitely
            // doesn't cover droidtop's invented engine "systems". Not an
            // error worth logging above debug: GroupCard just renders
            // text-only for these, same as before this was wired in.
            Log.d("droidtop.ThemeAssets", "No DEcaffe logo for system '$systemId'")
            null
        }
    }
}
