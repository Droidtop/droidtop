package dev.droidtop.library.theme

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader

/**
 * One real ES-DE "variant axis" -- confirmed against ES-DE's own real
 * ThemeData.cpp source (`parseVariants`/`parseColorSchemes`/
 * `parseFontSizes`/`parseAspectRatios`): a tag with a `name` attribute,
 * matched against a currently-selected value for that axis. [selected]
 * `null` means no selection exists for this axis at all -- every block
 * for that tag name is skipped unconditionally (real for `language`: no
 * per-user language selection to thread through yet).
 *
 * Earlier revisions of this parser handled aspectRatio/colorScheme/
 * fontSize as three near-identical, hand-duplicated `when` branches
 * (found and fixed one at a time as each one's absence caused a real,
 * separately-diagnosed rendering bug) -- this list is the fix for *that*
 * pattern, not just the individual bugs: a new axis is one line here, not
 * a new branch duplicated across every place a theme document gets
 * walked.
 *
 * Known, real, deliberately-deferred gap (confirmed against real
 * source): real ES-DE's five parse functions are NOT symmetric --
 * `parseVariants`/`parseAspectRatios` really do recurse into every other
 * axis plus `<view>`, but `parseColorSchemes`/`parseFontSizes` only ever
 * recurse into `<variables>`/`<include>` (they cannot real-world contain
 * a nested `<view>` or another axis block at all). This parser applies
 * one identical recursive [parseScopedBlock] to all five uniformly,
 * making it over-permissive for colorScheme/fontSize rather than
 * matching real ES-DE's stricter leaf-only grammar for those two -- lower
 * real risk than it sounds, since no real theme (including the bundled
 * one) actually nests content that way, but a genuine, known deviation,
 * not something to silently claim full parity on.
 */
private data class VariantAxis(val tagName: String, val selected: String?)

/**
 * Real ES-DE theme.xml parser -- a clean-room Kotlin port of the parsing
 * *rules* in ES-DE's own open-source `ThemeData::parseView`/`parseElement`
 * (verified against the actual source, not guessed; see [EsDeTheme]'s own
 * doc comment for why this is a reimplementation rather than reused code).
 * Covers a real, deliberately scoped element/property subset -- see
 * [ES_DE_ELEMENT_SCHEMA].
 *
 * Real, verified parsing rules this follows:
 *  - A view's own `name` attribute, and an element's own `name` attribute,
 *    can both be comma/whitespace-separated to apply one definition to
 *    several keyed instances at once ("all" as a view name applies to
 *    both "system" and "gamelist").
 *  - Each element's properties are child XML elements, not attributes --
 *    the child tag name is the property name, its text content the raw
 *    value.
 *  - `${name}` placeholders anywhere in a value are resolved against the
 *    theme's own `<variables>` block before type coercion.
 *  - NORMALIZED_PAIR values are "x y" space-separated floats. COLOR values
 *    are 6 or 8 hex digits with no leading '#' (6 digits implies full
 *    alpha, 0xFF, appended) -- same bit layout as ES-DE's own
 *    `getHexColor`. PATH values resolve relative to the theme file's own
 *    directory.
 *  - An unrecognized property name is skipped, not a hard error -- this
 *    parser intentionally covers a subset of the real schema, and a real
 *    theme file using a property outside that subset shouldn't fail to
 *    load entirely because of it (a real difference from ES-DE's own
 *    stricter behavior, deliberate given the smaller scope here).
 *  - `<include>path</include>` pulls in another XML file's own top-level
 *    content as if it were inlined at that point -- confirmed real:
 *    DEcaffe's own theme.xml is mostly a shell of `<include>` tags, not
 *    inline content.
 *  - `<variant>`, `<colorScheme>`, `<fontSize>`, and `<aspectRatio>` --
 *    each with a real `name` attribute -- are ES-DE's own variant axes
 *    (see [VariantAxis]); `<language>` is treated the same way but with
 *    no selection at all. All five are handled by ONE generic mechanism
 *    (see [parseNode]), not per-tag special cases.
 */
/**
 * Real ES-DE capabilities.xml content -- each list in real theme-declared
 * order. Real ES-DE's own default-selection rule for every axis
 * (confirmed against real ThemeData.cpp source: `mSelectedColorScheme =
 * mColorSchemes.front()`, same pattern for fontSize/variant/aspectRatio)
 * is "whichever the theme declares FIRST," not a fixed guessed string --
 * [EsDeThemeParser.parseWithCapabilities] is the real entry point that
 * applies this rule; [EsDeThemeParser.parse]'s own hardcoded default
 * parameters only matter for a caller with no real capabilities.xml to
 * read (e.g. a test theme.xml built without one).
 */
