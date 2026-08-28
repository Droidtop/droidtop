package dev.droidtop.shell.gamepad.theme

import android.content.Context
import android.util.Log
import dev.droidtop.library.theme.EsDeTheme
import dev.droidtop.library.theme.EsDeThemeParser
import dev.droidtop.library.theme.EsDeThemeValue
import dev.droidtop.library.theme.primaryListElement
import java.io.File

/**
 * Real, generic multi-theme discovery/loading -- deliberately mirrors real
 * ES-DE's own actual mechanism (`ThemeData::populateThemes`/
 * `ThemeData::loadFile`, `es-core/src/ThemeData.cpp`, read directly from
 * the local reference clone, not guessed):
 *
 * - Real ES-DE scans a small list of real theme-holding directories (on
 *   Android specifically: `getProgramDataPath()+"/themes"`,
 *   `getAppDataDirectory()+"/themes"`, and a real user theme directory
 *   under its internal app-data dir) for immediate subdirectories, and
 *   treats a subdirectory as a valid theme iff it has a real
 *   `capabilities.xml` file directly inside it (`ThemeData::
 *   parseThemeCapabilities`'s own `validTheme` flag -- literally just
 *   "does capabilities.xml exist", nothing deeper). Mirrored here as
 *   [BUNDLED_THEMES_ASSET_ROOT] (droidtop's APK-bundled equivalent of
 *   real ES-DE's read-only program-data theme dir) plus [userThemesDir]
 *   (droidtop's equivalent of real ES-DE's writable user theme
 *   directory -- where a future real theme downloader, not yet built,
 *   would extract downloaded themes to).
 * - A theme's real name is its own folder name (`Theme::getName()` is
 *   literally `Utils::FileSystem::getStem(path)`) -- never a
 *   droidtop-invented display name.
 * - The active theme is a real stored *setting* (`Settings::getString
 *   ("Theme")` in real ES-DE), looked up against the discovered set by
 *   name; if unset or no longer present, real ES-DE falls back to
 *   `sThemes.begin()` -- the FIRST theme alphabetically, case-insensitive
 *   (`ThemeData::StringComparator` sorts via `toUpper`), not any single
 *   hardcoded theme. [ThemePrefs] mirrors that exact fallback rule
 *   instead of hardcoding a specific folder name anywhere.
 *
 * Real ES-DE also supports an OLDER, legacy per-system-subfolder theme
 * layout (`Theme::getThemePath(system)` = `path/<system>/theme.xml`) for
 * backward compatibility with pre-3.0 "theme sets" -- not mirrored here;
 * every real theme droidtop has bundled (decaffe, ArtBookNext) uses the
 * modern single-root-`theme.xml`-with-`${system.theme}`-driven-`<include>`
 * layout, which [EsDeThemeParser.parseWithCapabilities] already handles.
 * A real, honest gap, not a guess -- flagged the same way this project
 * flags other deliberately-deferred real ES-DE behavior.
 */
internal object ThemeAssets {
    private const val BUNDLED_THEMES_ASSET_ROOT = "themes"
    private const val EXTRACTED_MARKER = ".extracted"

    data class ThemeDescriptor(val name: String, val bundledAssetFolder: String?, val userDir: File?)

    /**
     * Real, filesystem-driven discovery -- no compiled-in list of theme
     * names. Bundled (APK asset) themes are scanned first, then real user
     * themes (a future downloader's install target) -- a same-named user
     * theme shadows a bundled one, matching real ES-DE's own scan order
     * (user theme directory scanned last, so `sThemes[name] = theme`
     * naturally lets it win).
     */
    fun discoverThemes(context: Context): List<ThemeDescriptor> {
        val byName = linkedMapOf<String, ThemeDescriptor>()
        val bundledFolders = try {
            context.assets.list(BUNDLED_THEMES_ASSET_ROOT) ?: emptyArray()
        } catch (t: Exception) {
            emptyArray()
        }
        for (folder in bundledFolders) {
            if (assetHasCapabilities(context, folder)) {
                byName[folder] = ThemeDescriptor(name = folder, bundledAssetFolder = folder, userDir = null)
            }
        }
        val userDirs = userThemesDir(context).listFiles { f -> f.isDirectory } ?: emptyArray()
        for (dir in userDirs) {
            if (File(dir, "capabilities.xml").isFile) {
                byName[dir.name] = ThemeDescriptor(name = dir.name, bundledAssetFolder = null, userDir = dir)
            }
        }
        return byName.values.sortedBy { it.name.uppercase() }
    }

    private fun assetHasCapabilities(context: Context, folder: String): Boolean =
        try {
            context.assets.list("$BUNDLED_THEMES_ASSET_ROOT/$folder")?.contains("capabilities.xml") == true
        } catch (t: Exception) {
            false
        }

    /**
     * Real, writable install target for downloaded themes -- [dev.droidtop.library.theme.ThemeDownloader]
     * (JGit-based, real ES-DE theme-downloader parity) clones into this
     * exact directory so downloaded themes show up in [discoverThemes]
     * immediately, no separate registration step.
     */
    fun userThemesDir(context: Context): File = File(context.filesDir, "themes")

    /**
     * Resolves [ThemePrefs]' selected theme name against real discovery,
     * falling back to the first theme alphabetically (mirroring real
     * ES-DE's `sThemes.begin()` fallback) when the stored name is unset or
     * no longer present -- never a hardcoded folder name.
     */
    private fun resolveActiveTheme(context: Context): ThemeDescriptor? {
        val discovered = discoverThemes(context)
        if (discovered.isEmpty()) return null
        val selected = ThemePrefs.get(context)
        return discovered.firstOrNull { it.name == selected } ?: discovered.first()
    }

