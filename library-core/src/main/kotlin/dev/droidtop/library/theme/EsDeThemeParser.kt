package dev.droidtop.library.theme

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader

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
 *    content (variables/views/nested includes) as if it were inlined at
 *    that point -- confirmed real: DEcaffe's own theme.xml is mostly a
 *    shell of `<include>` tags (colors.xml, font.xml, per-aspect-ratio
 *    layout files, per-system metadata files), not inline content. Missing
 *    this entirely was a real bug: most of a real theme's variables and
 *    view elements never got parsed at all, since they live in included
 *    files. `<aspectRatio name="...">` wraps includes that only apply for
 *    a matching device aspect ratio (ES-DE's own real per-shape-screen
 *    layout mechanism) -- only the block matching [parse]'s `aspectRatio`
 *    argument is followed, others are skipped entirely.
 */
object EsDeThemeParser {
    private const val MAX_INCLUDE_DEPTH = 24

    fun parse(themeFile: File, aspectRatio: String = "16:9"): EsDeTheme {
        val variables = mutableMapOf<String, String>()
        val views = mutableMapOf<String, MutableMap<String, EsDeThemeElement>>()
        parseDocument(themeFile, aspectRatio, variables, views, depth = 0)
        return EsDeTheme(variables, views.mapValues { EsDeThemeView(it.value) })
    }

    private fun parseDocument(
        themeFile: File,
        aspectRatio: String,
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
                when (parser.name) {
                    "variables" -> parseVariables(parser, variables)
                    "view" -> parseView(parser, variables, baseDir, views)
                    "include" -> {
                        val rawPath = resolvePlaceholders(readText(parser), variables)
                        resolveIncludePath(baseDir, rawPath)?.let { included ->
                            parseDocument(included, aspectRatio, variables, views, depth + 1)
                        }
                    }
                    "aspectRatio" -> {
                        val name = parser.getAttributeValue(null, "name")
                        if (name == aspectRatio) {
                            parseAspectRatioBlock(parser, aspectRatio, baseDir, variables, views, depth)
                        } else {
                            skipSubtree(parser)
                        }
                    }
                }
            }
            event = parser.next()
        }
    }

    /**
     * An `<aspectRatio>` block's own children are the same top-level tags a
     * theme document can have (mostly `<include>` in practice) -- walked
     * the same way [parseDocument] walks a whole file, just scoped to this
     * subtree instead of the whole document.
     */
    private fun parseAspectRatioBlock(
        parser: XmlPullParser,
        aspectRatio: String,
        baseDir: File,
        variables: MutableMap<String, String>,
        views: MutableMap<String, MutableMap<String, EsDeThemeElement>>,
        depth: Int,
    ) {
        val blockDepth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == blockDepth)) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "variables" -> parseVariables(parser, variables)
                    "view" -> parseView(parser, variables, baseDir, views)
                    "include" -> {
                        val rawPath = resolvePlaceholders(readText(parser), variables)
                        resolveIncludePath(baseDir, rawPath)?.let { included ->
                            parseDocument(included, aspectRatio, variables, views, depth + 1)
                        }
                    }
                }
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