data class EsDeThemeCapabilities(
    val aspectRatios: List<String>,
    val colorSchemes: List<String>,
    val fontSizes: List<String>,
    val variants: List<String>,
    val languages: List<String> = emptyList(),
    /** Human-readable `<label>` per colorScheme/variant name, when capabilities.xml declares one — what a real selection UI shows (real ES-DE's own theme menus use these labels). */
    val colorSchemeLabels: Map<String, String> = emptyMap(),
    val variantLabels: Map<String, String> = emptyMap(),
    /**
     * Real `<transitions name="...">` profiles in declared order
     * (ThemeData.cpp:1568-1700). Order is load-bearing: with the
     * transitions setting on `automatic` the FIRST one is what a theme
     * gets (ThemeData.cpp:1063-1064).
     */
    val transitions: List<EsDeTransitionProfile> = emptyList(),
    /** Real `<suppressTransitionProfiles><entry>` (ThemeData.cpp:1719-1751): built-in profiles this theme refuses. */
    val suppressedTransitionProfiles: List<String> = emptyList(),
)

object EsDeThemeParser {
    private const val MAX_INCLUDE_DEPTH = 24

    /**
     * Real capabilities.xml is a flat, non-nested list of declarations
     * (unlike theme.xml's own recursive axis/view grammar) -- a plain
     * single-pass walk collecting `<aspectRatio>`/`<fontSize>` text
     * content and `<colorScheme name="...">`/`<variant name="...">`
     * attributes is enough; their own nested `<label>` children are
     * irrelevant here and simply fall through unmatched.
     */
    fun parseCapabilities(capabilitiesFile: File): EsDeThemeCapabilities {
        if (!capabilitiesFile.isFile) {
            return EsDeThemeCapabilities(emptyList(), emptyList(), emptyList(), emptyList())
        }
        return parseCapabilities(capabilitiesFile.readText())
    }

    /**
     * Same parse from an open stream -- a BUNDLED theme's
     * capabilities.xml lives inside the APK, and asking what a theme
     * declares (does it have a vertical variant?) must not require
     * extracting tens of megabytes of it to disk first. See
     * `ThemeAssets.capabilitiesOf`.
     */
    fun parseCapabilities(input: java.io.InputStream): EsDeThemeCapabilities =
        parseCapabilities(input.reader().readText())