    /** Public read of [resolveActiveTheme]'s own name -- the real, resolved active theme, for UI display/cycling, not just the raw (possibly unset) [ThemePrefs] value. */
    fun activeThemeName(context: Context): String? = resolveActiveTheme(context)?.name

    private val systemThemeCache = mutableMapOf<Pair<String, String?>, EsDeTheme?>()

    /**
     * Loads the currently active theme (real, discovered + selected per
     * [resolveActiveTheme]), parsed for [systemId] specifically -- real
     * ES-DE parses a theme once PER SYSTEM (its own `${system.theme}`
     * substitution differs per system), so this is cached per (theme
     * name, systemId) pair rather than parsed fresh every call.
     */
    fun loadActiveTheme(context: Context, systemId: String? = null): EsDeTheme? {
        val active = resolveActiveTheme(context) ?: return null
        val cacheKey = active.name to systemId
        systemThemeCache[cacheKey]?.let { return it }

        val themeDir = when {
            active.userDir != null -> active.userDir
            active.bundledAssetFolder != null -> extractedBundledThemeDir(context, active.bundledAssetFolder)
            else -> null
        } ?: return null

        val themeFile = File(themeDir, "theme.xml")
        val theme = try {
            // Real device screen ratio (landscape width/height, matching
            // ES_DE_ASPECT_RATIO_MAP's own convention) -- resolves a real
            // theme's own "automatic" aspectRatio capability to whichever
            // declared ratio is actually closest to THIS device, instead
            // of a droidtop-invented fallback. See parseWithCapabilities'
            // own doc comment for why skipping this silently breaks any
            // theme using that common real convention.
            val metrics = context.resources.displayMetrics
            val screenAspectRatio = metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()
            EsDeThemeParser.parseWithCapabilities(themeFile, systemTheme = systemId, screenAspectRatio = screenAspectRatio)
        } catch (t: Exception) {
            Log.e("droidtop.ThemeAssets", "Failed to parse theme '${active.name}'", t)
            null
        }?.let { applyThemePatchesOverlay(context, it, systemId) }
        systemThemeCache[cacheKey] = theme
        return theme
    }

    /**
     * Real, writable clone target for `droidtop-theme-patches` -- synced
     * explicitly (network I/O, see [dev.droidtop.library.theme.ThemeDownloader.syncThemePatches]),
     * never from inside this hot, synchronous load path. Reading here is
     * purely local-disk and safe to call unconditionally: an
     * unsynced/absent clone just means [applyThemePatchesOverlay] has
     * nothing to overlay yet, which is a real, valid state, not an error.
     */
    fun themePatchesDir(context: Context): File = File(context.filesDir, "theme_patches")

    /**
     * Additive-only overlay: fills in real per-system metadata
     * (`systemName`/`systemDescription`/...) for [systemId]s no real
     * ES-DE theme has any metadata for at all (droidtop's own invented
     * engine systems) -- never overrides a key the loaded theme already
     * declares itself, so this can never corrupt a real theme's own real
     * per-system data.
     */
    private fun applyThemePatchesOverlay(context: Context, theme: EsDeTheme, systemId: String?): EsDeTheme {
        if (systemId == null) return theme
        val overlay = EsDeThemeParser.parseVariablesFragment(
            File(themePatchesDir(context), "system/metadata/$systemId.xml")
        )
        if (overlay.isEmpty()) return theme
        return theme.copy(variables = overlay + theme.variables)
    }

    private fun extractedBundledThemeDir(context: Context, assetFolder: String): File {
        val themeDir = File(context.cacheDir, "theme_$assetFolder")
        val marker = File(themeDir, EXTRACTED_MARKER)
        if (!marker.exists()) {
            try {
                extractAssetDir(context, "$BUNDLED_THEMES_ASSET_ROOT/$assetFolder", themeDir)
                marker.createNewFile()
            } catch (t: Exception) {
                Log.e("droidtop.ThemeAssets", "Failed to extract bundled theme '$assetFolder'", t)
            }
        }
        return themeDir
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
                // Genuinely empty directory -- fine, nothing to copy.
            }
            return
        }
        destDir.mkdirs()
        for (child in children) {
            extractAssetDir(context, "$assetPath/$child", File(destDir, child))
        }
    }

    /**
     * Real per-system carousel/syslogo art, resolved generically from
     * whichever theme is active -- NOT a hardcoded decaffe-specific asset
     * path. Real ES-DE themes declare their system-logo image as a
     * `staticImage` property (or a `<syslogo>`-named `<image>`'s `path`,
     * for themes using the older split-element convention) on the
     * "system" view's own primary browsing element (carousel/grid/
     * textlist), already resolved per-system by [loadActiveTheme]'s own
     * `${system.theme}` substitution -- this just reads that value back
     * out instead of maintaining a second, separate lookup.
     */
    fun systemLogoPath(context: Context, systemId: String): String? {
        val theme = loadActiveTheme(context, systemId) ?: return null
        val listElement = theme.views["system"]?.primaryListElement() ?: return null
        val path = listElement.valueOrNull<EsDeThemeValue.Path>("staticImage")
            ?: listElement.valueOrNull<EsDeThemeValue.Path>("path")
        return path?.resolved
    }
}
