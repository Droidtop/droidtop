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

    // Real, confirmed bug (not a guess): EsDeThemeParser.parse's own
    // `variant` parameter defaults to null, and VariantAxis's matching
    // rule (see that class's own doc comment) treats null as "select
    // nothing" -- every single <variant> block in theme.xml, including
    // the ones containing DEcaffe's own real <syslogo> positioning
    // (theme.xml's "solidWithMeta"/"colorWithMeta" blocks), was silently
    // skipped on every parse. Confirmed live: the on-screen system-logo/
    // title overlap bug traced directly to this -- droidtop was drawing
    // its own hand-rolled carousel logo instead of ever loading the
    // theme's real one. capabilities.xml declares four real,
    // user-selectable variants (solidWithMeta/solidWithoutMeta/
    // colorWithMeta/colorWithoutMeta, no theme-declared default) --
    // "solidWithMeta" is picked here to match this same object's own
    // existing "system-logo-white" fallback asset path above (solid/
    // white logos, not colored ones), so the two stay consistent instead
    // of silently disagreeing on style. Real per-user variant selection
    // is separate, future work (see docs/SPEC.md) -- this fixes the
    // "nothing was ever selected at all" bug first.
    private const val DEFAULT_VARIANT = "solidWithMeta"

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
        val theme = try {
            EsDeThemeParser.parse(themeFile, variant = DEFAULT_VARIANT, systemTheme = systemId)
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