    fun parseCapabilities(xml: String): EsDeThemeCapabilities {
        val aspectRatios = mutableListOf<String>()
        val colorSchemes = mutableListOf<String>()
        val fontSizes = mutableListOf<String>()
        val variants = mutableListOf<String>()
        val languages = mutableListOf<String>()
        val colorSchemeLabels = mutableMapOf<String, String>()
        val variantLabels = mutableMapOf<String, String>()
        // Tracks which axis entry a following <label> belongs to (labels
        // are children of their colorScheme/variant block).
        var pendingLabelTarget: Pair<MutableMap<String, String>, String>? = null
        val transitions = mutableListOf<EsDeTransitionProfile>()
        val suppressedTransitionProfiles = mutableListOf<String>()
        // The <transitions> block currently being read, if any -- its own
        // six transition tags and its <label>/<selectable> are children,
        // and this flat walk needs to know they belong to it.
        var openTransitions: EsDeTransitionProfile? = null
        var openAnimations = mutableMapOf<EsDeViewTransition, EsDeTransitionAnimation>()
        var inSuppressBlock = false
        val transitionTags = EsDeViewTransition.entries.associateBy { it.tag }
        // ThemeData.cpp:1580-1599: a profile with no name, a name that
        // collides with one of the three built-in animation names, or a
        // name already used by an earlier profile, is dropped entirely.
        val reservedNames = setOf("builtin-instant", "builtin-slide", "builtin-fade")
        fun closeTransitions() {
            val open = openTransitions ?: return
            openTransitions = null
            if (openAnimations.isEmpty()) return
            // ThemeData.cpp:1653-1671: an undeclared startup transition
            // inherits the matching same-view one, when that was declared.
            openAnimations[EsDeViewTransition.SYSTEM_TO_SYSTEM]?.let {
                openAnimations.putIfAbsent(EsDeViewTransition.STARTUP_TO_SYSTEM, it)
            }
            openAnimations[EsDeViewTransition.GAMELIST_TO_GAMELIST]?.let {
                openAnimations.putIfAbsent(EsDeViewTransition.STARTUP_TO_GAMELIST, it)
            }
            transitions += open.copy(animations = openAnimations.toMap())
        }
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "aspectRatio" -> aspectRatios += readText(parser)
                    "fontSize" -> fontSizes += readText(parser)
                    "transitions" -> {
                        closeTransitions()
                        val name = parser.getAttributeValue(null, "name")
                        openAnimations = mutableMapOf()
                        openTransitions =
                            if (name.isNullOrBlank() || name in reservedNames ||
                                transitions.any { it.name == name }
                            ) {
                                null
                            } else {
                                EsDeTransitionProfile(name)
                            }
                    }
                    "selectable" -> openTransitions?.let { open ->
                        // ThemeData.cpp:1605-1611 tests the FIRST character
                        // only, against 0/f/F/n/N.
                        val first = readText(parser).firstOrNull()
                        openTransitions = open.copy(selectable = first !in listOf('0', 'f', 'F', 'n', 'N'))
                    }
                    "suppressTransitionProfiles" -> inSuppressBlock = true
                    "entry" -> if (inSuppressBlock) {
                        // ThemeData.cpp:1730-1740: only the three built-in
                        // names are accepted here at all.
                        readText(parser).takeIf { it in reservedNames }?.let {
                            if (it !in suppressedTransitionProfiles) suppressedTransitionProfiles += it
                        }
                    }
                    in transitionTags.keys -> if (openTransitions != null) {
                        val kind = transitionTags.getValue(parser.name)
                        val raw = readText(parser)
                        // ThemeData.cpp:1618-1630: an empty or unrecognised
                        // value is dropped, not silently read as instant.
                        if (raw.isNotEmpty() && raw in listOf("instant", "slide", "fade")) {
                            openAnimations[kind] = esDeTransitionAnimation(raw)
                        }
                    }
                    "colorScheme" -> parser.getAttributeValue(null, "name")?.let {
                        colorSchemes += it
                        pendingLabelTarget = colorSchemeLabels to it
                    }
                    "variant" -> parser.getAttributeValue(null, "name")?.let {
                        variants += it
                        pendingLabelTarget = variantLabels to it
                    }
                    // A <label> inside a <transitions> block belongs to
                    // THAT profile, not to the last colorScheme/variant
                    // seen (ThemeData.cpp:1675-1700 reads it as a child of
                    // the transitions node).
                    "label" -> {
                        val text = readText(parser)
                        val open = openTransitions
                        if (open != null) {
                            openTransitions = open.copy(label = open.label ?: text)
                        } else {
                            pendingLabelTarget?.let { (map, name) -> map[name] = text }
                        }
                    }
                    "language" -> languages += readText(parser)
                }
            }
            if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "transitions" -> closeTransitions()
                    "suppressTransitionProfiles" -> inSuppressBlock = false
                }
            }
            event = parser.next()
        }
        closeTransitions()
        return EsDeThemeCapabilities(
            // ES-DE validates, de-duplicates and re-orders the declared
            // ratios and PREPENDS "automatic" (ThemeData.cpp:1232-1252,
            // :1766-1775) -- see EsDeAspectRatio.capabilityList. Without
            // that prepend the axis default (front()) was the theme's own
            // first declared ratio and the screen was never consulted at all.
            EsDeAspectRatio.capabilityList(aspectRatios),
            colorSchemes, fontSizes, variants, languages,
            colorSchemeLabels, variantLabels,
            transitions, suppressedTransitionProfiles,
        )
    }

    /**
     * Parses a bare `<theme><variables>...</variables></theme>` overlay
     * fragment -- the real per-system metadata XML format ES-DE themes
     * use (`system/metadata/<id>.xml`), but read standalone rather than
     * as part of a full theme parse. Used by droidtop's own
     * `droidtop-theme-patches` overlay (see `ThemeAssets`'s own doc
     * comment) to supply this same real metadata shape for droidtop's
     * invented engine systems, which no real ES-DE theme has any
     * metadata for at all. Returns an empty map if the file doesn't
     * exist or has no `<variables>` block -- both real, honest "nothing
     * to overlay" cases, not errors.
     */
    fun parseVariablesFragment(fragmentFile: File): Map<String, String> {
        if (!fragmentFile.isFile) return emptyMap()
        val variables = mutableMapOf<String, String>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(fragmentFile.readText()))
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "variables") {
                parseVariables(parser, variables)
            }
            event = parser.next()
        }
        return variables
    }

    /**
     * Real entry point: reads [themeFile]'s sibling `capabilities.xml`
     * (real ES-DE convention: always alongside theme.xml, same
     * directory) and parses using each axis's real front-of-declared-list
     * default -- see [EsDeThemeCapabilities]'s own doc comment. Falls
     * back to [parse]'s own hardcoded defaults for any axis
     * capabilities.xml doesn't declare (or doesn't exist at all).
     *
     * [screenAspectRatio] is the live screen's width/height, ALWAYS in
     * that order, exactly as ES-DE computes it (Renderer.cpp:305) --
     * [ThemeAssets] supplies it from real display metrics. A portrait
     * screen therefore reports a value below 1 and matches the
     * `_vertical` entries of [EsDeAspectRatio.RATIO_MAP], which are
     * height/width for exactly that reason; nothing is flipped by the
     * caller and nothing about the selection is portrait-specific. Real ES-DE convention: a theme's own first-listed
     * aspectRatio capability is very commonly the literal string
     * `"automatic"` (confirmed against real ES-DE source,
     * `ThemeData::loadFile`'s own handling), which means "pick whichever
     * of this theme's OTHER declared aspect ratios is numerically closest
     * to the real device's actual screen" -- not a literal `<aspectRatio
     * name="automatic">` block to match against theme.xml (which no real
     * theme declares). Skipping this real resolution step and using the
     * literal string "automatic" as the selected axis value (an earlier,
     * real bug this fixes) would silently match NO real aspectRatio block
     * in theme.xml at all for any theme using this common convention.
     *
     * [deviceLocale] is the real device's own current locale, real
     * `language_COUNTRY` format matching capabilities.xml's own real
     * entries (e.g. `"en_US"`) -- [ThemeAssets] supplies this from
     * `Locale.getDefault()`. Only consulted when the theme actually
     * declares `<language>` capabilities at all (most views have none to
     * match against either way, matching real ES-DE's own behavior of
     * only running this resolution when `capabilities.languages` is
     * non-empty). Real ES-DE algorithm (`ThemeData::loadFile`): exact
     * `language_COUNTRY` match, else same-language (first two chars)
     * match against a different country, else the real mandatory
     * `"en_US"` fallback -- ported unchanged.
     */
    fun parseWithCapabilities(
        themeFile: File,
        systemTheme: String? = null,
        screenAspectRatio: Float? = null,
        deviceLocale: String? = null,
        systemFullName: String? = null,
        collectionKind: EsDeCollectionKind = EsDeCollectionKind.NONE,
        // The THEME ROOT directory -- where capabilities.xml really lives.
        // Real, confirmed-live bug this parameter fixes: a collection's
        // subfolder theme.xml (Art Book Next's custom-collections/
        // theme.xml) was parsed with capabilities looked up NEXT TO THAT
        // FILE, where no capabilities.xml exists -- so colorScheme fell
        // back to "1", no colorScheme block matched, and every variable
        // those blocks define (systemViewLogoPos/artworkSource/background
        // colors) went unresolved: the collection's logo rendered at the
        // default 0,0 position with a 0.5,0.5 origin (giant, clipped into
        // the top-left corner, over the tab bar) on a black background.
        themeRootDir: File? = null,
        // Real ES-DE parity (Settings "ThemeColorScheme"/"ThemeVariant"):
        // the user's selection, validated against what capabilities.xml
        // actually declares -- an unknown/stale value falls back to the
        // first-declared default rather than selecting nothing.
        colorSchemeOverride: String? = null,
        variantOverride: String? = null,
        // ES-DE's own "ThemeAspectRatio" setting (ThemeData.cpp:739-746):
        // honoured when the theme declares that ratio, otherwise the
        // theme's front() default, which is always "automatic".
        aspectRatioOverride: String? = null,
    ): EsDeTheme {
        val capabilities = parseCapabilities(File(themeRootDir ?: themeFile.parentFile, "capabilities.xml"))
        val resolvedAspectRatio = EsDeAspectRatio.select(
            capabilities = capabilities.aspectRatios,
            setting = aspectRatioOverride,
            screenAspectRatio = screenAspectRatio,
        ).name
        val resolvedLanguage = if (capabilities.languages.isNotEmpty()) {
            resolveLanguage(capabilities.languages, deviceLocale)
        } else {
            null
        }
        return parse(
            themeFile = themeFile,
            aspectRatio = resolvedAspectRatio,
            colorScheme = colorSchemeOverride?.takeIf { it in capabilities.colorSchemes }
                ?: capabilities.colorSchemes.firstOrNull() ?: "1",
            fontSize = capabilities.fontSizes.firstOrNull() ?: "medium",
            variant = variantOverride?.takeIf { it in capabilities.variants }
                ?: capabilities.variants.firstOrNull(),
            systemTheme = systemTheme,
            language = resolvedLanguage,
            systemFullName = systemFullName,
            collectionKind = collectionKind,
        )
    }

    /** Real ES-DE algorithm (`ThemeData::loadFile`): exact match, else same-language-prefix match, else the real mandatory "en_US" fallback. */
    private fun resolveLanguage(declared: List<String>, deviceLocale: String?): String {
        val setting = deviceLocale ?: "en_US"
        if (declared.contains(setting)) return setting
        val prefix = setting.take(2)
        declared.firstOrNull { it.take(2) == prefix }?.let { return it }
        return "en_US"
    }

    fun parse(
        themeFile: File,
        aspectRatio: String = "16:9",
        colorScheme: String = "1",
        fontSize: String = "medium",
        variant: String? = null,
        // Real ES-DE per-system metadata (systemName/systemManufacturer/
        // systemReleaseYear/...) lives in include paths like
        // "./system/metadata/${system.theme}.xml" -- unresolvable, and
        // therefore skipped (see resolveIncludePath), for a theme-wide
        // parse with no system context. Real ES-DE itself resolves these
        // by parsing per system, substituting the real system id here
        // before parsing starts, not by a single global parse -- this
        // parameter is that same real per-system parse, used by
        // ThemeAssets to get one EsDeTheme per system id rather than one
        // theme-wide object with these fields permanently unresolved.
        systemTheme: String? = null,
        // Real ES-DE resolution (see parseWithCapabilities' own doc
        // comment) -- null means "no real language match," matching
        // real ES-DE's own behavior for a theme that declares NO
        // <language> capabilities at all (most themes/views -- language-
        // scoped blocks simply don't exist to match against either way).
        language: String? = null,
        // Real ES-DE `system.name`/`system.fullName` variables (confirmed
        // against `SystemData.cpp`'s own real `sysData.insert(...)`
        // calls, a real local clone kept at /root/es-de-reference) --
        // previously entirely unpopulated (only `system.theme` was),
        // a real, confirmed-live gap (any theme text using
        // `${system.fullName}` rendered the raw placeholder literally,
        // caught on-device). Both variables resolve to the same real
        // display string here -- droidtop has no separate "short
        // internal name" vs. "display name" distinction real ES-DE's
        // own `name`/`fullName` split serves, so inventing one wouldn't
        // be real. Not populated: the real
        // The `.autoCollections`/`.customCollections`/`.noCollections`
        // suffixed forms come from the same place now -- see
        // [collectionKind] below and [EsDeSystemVariables].
        systemFullName: String? = null,
        // Which of ES-DE's two collection kinds this system is, or NONE
        // for a real system. Decides which of the three mutually
        // exclusive `${system.*.<kind>Collections}` variable families
        // carries a value and which two carry ES-DE's skip flag
        // (SystemData.cpp:1978-2032).
        collectionKind: EsDeCollectionKind = EsDeCollectionKind.NONE,
    ): EsDeTheme {
        val axes = listOf(
            VariantAxis("variant", variant),
            VariantAxis("colorScheme", colorScheme),
            VariantAxis("fontSize", fontSize),
            // Empty = the theme declared no aspect ratios, so no
            // <aspectRatio> block is applied at all -- ES-DE's own
            // early return in parseAspectRatios (ThemeData.cpp:2036).
            VariantAxis("aspectRatio", aspectRatio.ifEmpty { null }),
            VariantAxis("language", language),
        )
        val variables = mutableMapOf<String, String>()
        // Every `${system.*}` variable, suffixed forms included -- see
        // [EsDeSystemVariables], which is SystemData.cpp:1959-2032 itself.
        variables.putAll(EsDeSystemVariables.forSystem(systemFullName, systemTheme, collectionKind))
        val views = mutableMapOf<String, MutableMap<String, EsDeThemeElement>>()
        parseDocument(themeFile, axes, variables, views, depth = 0)
        return EsDeTheme(variables, views.mapValues { EsDeThemeView(it.value) })
    }

    private fun parseDocument(
        themeFile: File,
        axes: List<VariantAxis>,
        variables: MutableMap<String, String>,
        views: MutableMap<String, MutableMap<String, EsDeThemeElement>>,
        depth: Int,
    ) {
        // Real safety net against include cycles or pathological nesting --
        // ES-DE's own theme files never nest anywhere close to this deep.
        if (depth >= MAX_INCLUDE_DEPTH || !themeFile.isFile) return
        val baseDir = themeFile.parentFile ?: File(".")

        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(themeFile.readText()))
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                parseNode(parser, axes, baseDir, variables, views, depth)
            }
            event = parser.next()
        }
    }

    /**
     * Dispatches ONE already-positioned START_TAG node -- either a real
     * variant axis (see [VariantAxis]: matches and descends, or skips),
     * or one of the fixed structural tags (`variables`/`view`/`include`),
     * or anything else (silently skipped by the caller's own loop just
     * moving past it, same permissive-subset behavior as an unrecognized
     * element/property elsewhere in this parser).
     */
    private fun parseNode(
        parser: XmlPullParser,
        axes: List<VariantAxis>,
        baseDir: File,
        variables: MutableMap<String, String>,
        views: MutableMap<String, MutableMap<String, EsDeThemeElement>>,
        depth: Int,
    ) {
        val axis = axes.find { it.tagName == parser.name }
        when {
            axis != null -> {
                val rawName = parser.getAttributeValue(null, "name")
                // Real, confirmed-live bug this fixes: a variant/colorScheme/
                // fontSize/aspectRatio block's own `name` attribute can be a
                // real, comma-separated list declaring itself for MULTIPLE
                // axis values at once -- exactly [parseView] already
                // splits its own `name` attribute for (see [splitNames]) --
                // confirmed via decaffe's own theme.xml, whose real
                // metadata-sidebar/description/preview content lives inside
                // <variant name="solidWithoutMeta, solidWithMeta">. Plain
                // string equality against the RAW, unsplit name here meant
                // that block (and any other multi-name variant/colorScheme/
                // fontSize/aspectRatio block) never matched any single
                // selected value, silently skipping the vast majority of
                // decaffe's real system-view content -- confirmed live via
                // a real device screenshot missing the metadata sidebar,
                // description panel, game preview, and bottom info bar
                // real ES-DE's own bundled reference screenshot shows.
                val names = splitNames(rawName ?: "")
                // Real ES-DE ThemeData::parseVariants (confirmed against
                // real source): `name == "all"` always matches, real
                // themes rely on this for content shared across every
                // variant -- confirmed this "all" rule is unique to the
                // variant axis specifically (parseColorSchemes/
                // parseFontSizes/parseAspectRatios use plain equality
                // only, no "all" special case for those three).
                val matches = axis.selected != null &&
                    (axis.selected in names || (axis.tagName == "variant" && "all" in names))
                if (matches) {
                    parseScopedBlock(parser, axes, baseDir, variables, views, depth)
                } else {
                    skipSubtree(parser)
                }
            }
            parser.name == "variables" -> parseVariables(parser, variables)
            parser.name == "view" -> parseView(parser, variables, baseDir, views)
            parser.name == "include" -> {
                val rawPath = resolvePlaceholders(readText(parser), variables)
                resolveIncludePath(baseDir, rawPath)?.let { included ->
                    parseDocument(included, axes, variables, views, depth + 1)
                }
            }
        }
    }

    /**
     * A matched variant block's own children are the same real node types
     * a whole document can have -- walked with the exact same [parseNode]
     * dispatch, just scoped to this element's subtree instead of the
     * whole file. This is what makes every axis mutually recursive with
     * every other one for free: a `<colorScheme>` matched here can itself
     * contain an `<aspectRatio>`, which hits this same function again.
     */
    private fun parseScopedBlock(
        parser: XmlPullParser,
        axes: List<VariantAxis>,
        baseDir: File,
        variables: MutableMap<String, String>,
        views: MutableMap<String, MutableMap<String, EsDeThemeElement>>,
        depth: Int,
    ) {
        val blockDepth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == blockDepth)) {
            if (event == XmlPullParser.START_TAG) {
                parseNode(parser, axes, baseDir, variables, views, depth)
            }
            event = parser.next()
        }
    }

    /**
     * Null when the resolved path still contains an unresolved `${...}`
     * placeholder (real ES-DE cases like `${system.theme}.xml` need a
     * per-system context this theme-wide parse doesn't have -- see
     * [EsDeThemeParser]'s own doc comment) or when nothing exists at that
     * path. Both are expected/real, not error conditions -- skipping the
     * include is the honest behavior rather than crashing the whole parse.
     */
    private fun resolveIncludePath(baseDir: File, rawPath: String): File? {
        if (rawPath.contains("\${")) return null
        val file = File(baseDir, rawPath).normalize()
        return file.takeIf { it.isFile }
    }

    /**
     * Real bug, confirmed live on-device: a theme-defined `<variables>`
     * block (real ES-DE's own generic custom-variable mechanism -- Art
     * Book Next's bundled `_metadata-global/_default.xml` defines
     * `systemDescription`/`systemHardwareType`/`systemColor`/... this
     * way) commonly references an already-known variable inside its own
     * definition, e.g. `<systemDescription>Play ${system.fullName}
     * </systemDescription>`. This used to store that raw, unresolved
     * text verbatim -- any theme using this real, common pattern showed
     * the literal `${system.fullName}` placeholder on screen instead of
     * a real value. `resolvePlaceholders` against the variables map
     * accumulated SO FAR (real ES-DE's own actual behavior: variables
     * resolve in document order, a later `<variables>` block can
     * reference an earlier one's real value, not the other way around)
     * fixes it -- matching how every other raw-text property already
     * gets resolved (`parseElementProperties`'s own `rawText`).
     */
    private fun parseVariables(parser: XmlPullParser, variables: MutableMap<String, String>) {
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.START_TAG) {
                val name = parser.name
                val text = readText(parser)
                variables[name] = resolvePlaceholders(text, variables)
            }
            event = parser.next()
        }
    }

    private fun parseView(
        parser: XmlPullParser,
        variables: Map<String, String>,
        baseDir: File,
        views: MutableMap<String, MutableMap<String, EsDeThemeElement>>,
    ) {
        val viewNames = splitNames(parser.getAttributeValue(null, "name") ?: "")
            .flatMap { if (it == "all") listOf("system", "gamelist") else listOf(it) }
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.START_TAG) {
                val elementType = parser.name
                val schema = ES_DE_ELEMENT_SCHEMA[elementType]
                if (schema == null) {
                    skipSubtree(parser)
                } else {
                    val nameAttr = parser.getAttributeValue(null, "name") ?: ""
                    val properties = parseElementProperties(parser, schema, variables, baseDir)
                    for (instanceName in splitNames(nameAttr)) {
                        val key = "${elementType}_$instanceName"
                        // Real ES-DE behavior (confirmed against ThemeData::parseElement):
                        // the same named element re-declared later (e.g. a <variant>-scoped
                        // block overriding just staticImage/imageColor on top of a base
                        // <view> block's pos/size/origin) MERGES its properties onto the
                        // existing element, it doesn't replace it wholesale. A real,
                        // confirmed bug this fixes: a plain map `put` here was wiping out
                        // decaffe's own carousel's pos/size/origin every time its later
                        // variant-scoped redeclaration (staticImage/imageColor only) was
                        // parsed, making the carousel fall back to sizeOf/positionOf's
                        // (0.2, 0.2)/(0,0) defaults -- confirmed live via a real debug log
                        // on-device (rawSize=null rawPos=null rawOrigin=null), not guessed.
                        for (viewName in viewNames) {
                            val viewElements = views.getOrPut(viewName) { mutableMapOf() }
                            val existingProperties = viewElements[key]?.properties ?: emptyMap()
                            viewElements[key] = EsDeThemeElement(elementType, key, existingProperties + properties)
                        }
                    }
                }
            }
            event = parser.next()
        }
    }

    private fun parseElementProperties(
        parser: XmlPullParser,
        schema: Map<String, EsDePropertyType>,
        variables: Map<String, String>,
        baseDir: File,
    ): Map<String, EsDeThemeValue> {
        val properties = mutableMapOf<String, EsDeThemeValue>()
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.START_TAG) {
                val propName = parser.name
                // Real ES-DE attribute-keyed property (confirmed against
                // sPropertyAttributeMap, ThemeData.cpp:144-154) -- the
                // attribute value MUST be read here, before readText()
                // advances the parser past this tag's own START_TAG
                // position, or getAttributeValue has nothing to read. A
                // real, confirmed-live bug this fixes: this read never
                // happened at all before, so a real theme's own
                // `<customBadgeIcon badge="kidgame">` declarations were
                // silently unparseable regardless of what the schema
                // declared -- confirmed via the bundled decaffe theme's
                // own six real customBadgeIcon/customControllerIcon
                // declarations, none of which ever reached
                // EsDeThemedBadges' own `badge_$slot` lookup.
                val attributeMapping = ES_DE_PROPERTY_ATTRIBUTE_MAP[propName]
                val attrValue = attributeMapping?.let { (attrName, _) -> parser.getAttributeValue(null, attrName) }
                val rawText = resolvePlaceholders(readText(parser), variables)
                val propType = schema[propName]
                // ES-DE's own mutually-exclusive-variable rule
                // (ThemeData.cpp:2249-2256): a property whose resolved text
                // is the backspace flag is skipped outright, leaving the
                // property unset rather than setting it to a stray control
                // character. This is what makes a theme's
                // `${system.fullName.noCollections}` text element draw
                // nothing at all on a collection.
                if (propType != null && rawText.isNotBlank() && rawText != EsDeSystemVariables.NOT_APPLICABLE) {
                    coerce(propType, rawText, baseDir)?.let { value ->
                        val key = if (attributeMapping != null && attrValue != null) {
                            "${attributeMapping.second}_$attrValue"
                        } else {
                            propName
                        }
                        properties[key] = value
                    }
                }
            }
            event = parser.next()
        }
        return properties
    }

    private fun coerce(type: EsDePropertyType, raw: String, baseDir: File): EsDeThemeValue? = when (type) {
        EsDePropertyType.NORMALIZED_PAIR -> {
            val parts = raw.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                EsDeThemeValue.Pair(parts[0].toFloatOrNull() ?: 0f, parts[1].toFloatOrNull() ?: 0f)
            } else {
                null
            }
        }
        // Real ES-DE convention (confirmed against real source): a PATH
        // value starting with ':' refers to one of ES-DE's own bundled
        // application resources (its built-in icon/font set), resolved
        // via ResourceManager rather than as a theme-relative file.
        // droidtop has no equivalent bundled ES-DE-wide resource pool --
        // stripping the leading ':' and still resolving relative to the
        // theme's own directory is an honest, real fallback (a theme
        // referencing its OWN bundled assets under that same convention
        // still resolves), not a claim of full parity with ES-DE's real
        // built-in resource set.
        EsDePropertyType.PATH -> EsDeThemeValue.Path(File(baseDir, raw.removePrefix(":")).normalize().path)
        EsDePropertyType.STRING -> EsDeThemeValue.Str(raw)
        EsDePropertyType.COLOR -> parseHexColor(raw)?.let { EsDeThemeValue.Color(it) }
        EsDePropertyType.UNSIGNED_INTEGER -> raw.toLongOrNull()?.let { EsDeThemeValue.UInt(it) }
        EsDePropertyType.FLOAT -> raw.toFloatOrNull()?.let { EsDeThemeValue.FloatValue(it) }
        EsDePropertyType.BOOLEAN -> EsDeThemeValue.Bool(raw.equals("true", ignoreCase = true))
    }

    /** Same rule as ES-DE's own getHexColor: 6 hex digits get 0xFF alpha appended, 8 digits are used as-is (RRGGBBAA). */
    private fun parseHexColor(raw: String): Long? {
        val clean = raw.trim().removePrefix("#")
        if (clean.length != 6 && clean.length != 8) return null
        val value = clean.toLongOrNull(16) ?: return null
        return if (clean.length == 6) (value shl 8) or 0xFF else value
    }

    /**
     * Real ES-DE ThemeData::resolvePlaceholders behavior (confirmed
     * against real source): finds only the FIRST `${...}`, substitutes
     * its resolved value LITERALLY (never re-scanned for further `${}`
     * inside it), then recurses only on the trailing suffix after that
     * match. The earlier version of this function re-scanned the WHOLE
     * result (prefix + substituted value + suffix) on every iteration,
     * which would further expand a `${}` that happened to appear INSIDE
     * a variable's own resolved value -- real ES-DE leaves that literal.
     */
    private val PLACEHOLDER_REGEX = Regex("\\$\\{([^}]*)\\}")

    private fun resolvePlaceholders(raw: String, variables: Map<String, String>): String {
        val match = PLACEHOLDER_REGEX.find(raw) ?: return raw
        val varName = match.groupValues[1]
        val prefix = raw.substring(0, match.range.first)
        val replacement = variables[varName] ?: ""
        val suffix = raw.substring(match.range.last + 1)
        return prefix + replacement + resolvePlaceholders(suffix, variables)
    }

    private fun splitNames(nameAttr: String): List<String> =
        nameAttr.split(Regex("[\\s,]+")).filter { it.isNotBlank() }

    /** Reads the text content of the current START_TAG element and advances past its END_TAG. */
    private fun readText(parser: XmlPullParser): String {
        val depth = parser.depth
        val sb = StringBuilder()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.TEXT) sb.append(parser.text)
            event = parser.next()
        }
        return sb.toString().trim()
    }

    private fun skipSubtree(parser: XmlPullParser) {
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            event = parser.next()
        }
    }
}
