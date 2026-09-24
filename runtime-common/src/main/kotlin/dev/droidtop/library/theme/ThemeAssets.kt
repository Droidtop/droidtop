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
 *   directory -- where [ThemeDownloader], a real, working JGit-based
 *   theme downloader, clones downloaded themes to).
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
 * Android Preference settings screen -- see `SettingsGamingFragment`'s
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
    // The default on a PORTRAIT display -- see [defaultThemeFor]. Slate is
    // ES-DE's own default theme and the only bundled one that declares
    // vertical variants (16:9_vertical, 4:3_vertical).
    private const val PORTRAIT_DEFAULT_THEME_NAME = "slate-es-de"

    data class ThemeDescriptor(val name: String, val bundledAssetFolder: String?, val userDir: File?)

    /**
     * Real, filesystem-driven discovery -- no compiled-in list of theme
     * names. Bundled (APK asset) themes are scanned first, then real user
     * themes (a future downloader's install target) -- a same-named user
     * theme shadows a bundled one, matching real ES-DE's own scan order
     * (user theme directory scanned last, so `sThemes[name] = theme`
     * naturally lets it win).
     *
     * Discovered once and kept: every theme load asks which theme is
     * active, and that used to list the APK's theme assets and the user
     * theme folder again each time, once per carousel system. The set
     * changes only when a theme is downloaded or updated, which fires
     * [ThemePrefs.notifyThemesChanged]; that listener (see `init`) drops
     * the list with the parse caches.
     */
    fun discoverThemes(context: Context): List<ThemeDescriptor> =
        discoveredThemes ?: scanThemes(context).also { discoveredThemes = it }

    @Volatile
    private var discoveredThemes: List<ThemeDescriptor>? = null

    private fun scanThemes(context: Context): List<ThemeDescriptor> {
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
     * theme (or where the user picked one during setup). droidtop bundled
     * two when this was written, so blindly following that rule silently
     * served the wrong one; art-book-next has since been dropped from the
     * APK and is fetched on demand, but preferring decaffe by name stays
     * correct and stays necessary the moment a second theme is present
     * again -- which a single download now makes true. decaffe is droidtop's
     * own real, intended default (see docs/SPEC.md's own Gaming section)
     * -- prefer it by name when unset, THEN fall back to alphabetically
     * first among whatever remains (still real ES-DE parity for any
     * OTHER/future bundled theme set that doesn't include decaffe at all).
     */
    private fun resolveActiveTheme(context: Context): ThemeDescriptor? {
        val discovered = discoverThemes(context)
        if (discovered.isEmpty()) return null
        val selected = ThemePrefs.get(context)
        discovered.firstOrNull { it.name == selected }?.let { return it }
        val default = defaultThemeFor(context, discovered) ?: return null
        // A default chosen BECAUSE of the screen's shape is written down
        // the first time it is resolved, exactly as onboarding's own
        // portrait step writes it. Without this the choice is re-made on
        // every read: turning a phone sideways makes isPortraitScreen
        // false, which puts DEcaffe back mid-session and takes it away
        // again on the way back -- the theme moving under the user,
        // which is the one thing this rule must not do. Onboarding only
        // covers an install that saw that step; this covers the rest.
        if (default.name != DEFAULT_THEME_NAME) ThemePrefs.set(context, default.name)
        return default
    }

    /**
     * The default when the user has chosen nothing: [DEFAULT_THEME_NAME]
     * normally, but on a PORTRAIT screen the first discovered theme that
     * actually ships a vertical variant.
     *
     * This is not a second selection mechanism, it is the honest reading
     * of what the aspect-ratio axis can and cannot do. ES-DE picks the
     * closest DECLARED ratio and stretches it over the screen
     * (EsDeAspectRatio.select, ThemeData.cpp:736-771); a theme with no
     * vertical variant therefore renders its closest landscape layout on
     * a phone -- DEcaffe lands on 4:3 at 1080x1920 -- which is a real
     * layout drawn at the wrong shape, not a portrait layout. No engine
     * fix can invent the portrait artwork and element positions the
     * theme never wrote. So on a portrait display droidtop defaults to a
     * theme that HAS them, preferring Slate (ES-DE's own default theme,
     * which ships 16:9_vertical and 4:3_vertical). DEcaffe stays the
     * landscape default, and an explicit user choice always wins over
     * this -- including choosing DEcaffe on a phone.
     */
    fun defaultThemeFor(
        context: Context,
        discovered: List<ThemeDescriptor> = discoverThemes(context),
    ): ThemeDescriptor? {
        if (discovered.isEmpty()) return null
        val landscapeDefault = discovered.firstOrNull { it.name == DEFAULT_THEME_NAME }
            ?: discovered.first()
        if (!isPortraitScreen(context)) return landscapeDefault
        if (hasVerticalVariant(context, landscapeDefault)) return landscapeDefault
        return discovered.firstOrNull {
            it.name == PORTRAIT_DEFAULT_THEME_NAME && hasVerticalVariant(context, it)
        }
            ?: discovered.firstOrNull { hasVerticalVariant(context, it) }
            ?: landscapeDefault
    }

    /** Width < height on the live display, the same test ES-DE makes (Renderer.cpp:188-191). */
    fun isPortraitScreen(context: Context): Boolean {
        val metrics = context.resources.displayMetrics
        return metrics.heightPixels > metrics.widthPixels
    }

    /** Does this theme declare any `_vertical` aspect ratio at all -- i.e. did its author lay out a portrait screen? */
    fun hasVerticalVariant(context: Context, theme: ThemeDescriptor): Boolean {
        val capabilities = capabilitiesOf(context, theme) ?: return false
        return EsDeAspectRatio.hasVerticalVariant(capabilities.aspectRatios)
    }

    /**
     * Reads a discovered theme's capabilities.xml without extracting the
     * whole theme: a bundled theme is read straight out of the APK's
     * assets, which is what makes this cheap enough to ask about every
     * theme at startup (extraction copies tens of megabytes).
     */
    fun capabilitiesOf(context: Context, theme: ThemeDescriptor): EsDeThemeCapabilities? {
        capabilitiesCache[theme.name]?.let { return it }
        val parsed = try {
            when {
                theme.userDir != null ->
                    EsDeThemeParser.parseCapabilities(File(theme.userDir, "capabilities.xml"))
                theme.bundledAssetFolder != null -> {
                    val asset = "$BUNDLED_THEMES_ASSET_ROOT/${theme.bundledAssetFolder}/capabilities.xml"
                    context.assets.open(asset).use { EsDeThemeParser.parseCapabilities(it) }
                }
                else -> null
            }
        } catch (t: Exception) {
            Log.w("droidtop.ThemeAssets", "Cannot read capabilities of '${theme.name}'", t)
            null
        } ?: return null
        capabilitiesCache[theme.name] = parsed
        return parsed
    }

    /**
     * What a person calls this theme. A theme's own `<themeName>` where it
     * declares one, and otherwise its directory id with the `-es-de`
     * suffix dropped and the words capitalised -- never the raw id, which
     * is developer notation in a user-facing string (docs/SPEC.md 7k).
     * Settings listed "slate-es-de" and onboarding called the same theme
     * "DEcaffe" in prose, two notations for one thing in one flow.
     */
    fun displayName(context: Context, theme: ThemeDescriptor): String =
        capabilitiesOf(context, theme)?.themeName?.takeIf { it.isNotBlank() }
            ?: humanisedThemeId(theme.name)

    /** [displayName] for a theme known only by its directory id. */
    fun displayName(context: Context, themeId: String): String =
        discoverThemes(context).firstOrNull { it.name == themeId }
            ?.let { displayName(context, it) }
            ?: humanisedThemeId(themeId)

    private fun humanisedThemeId(id: String): String =
        id.removeSuffix("-es-de")
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
            .ifBlank { id }

    // Concurrent maps, not plain ones: the shell parses themes on
    // background threads (every carousel system's at once) while
    // composition reads them on the main thread.
    private val capabilitiesCache = java.util.concurrent.ConcurrentHashMap<String, EsDeThemeCapabilities>()

    /** Public read of [resolveActiveTheme]'s own name -- the real, resolved active theme, for UI display/cycling, not just the raw (possibly unset) [ThemePrefs] value. */
    fun activeThemeName(context: Context): String? = resolveActiveTheme(context)?.name

    /**
     * The active theme's own view-transition animations, already resolved
     * through ES-DE's own selection rule -- see [esDeTransitionAnimations].
     * Every transition is INSTANT when there is no active theme or it
     * declares no profile, which is also ES-DE's own baseline.
     */
    fun activeTransitions(context: Context): Map<EsDeViewTransition, EsDeTransitionAnimation> {
        val active = resolveActiveTheme(context)
            ?: return EsDeViewTransition.entries.associateWith { EsDeTransitionAnimation.INSTANT }
        val capabilities = capabilitiesOf(context, active)
        return esDeTransitionAnimations(
            profiles = capabilities?.transitions.orEmpty(),
            setting = ThemePrefs.transitionsSetting(context),
            // ES-DE's `sVariantDefinedTransitions` -- a variant may name a
            // profile of its own. droidtop's parser does not read the
            // variant's `<transitions>` override yet, so this stays null
            // and the first-declared rule applies, which is what a theme
            // whose variants define none already gets.
            variantDefinedTransitions = null,
            suppressedProfiles = capabilities?.suppressedTransitionProfiles.orEmpty(),
        )
    }

    /**
     * What makes one parse of a theme different from another.
     *
     * [screenAspectRatio] is in here for the same reason the parse takes
     * it at all: a theme's aspect-ratio axis is resolved against the LIVE
     * screen shape, so the landscape parse and the portrait parse of one
     * theme are two different documents. Without it the first parse of
     * the session kept being served after a rotation, and a device turned
     * upright went on drawing the layout its author wrote for a wide
     * screen (rig, build 546: Slate's own vertical variant never appeared
     * until the process was restarted).
     */
    private data class ThemeCacheKey(
        val themeName: String,
        val systemId: String?,
        val collectionThemeFolder: String?,
        val systemFullName: String?,
        val collectionKind: EsDeCollectionKind,
        val screenAspectRatio: Float,
    )

    private val systemThemeCache = java.util.concurrent.ConcurrentHashMap<ThemeCacheKey, EsDeTheme>()

    init {
        // A theme selection change -- or a theme re-downloaded/updated in
        // place under the same name (ThemePrefs.notifyThemesChanged, fired
        // by ThemeBrowserScreen after a real download) -- must drop every
        // cached parse: entries are keyed by theme NAME, so an updated
        // theme's stale parse would otherwise keep serving forever. A
        // download is also the one event that changes which themes exist.
        ThemePrefs.addOnChangeListener {
            discoveredThemes = null
            systemThemeCache.clear()
            capabilitiesCache.clear()
        }
    }

    private fun cacheKey(
        context: Context,
        theme: ThemeDescriptor,
        systemId: String?,
        collectionThemeFolder: String?,
        systemFullName: String?,
        collectionKind: EsDeCollectionKind,
    ): ThemeCacheKey {
        // Real device screen ratio (landscape width/height, matching
        // ES_DE_ASPECT_RATIO_MAP's own convention), part of what
        // identifies a parse -- see [ThemeCacheKey].
        val metrics = context.resources.displayMetrics
        return ThemeCacheKey(
            themeName = theme.name,
            systemId = systemId,
            collectionThemeFolder = collectionThemeFolder,
            systemFullName = systemFullName,
            collectionKind = collectionKind,
            screenAspectRatio = metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat(),
        )
    }

    /**
     * [loadActiveTheme]'s answer if it is already parsed, and null rather
     * than a parse if it is not. This is what composition asks: a parse
     * is XML and file reads, which belong on a background thread, and the
     * shell does those through [loadActiveTheme] off the main thread.
     */
    fun cachedActiveTheme(
        context: Context,
        systemId: String? = null,
        collectionThemeFolder: String? = null,
        systemFullName: String? = null,
        collectionKind: EsDeCollectionKind = EsDeCollectionKind.NONE,
    ): EsDeTheme? {
        val active = resolveActiveTheme(context) ?: return null
        return systemThemeCache[cacheKey(context, active, systemId, collectionThemeFolder, systemFullName, collectionKind)]
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
        // NONE for a real system; the caller knows whether the collection
        // it is showing is one of ES-DE's three automatic ones or a custom
        // one, and the three mutually exclusive `${system.*}` variable
        // families turn on exactly that (SystemData.cpp:1978-2032).
        collectionKind: EsDeCollectionKind = EsDeCollectionKind.NONE,
    ): EsDeTheme? {
        val active = resolveActiveTheme(context) ?: return null
        return loadTheme(context, active, systemId, collectionThemeFolder, systemFullName, collectionKind)
    }

    /**
     * [loadActiveTheme] for a theme that is NOT the active one: the same
     * parse, the same cache, the same per-theme colour scheme, variant and
     * aspect ratio, for a theme the person is only looking at.
     *
     * This exists so onboarding's Appearance step can show a REAL render
     * of each theme rather than a picture of one or a name on its own
     * (docs/SPEC.md 7b). There is exactly one theme loader, and a preview
     * goes through it like everything else -- a second, simplified parse
     * for previews would be a preview of something the shell never draws.
     */
    fun loadTheme(
        context: Context,
        active: ThemeDescriptor,
        systemId: String? = null,
        collectionThemeFolder: String? = null,
        systemFullName: String? = null,
        collectionKind: EsDeCollectionKind = EsDeCollectionKind.NONE,
    ): EsDeTheme? {
        // The live screen ratio resolves a real theme's own "automatic"
        // aspectRatio capability to whichever declared ratio is actually
        // closest to THIS device, instead of a droidtop-invented fallback.
        // See parseWithCapabilities' own doc comment for why skipping this
        // silently breaks any theme using that common real convention. It
        // is part of the cache key, so it is read before the cache is
        // consulted.
        val cacheKey = cacheKey(context, active, systemId, collectionThemeFolder, systemFullName, collectionKind)
        val screenAspectRatio = cacheKey.screenAspectRatio
        systemThemeCache[cacheKey]?.let { return it }

        val themeDir = when {
            active.userDir != null -> active.userDir
            active.bundledAssetFolder != null -> extractedBundledThemeDir(context, active.bundledAssetFolder)
            else -> null
        } ?: return null

        val collectionThemeFile = collectionThemeFolder?.let { File(themeDir, "$it/theme.xml") }?.takeIf { it.isFile }
        val themeFile = collectionThemeFile ?: File(themeDir, "theme.xml")
        val theme = try {
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
                collectionKind = collectionKind,
                // capabilities.xml lives at the THEME ROOT even when the
                // parsed file is a collection's subfolder theme.xml --
                // see parseWithCapabilities' own parameter comment for
                // the confirmed-live collection-logo bug this fixes.
                themeRootDir = themeDir,
                // Real ES-DE parity: colorScheme/variant are user
                // selections (per theme), validated in the parser against
                // capabilities; unset/stale falls back to first-declared.
                // Cache safety: ThemePrefs.set*() fires the same change
                // listeners a theme switch does, which clears
                // systemThemeCache -- so stale-scheme parses can't be
                // served after a selection change.
                colorSchemeOverride = ThemePrefs.colorScheme(context, active.name),
                variantOverride = ThemePrefs.variant(context, active.name),
                aspectRatioOverride = ThemePrefs.aspectRatio(context, active.name),
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
        // fixes: post-install, the first Gaming composition parses the
        // theme WHILE extraction is still running (the shell parses
        // every carousel system's theme, concurrently with its own
        // focused-system parse) --
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
     * The ACTIVE theme's declared capabilities (colorSchemes/variants with
     * their labels) — what the Settings colorScheme/variant pickers list.
     * Null when no theme resolves.
     */
    fun activeThemeCapabilities(context: Context): EsDeThemeCapabilities? {
        val active = resolveActiveTheme(context) ?: return null
        val themeDir = when {
            active.userDir != null -> active.userDir
            active.bundledAssetFolder != null -> extractedBundledThemeDir(context, active.bundledAssetFolder)
            else -> null
        } ?: return null
        return EsDeThemeParser.parseCapabilities(File(themeDir, "capabilities.xml"))
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
     *
     * Takes the system's own parse rather than loading one, so the logo
     * comes from the same per-system parse ES-DE gives that system (its
     * collection folder and `${system.*}` values included) and costs no
     * second parse. It checks files exist, so it is asked off the main
     * thread.
     */
    fun systemLogoPath(theme: EsDeTheme): String? {
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
