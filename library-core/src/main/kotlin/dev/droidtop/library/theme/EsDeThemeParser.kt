package dev.droidtop.library.theme

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader

/**
 * One real ES-DE "variant axis" -- confirmed against ES-DE's own
 * ThemeData.cpp (`parseVariants`/`parseColorSchemes`/`parseFontSizes`, and
 * `aspectRatio` handled the same way inline): a tag with a `name`
 * attribute, matched against a currently-selected value for that axis,
 * mutually recursive with every OTHER axis -- a `<colorScheme>` block can
 * contain an `<aspectRatio>` block and vice versa, since each of ES-DE's
 * own parse functions re-invokes every other one on a matched subtree,
 * not just its own tag type. [selected] `null` means no selection exists
 * for this axis at all -- every block for that tag name is skipped
 * unconditionally (real for `language`: no per-user language selection to
 * thread through yet).
 *
 * Earlier revisions of this parser handled aspectRatio/colorScheme/
 * fontSize as three near-identical, hand-duplicated `when` branches
 * (found and fixed one at a time as each one's absence caused a real,
 * separately-diagnosed rendering bug) -- this list is the fix for *that*
 * pattern, not just the individual bugs: a new axis is one line here, not
 * a new branch duplicated across every place a theme document gets
 * walked.
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
object EsDeThemeParser {
    private const val MAX_INCLUDE_DEPTH = 24

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
    ): EsDeTheme {
        val axes = listOf(
            VariantAxis("variant", variant),
            VariantAxis("colorScheme", colorScheme),
            VariantAxis("fontSize", fontSize),
            VariantAxis("aspectRatio", aspectRatio),
            VariantAxis("language", null),
        )
        val variables = mutableMapOf<String, String>()
        if (systemTheme != null) variables["system.theme"] = systemTheme
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
                val name = parser.getAttributeValue(null, "name")
                if (axis.selected != null && name == axis.selected) {
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

    private fun parseVariables(parser: XmlPullParser, variables: MutableMap<String, String>) {
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.START_TAG) {
                val name = parser.name
                val text = readText(parser)
                variables[name] = text
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
                        val element = EsDeThemeElement(elementType, key, properties)
                        for (viewName in viewNames) {
                            views.getOrPut(viewName) { mutableMapOf() }[key] = element
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
                val rawText = resolvePlaceholders(readText(parser), variables)
                val propType = schema[propName]
                if (propType != null && rawText.isNotBlank()) {
                    coerce(propType, rawText, baseDir)?.let { properties[propName] = it }
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
        EsDePropertyType.PATH -> EsDeThemeValue.Path(File(baseDir, raw).normalize().path)
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

    private fun resolvePlaceholders(raw: String, variables: Map<String, String>): String {
        var result = raw
        val regex = Regex("\\$\\{([^}]*)\\}")
        var match = regex.find(result)
        var guard = 0
        while (match != null && guard < 10) {
            val varName = match.groupValues[1]
            result = result.replaceRange(match.range, variables[varName] ?: "")
            match = regex.find(result)
            guard++
        }
        return result
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
