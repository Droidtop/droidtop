package dev.droidtop.library.theme

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale

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
 *
 * Lives in `:runtime-common` (not `:shell-gamepad`, where it originated,
 * and not `:library-core` either -- see this module's own build.gradle.kts
 * for why: `:library-core` already has a real, deliberate dependency ON
 * `:shell-default`, so the theme engine can't live in `:library-core`
 * without a real circular dependency) so both `:shell-gamepad` (the real,
 * Compose-driven theme renderer) and `:shell-default` (the real, unified
 * Android Preference settings screen -- see `SettingsHandheldFragment`'s
 * own real "Theme"/"Sync theme index" entries) can read/drive the SAME
 * real theme discovery and selection state, rather than one of them
 * re-implementing it a second time. Public (not `internal`) for exactly
 * that reason -- this is a real, deliberate
 * cross-module contract, not an accidental leak.
 */
object ThemeAssets {
    private const val BUNDLED_THEMES_ASSET_ROOT = "themes"
    private const val EXTRACTED_MARKER = ".extracted"
    // droidtop's own real, intended default theme (see [resolveActiveTheme]'s
    // own doc comment) -- matches the vendored folder name under
    // [BUNDLED_THEMES_ASSET_ROOT], not a display name.
    private const val DEFAULT_THEME_NAME = "decaffe-es-de"

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
     * Real, writable install target for downloaded themes -- [ThemeDownloader]
     * (JGit-based, real ES-DE theme-downloader parity) clones into this
     * exact directory so downloaded themes show up in [discoverThemes]
     * immediately, no separate registration step.
     */
    fun userThemesDir(context: Context): File = File(context.filesDir, "themes")

    /**
     * Real, confirmed-live bug this fixes: with no explicit [ThemePrefs]
     * selection (true for every install until a real theme-browse UI
     * exists -- Phase 4, still undesigned), falling back to alphabetically
     * FIRST among every bundled theme picked "art-book-next-es-de" over
     * "decaffe-es-de" (A < D) -- confirmed via a real on-device debug log
     * showing art-book-next's own hero-style carousel (pos 0,0 / size 1,1)
     * rendering instead of decaffe's, not a rendering bug in the carousel
     * math at all. Real ES-DE's own `sThemes.begin()` fallback is a
     * reasonable rule for a program that ships with exactly one bundled
     * theme (or where the user picked one during setup) -- droidtop
     * currently bundles two with no selection UI yet, so blindly following
     * that same rule silently serves the wrong one. decaffe is droidtop's
     * own real, intended default (see docs/SPEC.md's own Handheld section)
     * -- prefer it by name when unset, THEN fall back to alphabetically
     * first among whatever remains (still real ES-DE parity for any
     * OTHER/future bundled theme set that doesn't include decaffe at all).
     */
    private fun resolveActiveTheme(context: Context): ThemeDescriptor? {
        val discovered = discoverThemes(context)
        if (discovered.isEmpty()) return null
        val selected = ThemePrefs.get(context)
        return discovered.firstOrNull { it.name == selected }
            ?: discovered.firstOrNull { it.name == DEFAULT_THEME_NAME }
            ?: discovered.first()
    }

    /** Public read of [resolveActiveTheme]'s own name -- the real, resolved active theme, for UI display/cycling, not just the raw (possibly unset) [ThemePrefs] value. */
    fun activeThemeName(context: Context): String? = resolveActiveTheme(context)?.name

    private val systemThemeCache = mutableMapOf<Triple<String, String?, Pair<String?, String?>>, EsDeTheme>()

    init {
        // A theme selection change -- or a theme re-downloaded/updated in
        // place under the same name (ThemePrefs.notifyThemesChanged, fired
        // by ThemeBrowserScreen after a real download) -- must drop every
        // cached parse: entries are keyed by theme NAME, so an updated
        // theme's stale parse would otherwise keep serving forever.
        ThemePrefs.addOnChangeListener { systemThemeCache.clear() }
    }

