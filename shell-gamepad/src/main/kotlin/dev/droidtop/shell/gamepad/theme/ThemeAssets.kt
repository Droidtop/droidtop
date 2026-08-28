package dev.droidtop.shell.gamepad.theme

import android.content.Context
import android.util.Log
import dev.droidtop.library.theme.EsDeTheme
import dev.droidtop.library.theme.EsDeThemeParser
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

    private const val THEME_ROOT_ASSET = "themes/decaffe-es-de"
    private const val EXTRACTED_MARKER = ".extracted"

    /**
     * Parses the bundled DEcaffe theme's real theme.xml -- needs a real
     * filesystem path (not an AssetManager stream) since
     * [EsDeThemeParser.parse]'s own PATH-property resolution builds paths
     * relative to the theme file's directory on disk (ES-DE's own real
     * convention, and Compose/Coil also need a real file path to load
     * images from, not an asset URI). Extracts the whole real theme
     * folder (~1244 files) to [Context.getCacheDir] once, marked with a
     * sentinel file so repeat calls don't re-copy -- a real, one-time
     * cost, not per-recomposition.
     *
     * [systemId] is real ES-DE's own `${system.theme}` value (droidtop's
     * [dev.droidtop.library.consoles.ConsoleSystemDef.id] IS that value,
     * generated from the same real system list DEcaffe's own asset
     * filenames are keyed by -- see that class's own doc comment) --
     * passing it resolves per-system metadata includes (systemName/
     * systemManufacturer/systemReleaseYear/...) that a `null` (theme-wide,
     * no system context) parse can't reach at all, since their real
     * include paths are literally `.../${system.theme}.xml`. Real ES-DE
     * itself parses a theme once PER SYSTEM for exactly this reason, not
     * once globally -- [systemThemeCache] mirrors that (one real,
     * relatively expensive multi-file parse per system id, cached rather
     * than repeated every recomposition/focus change).
     */
    private val systemThemeCache = mutableMapOf<String?, EsDeTheme?>()

    fun loadDecaffeTheme(context: Context, systemId: String? = null): EsDeTheme? {
        systemThemeCache[systemId]?.let { return it }
        val themeDir = File(context.cacheDir, "theme_decaffe")
        val marker = File(themeDir, EXTRACTED_MARKER)
        if (!marker.exists()) {
            try {
                extractAssetDir(context, THEME_ROOT_ASSET, themeDir)
                marker.createNewFile()
            } catch (t: Exception) {
                Log.e("droidtop.ThemeAssets", "Failed to extract bundled theme", t)
                return null
            }
        }
        val themeFile = File(themeDir, "theme.xml")
        // Real ES-DE default-selection rule for every axis (aspectRatio/
        // colorScheme/fontSize/variant): whichever value the theme's own
        // real capabilities.xml declares FIRST -- confirmed against real
        // ES-DE source (ThemeData.cpp's own `mSelectedX = mXs.front()`
        // pattern for each axis). Extracted alongside theme.xml above, so
        // it's already sitting in themeDir with the same real path.
        val theme = try {
            EsDeThemeParser.parseWithCapabilities(themeFile, systemTheme = systemId)
        } catch (t: Exception) {
            Log.e("droidtop.ThemeAssets", "Failed to parse bundled theme.xml", t)
            null
        }
        systemThemeCache[systemId] = theme
        return theme
    }

    private fun extractAssetDir(context: Context, assetPath: String, destDir: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // A leaf file, not a directory -- AssetManager.list() returns
            // an empty array for both "empty directory" and "not a
            // directory," so this is the real, standard way to tell them
            // apart: try to open it as a file.
            destDir.parentFile?.mkdirs()
            try {
                context.assets.open(assetPath).use { input ->
                    destDir.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (t: Exception) {
                // Genuinely empty directory (rare in this bundle) -- fine, nothing to copy.
            }
            return
        }
        destDir.mkdirs()
        for (child in children) {
            extractAssetDir(context, "$assetPath/$child", File(destDir, child))
        }
    }
}