    /**
     * Loads the currently active theme (real, discovered + selected per
     * [resolveActiveTheme]), parsed for [systemId] specifically -- real
     * ES-DE parses a theme once PER SYSTEM (its own `${system.theme}`
     * substitution differs per system), so this is cached per (theme
     * name, systemId, collectionThemeFolder) rather than parsed fresh
     * every call.
     *
     * [collectionThemeFolder] mirrors real ES-DE's own `SystemData::
     * getThemePath()` (SystemData.cpp:1754-1772, a real local clone kept
     * at /root/es-de-reference): check for a real per-collection
     * `<folder>/theme.xml` override first (`auto-allgames`/
     * `auto-favorites`/`auto-lastplayed`/`custom-collections`, see
     * `GamepadShell.kt`'s own `AutoCollections`/`GameGroup.Collection`),
     * falling back to the theme's root `theme.xml` when that subfolder
     * file doesn't exist -- exactly real ES-DE's own two-step fallback,
     * not a droidtop invention (no bundled theme, decaffe included,
     * actually ships such a subfolder file, but the fallback path is
     * still the real one, not a simplification of it). What WAS a real,
     * confirmed-live bug: `systemTheme` below used to be passed as plain
     * `systemId` (always null for a collection), so `${system.theme}` --
     * and therefore a real theme's own `<include>./system/metadata/
     * ${system.theme}.xml</include>` -- never resolved for a collection
     * at all, even via the correct root `theme.xml` fallback. Fixed by
     * falling back to `collectionThemeFolder` there too, matching real
     * ES-DE's own `system.theme` = `SystemData::mThemeFolder` regardless
     * of which theme.xml file that folder name resolves against.
     */
    fun loadActiveTheme(
        context: Context,
        systemId: String? = null,
        collectionThemeFolder: String? = null,
        systemFullName: String? = null,
    ): EsDeTheme? {
        val active = resolveActiveTheme(context) ?: return null
        val cacheKey = Triple(active.name, systemId, collectionThemeFolder to systemFullName)
        systemThemeCache[cacheKey]?.let { return it }

        val themeDir = when {
            active.userDir != null -> active.userDir
            active.bundledAssetFolder != null -> extractedBundledThemeDir(context, active.bundledAssetFolder)
            else -> null
        } ?: return null

        val collectionThemeFile = collectionThemeFolder?.let { File(themeDir, "$it/theme.xml") }?.takeIf { it.isFile }
        val themeFile = collectionThemeFile ?: File(themeDir, "theme.xml")
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
            // Real device locale, "language_COUNTRY" format matching real
            // capabilities.xml entries -- see parseWithCapabilities' own
            // doc comment for why this needs real resolution instead of
            // the earlier, always-null language axis (any theme with real
            // <language>-scoped content, e.g. DEcaffe's own per-system
            // metadata translations, never surfaced any of it before this).
            val locale = Locale.getDefault()
            val deviceLocale = "${locale.language}_${locale.country}"
            EsDeThemeParser.parseWithCapabilities(
                themeFile,
                // Real, confirmed-live bug this fixes: real ES-DE's own
                // `${system.theme}` variable is `SystemData::mThemeFolder`
                // (SystemData.cpp:1976), which for a COLLECTION really is
                // that collection's own theme-folder name (`auto-allgames`
                // etc, confirmed against SystemData.cpp:1976-2031's own
                // `system.theme`/`.autoCollections`/`.customCollections`
                // handling) -- not left unset. Droidtop passed `systemId`
                // alone (always null for a collection, since a collection
                // isn't a real console system), so `${system.theme}` never
                // resolved for one, and the theme's own real
                // `<include>./system/metadata/${system.theme}.xml</include>`
                // (decaffe's own real per-collection metadata fragments --
                // auto-allgames.xml/auto-favorites.xml/auto-lastplayed.xml/
                // custom-collections.xml, all four bundled) silently
                // included nothing at all for every collection view.
                systemTheme = systemId ?: collectionThemeFolder,
                screenAspectRatio = screenAspectRatio,
                deviceLocale = deviceLocale,
                systemFullName = systemFullName,
                // capabilities.xml lives at the THEME ROOT even when the
                // parsed file is a collection's subfolder theme.xml --
                // see parseWithCapabilities' own parameter comment for
                // the confirmed-live collection-logo bug this fixes.
                themeRootDir = themeDir,
            )
        } catch (t: Exception) {
            Log.e("droidtop.ThemeAssets", "Failed to parse theme '${active.name}'", t)
            null
        }?.let { applyThemePatchesOverlay(context, it, systemId) }
        // NEVER cache a failed parse -- a transient failure (mid-
        // extraction race on first launch was the real, confirmed case)
        // would otherwise poison this name-keyed cache for the whole
        // process lifetime, long after the underlying files became fine.
        if (theme != null) systemThemeCache[cacheKey] = theme
        return theme
    }

    /**
     * Real, writable clone target for `droidtop-theme-patches` -- synced
     * explicitly (network I/O, see [ThemeDownloader.syncThemePatches]),
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

    private val extractionLock = Any()

    private fun extractedBundledThemeDir(context: Context, assetFolder: String): File {
        val themeDir = File(context.cacheDir, "theme_$assetFolder")
        val marker = File(themeDir, EXTRACTED_MARKER)
        // The marker stores the APK's own lastUpdateTime -- NOT
        // versionCode, which this project pins at a constant 1 (see
        // app/build.gradle.kts; only versionName varies per CI run), and
        // NOT versionName either, since a dev reinstall of the same build
        // number with different assets is a real, common case here --
        // lastUpdateTime changes on every real (re)install, which is
        // exactly the "did the bundled assets possibly change" signal.
        // (The original marker was a bare existence check, never
        // invalidated by any update -- the real test device was carrying
        // extractions of two long-dead legacy asset layouts.)
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        } catch (t: Exception) {
            "unknown"
        }
        fun markerCurrent(): Boolean = try {
            marker.isFile && marker.readText().trim() == currentVersion
        } catch (t: Exception) {
            false
        }
        if (markerCurrent()) return themeDir
        // Real, confirmed-live regression this synchronized/atomic shape
        // fixes: post-install, the first Handheld composition parses the
        // theme WHILE extraction is still running (systemLogoPath alone
        // calls loadActiveTheme once per carousel item, concurrently) --
        // unsynchronized callers each saw a stale marker and wiped/
        // re-extracted over each other, and a parse that ran against the
        // half-extracted tree lost real content (decaffe's per-system
        // carousel logos went missing on a real device). One lock so
        // exactly one caller extracts while the rest wait; extraction
        // goes into a sibling temp dir with the marker written LAST, then
        // swaps into place with a same-filesystem rename -- a reader
        // never sees a partial tree, only the old-complete or
        // new-complete one.
        synchronized(extractionLock) {
            if (markerCurrent()) return themeDir
            try {
                val tempDir = File(context.cacheDir, "theme_$assetFolder.extracting")
                tempDir.deleteRecursively()
                extractAssetDir(context, "$BUNDLED_THEMES_ASSET_ROOT/$assetFolder", tempDir)
                File(tempDir, EXTRACTED_MARKER).writeText(currentVersion)
                themeDir.deleteRecursively()
                if (!tempDir.renameTo(themeDir)) {
                    Log.e("droidtop.ThemeAssets", "Atomic swap failed for bundled theme '$assetFolder'")
                }
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
        // Real CarouselComponent::addEntry fallback chain, transcribed:
        // the per-system item image IF its file exists, else the
        // carousel's own declared `defaultImage` IF its file exists (Art
        // Book Next really ships `_default.png` exactly for systems it
        // has no art for), else null -- which the item renderer turns
        // into real ES-DE's own text-label fallback. The existence checks
        // are load-bearing: real ES-DE's parser keeps a PATH property
        // even when the file is missing (ThemeData.cpp:2323-2377 only
        // logs), so a theme's `staticImage` template resolves to a real
        // path for EVERY system -- systems the theme has no art for got a
        // dead path here and rendered as a blank carousel item instead of
        // falling through (confirmed live: Art Book Next's black items).
        return listOfNotNull(
            listElement.valueOrNull<EsDeThemeValue.Path>("staticImage")?.resolved,
            listElement.valueOrNull<EsDeThemeValue.Path>("path")?.resolved,
            listElement.valueOrNull<EsDeThemeValue.Path>("defaultImage")?.resolved,
        ).firstOrNull { File(it).exists() }
    }
}
