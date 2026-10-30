package dev.droidtop.library.theme

import dev.droidtop.notices.ColorMath

/**
 * Every colour role the Gaming chrome draws, as ARGB ints, plus the theme's
 * own typefaces and background texture. `:shell-gamepad`'s design tokens
 * (`MenuTokens`, the Material scheme) are this and nothing else, so one
 * theme change moves every surface droidtop draws itself.
 *
 * [DEFAULT] is droidtop's own palette, the exact values the chrome used
 * before any theme reached it. [declared] is false for it.
 *
 * Serializable so [ThemeColorStore] can keep a resolved palette across process starts (docs/SPEC.md 7k, "Colours
 * outside Gaming").
 */
@kotlinx.serialization.Serializable
data class GamingThemeColors(
    val ground: Int,
    val overlaySurface: Int,
    val card: Int,
    val cardFocused: Int,
    val cardInset: Int,
    val hintBar: Int,
    val onSurface: Int,
    val onSurfaceMuted: Int,
    val value: Int,
    val placeholder: Int,
    val sectionLabel: Int,
    val onSurfaceDisabled: Int,
    val accent: Int,
    val onAccent: Int,
    val danger: Int,
    val affirmative: Int,
    val favourite: Int,
    val rowFill: Int,
    val rowFillSelected: Int,
    val cardOutline: Int,
    val hintPillOutline: Int,
    val selected: Int,
    val onSelected: Int,
    val scrim: Int,
    val dangerPlate: Int,
    val launch: Int,
    val launchFocused: Int,
    val launchDisabled: Int,
    val onLaunchMuted: Int,
    /** The theme's UI typeface (its help-row font), an existing file, or null. */
    val bodyFontPath: String? = null,
    /** The theme's display typeface (its carousel or title font), or null. */
    val displayFontPath: String? = null,
    /** A tiled background image the theme lays under its ground, or null. */
    val textureImagePath: String? = null,
    val declared: Boolean = true,
) {
    /** True when the ground is light, so text is dark and the Material scheme is a light one. */
    val isLight: Boolean get() = ColorMath.luminance(ground) > 0.4

    companion object {
        /**
         * droidtop's own palette, from the approved visual language (owner, 2026-10-24, Droidtop/tracker#644,
         * coordination/design/proposals/2026-10-24-menus-and-visual-language.md 4.1 and 4.2): a blue-grey ground, never
         * pure black, and three surfaces one luminance step apart (cards, sheets, menus and dialogs); text that is not
         * pure white; one azure accent, used for focus, the primary button and what is chosen. It is the fallback: an
         * ES-DE theme's own colours win wherever it declares them ([GamingThemeMapping]).
         */
        val DEFAULT = GamingThemeColors(
            ground = 0xFF11151C.toInt(),
            overlaySurface = 0xFF1E2430.toInt(),
            card = 0xFF181D26.toInt(),
            cardFocused = 0xFF262D3A.toInt(),
            cardInset = 0xFF141920.toInt(),
            hintBar = 0xFF151A21.toInt(),
            onSurface = 0xFFF2F5F9.toInt(),
            onSurfaceMuted = 0xFFA9B4C4.toInt(),
            value = 0xFFC9D1DC.toInt(),
            placeholder = 0xFF95A0AF.toInt(),
            sectionLabel = 0xFFA9B4C4.toInt(),
            // 40 percent of the text, as the visual language has it.
            onSurfaceDisabled = 0x66F2F5F9,
            accent = 0xFF3BB5FF.toInt(),
            onAccent = 0xFF0B1220.toInt(),
            danger = 0xFFFF5A5F.toInt(),
            affirmative = 0xFF4CD28A.toInt(),
            favourite = 0xFFFFD700.toInt(),
            rowFill = 0x0DFFFFFF,
            // A focused row: the accent at 22 percent behind it (4.5, "Focus").
            rowFillSelected = 0x383BB5FF,
            cardOutline = 0x1FFFFFFF,
            hintPillOutline = 0x33FFFFFF,
            selected = 0xFFF2F5F9.toInt(),
            onSelected = 0xFF11151C.toInt(),
            scrim = 0xCC000000.toInt(),
            // The ground with the danger mixed in at 22 percent, at 80 percent over the page.
            dangerPlate = 0xCC45242B.toInt(),
            // The primary button: the ground with the accent at 40 percent, its label in the text colour (7.3:1).
            launch = 0xFF225577.toInt(),
            launchFocused = 0xFF3B6887.toInt(),
            launchDisabled = 0xFF141920.toInt(),
            onLaunchMuted = 0xFFD7E3EE.toInt(),
            declared = false,
        )
    }
}

/**
 * ONE mapping from the active ES-DE theme to droidtop's Gaming design
 * tokens (docs/SPEC.md "Gaming theming").
 *
 * The input is an already-parsed [EsDeTheme], so the theme's selected
 * variant, colour scheme and aspect ratio have been applied by the parser
 * before this runs: `${...}` variables are resolved, and only the blocks
 * of the chosen scheme are present. Nothing here re-reads a theme file.
 *
 * What it reads, in order, and what happens when the theme declares none
 * of it (that role keeps droidtop's own value, derived from the colours
 * that ARE declared so the set stays coherent):
 *
 * - **Ground**: an `image` named `background` that declares a colour
 *   (Art Book Next, Slate), else a full-screen opaque `image` colour, else
 *   the theme's own `backgroundColor`/`systemBackgroundColor` variable.
 * - **Text**: the colour of the `clock`, `systemstatus`, carousel text and
 *   `text` elements of the system view (the text a theme draws straight on
 *   its ground), then the help row's, then a gamelist's.
 * - **Secondary text**: the `helpsystem` `textColor`.
 * - **Menu surface**: the `helpsystem` `backgroundColor`, which is the
 *   plate ES-DE itself draws menus-and-hints on.
 * - **Accent**: the first colour with real chroma among the help icons,
 *   the list's selected and selector colours, the carousel and the text
 *   colours, else the text colour (a neutral theme gets a neutral ring).
 * - **Fonts**: the help row's `fontPath` for UI text, the carousel's or
 *   first title text's for headings.
 * - **Texture**: a tiled `image_background` path that exists on disk.
 *
 * Every text role is then forced to WCAG AA (4.5:1) over every surface it
 * is drawn on, by the smallest move toward white or black that gets there
 * ([ColorMath.ensure]); roles that are fills (launch) give way to
 * their label instead. A theme that declares nothing of the above is
 * [GamingThemeColors.DEFAULT], untouched.
 */
object GamingThemeMapping {
    /** WCAG AA for body text. */
    const val TEXT_FLOOR = ColorMath.TEXT_FLOOR

    /** Disabled labels are held to the large-text floor, as they always were. */
    const val DISABLED_FLOOR = 3.0

    private val GROUND_VARIABLES = listOf("backgroundColor", "systemBackgroundColor")
    private val TITLE_TYPES = setOf("text", "textlist", "gamelistinfo")

    private class Declared(
        var ground: Int? = null,
        var texturePath: String? = null,
        var primary: Int? = null,
        var secondary: Int? = null,
        var surface: Int? = null,
        var accent: Int? = null,
        var bodyFont: String? = null,
        var displayFont: String? = null,
    ) {
        val any: Boolean
            get() = ground != null || primary != null || secondary != null || surface != null ||
                accent != null || bodyFont != null || displayFont != null
    }

    /**
     * Whether the theme draws its own clock or system status in [view] (`system` or `gamelist`), docs/SPEC.md 7k2,
     * "Gaming theming". The shell's own floating status cluster yields to it there, so the two never overlap, and shows
     * on every other view: a theme with a clock only on its system view leaves the gamelist with droidtop's
     * (Droidtop/tracker#658). The `all` view's elements count for a view the theme draws; a view it does not draw
     * (droidtop's own gamelist then) has no theme status. A `scope` that keeps the element out of a plain view
     * (`menu`, `none`) does not count: it would not be on screen to overlap.
     */
    fun drawsOwnStatus(theme: EsDeTheme?, view: String): Boolean {
        val own = theme?.views?.get(view) ?: return false
        return listOfNotNull(own, theme.views["all"]).any { v ->
            v.elements.values.any {
                (it.type == "clock" || it.type == "systemstatus") && esDeScopeAllows(it, menuOpen = false)
            }
        }
    }

    /** The views of [theme] that draw their own clock or status ([drawsOwnStatus]). */
    fun ownStatusViews(theme: EsDeTheme?): Set<String> = setOf("system", "gamelist").filterTo(HashSet()) { drawsOwnStatus(theme, it) }

    /** Body text under High contrast (docs/SPEC.md 7k, "High contrast"): WCAG AAA. */
    const val HIGH_CONTRAST_FLOOR = 7.0

    /**
     * [colors] under High contrast: every text role moved the shortest way to 7:1 over every surface it is drawn
     * on (contrast wins over the theme's hue when both cannot hold). Pure.
     */
    fun highContrast(colors: GamingThemeColors): GamingThemeColors {
        val m = ColorMath
        val surfaces = listOf(colors.ground, colors.overlaySurface, colors.card, colors.cardFocused, colors.cardInset, colors.hintBar)
        fun strong(fg: Int) = m.ensure(fg, surfaces, HIGH_CONTRAST_FLOOR)
        return colors.copy(
            onSurface = strong(colors.onSurface),
            onSurfaceMuted = strong(colors.onSurfaceMuted),
            value = strong(colors.value),
            placeholder = strong(colors.placeholder),
            sectionLabel = strong(colors.sectionLabel),
            accent = strong(colors.accent),
        )
    }

    /**
     * The palette for [theme] in the appearance asked for: null [light] is the theme as it is. A theme whose ground is
     * the other appearance's (no colour scheme of it matched, ThemeAssets) keeps its accent and fonts over droidtop's
     * own ground for [light], and its text colours, written for the other ground, are derived again; its texture is
     * left out (docs/SPEC.md 7k, "Light and dark").
     */
    fun map(theme: EsDeTheme?, light: Boolean? = null): GamingThemeColors {
        if (theme == null) return defaultFor(light == true)
        val declared = read(theme)
        if (!declared.any) return defaultFor(light == true)
        val built = build(declared)
        if (light == null || built.isLight == light) return built
        return build(
            Declared(
                ground = defaultFor(light).ground,
                accent = declared.accent,
                bodyFont = declared.bodyFont,
                displayFont = declared.displayFont,
            ),
        )
    }

    /**
     * [map], then High contrast when it is on: the colours a palette draws with for one parse of the active theme
     * (docs/SPEC.md 7k). [schemeNamed] is whether the person named the theme's colour scheme: a named scheme is drawn
     * as the theme wrote it; otherwise the palette follows [light]. [tintAccent], when "Tint themes to my colours" is
     * on, swaps the theme's accent and highlight ([tint]). The one place both the Gaming palette and the
     * colours outside Gaming ([ThemeColorStore]) are worked out from a theme. Pure.
     */
    fun resolved(theme: EsDeTheme?, light: Boolean, schemeNamed: Boolean, highContrast: Boolean, tintAccent: Int? = null): GamingThemeColors {
        val mapped = map(theme, light = light.takeIf { !schemeNamed })
        // A tint swaps a theme's accent and highlight; with no theme there is nothing to tint.
        val tinted = if (tintAccent == null || theme == null) mapped else tint(mapped, tintAccent)
        return if (highContrast) highContrast(tinted) else tinted
    }

    /**
     * "Tint themes to my colours" (docs/SPEC.md 7k, "Colours outside Gaming"; Droidtop/tracker#844): [colors] with only
     * its accent and highlight swapped for [accent], the active colour source's: the accent (held to 4.5:1 on the
     * theme's own surfaces by lightness alone), the ink on it, and the focused row, which is the accent at 22 percent.
     * Every other role, the grounds, surfaces, text, the primary button, typefaces and texture, stays the theme's. Pure.
     */
    fun tint(colors: GamingThemeColors, accent: Int): GamingThemeColors {
        val m = ColorMath
        val ground = colors.ground
        val ink = if (m.ratio(m.WHITE, ground) >= m.ratio(m.BLACK, ground)) m.WHITE else m.BLACK
        val surfaces = listOf(
            ground, colors.overlaySurface, colors.card, colors.cardFocused, colors.cardInset, colors.hintBar,
            m.mix(colors.overlaySurface, ink, 0.17),
        )
        val held = m.ensure(accent, surfaces, TEXT_FLOOR)
        // Declared, so a theme that names no colours still draws the tint (its Material scheme from these roles).
        return colors.copy(accent = held, onAccent = m.pickOn(held), rowFillSelected = m.withAlpha(held, 0x38), declared = true)
    }

    /**
     * Android's system palette (Android 12 and later, "Device colours"), as the tones Android publishes for its first
     * accent and first neutral family: [accent1] and [neutral1] each hold [TONES] in that order (`system_accent1_0`
     * through `system_accent1_1000`).
     */
    data class DevicePalette(val accent1: List<Int>, val neutral1: List<Int>) {
        fun accent(tone: Int): Int = accent1[TONES.indexOf(tone)]

        fun neutral(tone: Int): Int = neutral1[TONES.indexOf(tone)]

        companion object {
            /** The tones Android's system palette has, lightest first. */
            val TONES = listOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)
        }
    }

    /**
     * The one mapping from Android's system palette to droidtop's roles (docs/SPEC.md 7k, "Colours outside Gaming";
     * Droidtop/tracker#844), the way Material You lays it out for [light] or dark: the ground and the sheets from the
     * neutral family (tone 50 and 100 light, 900 and 800 dark), the accent from the accent family (600 light, 200 dark),
     * and the focused row from the accent's container shade (100 light, 700 dark). Every other role is derived as a
     * theme's are ([build]): text held to 4.5:1 on every surface, the accent held by lightness alone. Pure.
     */
    fun fromDevicePalette(palette: DevicePalette, light: Boolean): GamingThemeColors {
        val built = build(
            Declared(
                ground = palette.neutral(if (light) 50 else 900),
                surface = palette.neutral(if (light) 100 else 800),
                accent = palette.accent(if (light) 600 else 200),
            ),
        )
        return built.copy(rowFillSelected = ColorMath.withAlpha(palette.accent(if (light) 100 else 700), 0x99))
    }

    /**
     * [colors] with [accent] as its accent, every role the accent decides worked out again the way [build] does it (the
     * accent held to 4.5:1 on the surfaces by lightness alone, the focused row, the primary button and its label): the
     * Custom droidtop theme's palette (docs/SPEC.md 7k, "Colours outside Gaming"). The same accent gives [colors]
     * back unchanged. Pure.
     */
    fun withAccent(colors: GamingThemeColors, accent: Int): GamingThemeColors {
        if (accent == colors.accent) return colors
        val m = ColorMath
        val ground = colors.ground
        val ink = if (m.ratio(m.WHITE, ground) >= m.ratio(m.BLACK, ground)) m.WHITE else m.BLACK
        val surfaces = listOf(
            ground, colors.overlaySurface, colors.card, colors.cardFocused, colors.cardInset, colors.hintBar,
            m.mix(colors.overlaySurface, ink, 0.17),
        )
        val held = m.ensure(accent, surfaces, TEXT_FLOOR)
        val primary = colors.onSurface
        val awayFromInk = if (m.luminance(primary) > 0.4) m.BLACK else m.WHITE
        val launch = m.ensureFill(m.mix(ground, held, 0.40), primary, TEXT_FLOOR, awayFromInk)
        val launchFocused = m.ensureFill(m.mix(launch, primary, 0.12), primary, TEXT_FLOOR, awayFromInk)
        return colors.copy(
            accent = held,
            onAccent = m.pickOn(held),
            rowFillSelected = m.withAlpha(held, 0x38),
            launch = launch,
            launchFocused = launchFocused,
            onLaunchMuted = m.ensure(m.mix(primary, launch, 0.15), listOf(launch, launchFocused), TEXT_FLOOR),
            // Its Material scheme and notices are built from these roles, not droidtop's stock pair.
            declared = true,
        )
    }

    /**
     * droidtop's own palette where no theme declares one: [GamingThemeColors.DEFAULT], or its light twin, built the way a
     * theme's palette is (every text role held to 4.5:1) over a pale grey ground with a blue accent (docs/SPEC.md 7k,
     * "Light and dark").
     */
    fun defaultFor(light: Boolean): GamingThemeColors = if (light) DEFAULT_LIGHT else GamingThemeColors.DEFAULT

    private val DEFAULT_LIGHT: GamingThemeColors by lazy {
        build(Declared(ground = 0xFFF2F3F5.toInt(), accent = 0xFF1F5FD1.toInt())).copy(declared = false)
    }

    // ---- reading what the theme declares ----------------------------------

    private fun read(theme: EsDeTheme): Declared {
        val d = Declared()
        val ordered = listOfNotNull(theme.views["system"], theme.views["all"], theme.views["gamelist"])
        val elements = ordered.flatMap { it.elements.values }
        val systemElements = listOfNotNull(theme.views["system"], theme.views["all"]).flatMap { it.elements.values }
        val ofType = { type: String -> elements.filter { it.type == type } }
        val systemOfType = { type: String -> systemElements.filter { it.type == type } }

        // Ground and texture.
        val named = elements.firstOrNull { it.type == "image" && it.key.contains("background", ignoreCase = true) && opaqueColor(it, "color") != null }
        val covering = elements
            .filter { it.type == "image" && covers(it) && opaqueColor(it, "color") != null }
            .minByOrNull { it.floatOrNull("zIndex") ?: 30f }
        val groundElement = named ?: covering
        d.ground = groundElement?.let { opaqueColor(it, "color") }
            ?: GROUND_VARIABLES.firstNotNullOfOrNull { name -> theme.variables[name]?.let { parseVariableColor(it) } }
        d.texturePath = elements
            .firstOrNull { it.type == "image" && it.key.contains("background", ignoreCase = true) && it.boolOrNull("tile") == true }
            ?.existingPathOrNull("path")

        // Text drawn straight on the ground, then the help row's, then a list's.
        d.primary = firstColor(
            systemOfType("clock").map { it.colorArgb("color") } +
                systemOfType("systemstatus").map { it.colorArgb("color") } +
                systemOfType("carousel").map { it.colorArgb("textColor") } +
                systemOfType("text").map { it.colorArgb("color") } +
                ofType("helpsystem").map { it.colorArgb("textColor") } +
                ofType("textlist").map { it.colorArgb("primaryColor") } +
                ofType("text").map { it.colorArgb("color") },
        )
        d.secondary = firstColor(ofType("helpsystem").map { it.colorArgb("textColor") })
        d.surface = firstColor(ofType("helpsystem").map { it.colorArgb("backgroundColor") })

        // The first declared colour with real chroma.
        val accentCandidates = ofType("helpsystem").map { it.colorArgb("iconColor") } +
            ofType("textlist").flatMap { listOf(it.colorArgb("selectedColor"), it.colorArgb("selectorColor")) } +
            ofType("carousel").flatMap { listOf(it.colorArgb("color"), it.colorArgb("textColor")) } +
            ofType("clock").map { it.colorArgb("color") } +
            ofType("text").map { it.colorArgb("color") } +
            ofType("image").map { it.colorArgb("color") }
        d.accent = accentCandidates.firstOrNull { it != null && ColorMath.saturation(it) >= CHROMA_FLOOR && ColorMath.alpha(it) >= 0x80 }

        // Typefaces.
        d.bodyFont = (ofType("helpsystem") + systemOfType("clock") + systemOfType("systemstatus") + ofType("text") + ofType("carousel"))
            .firstNotNullOfOrNull { it.existingPathOrNull("fontPath") }
        d.displayFont = (ofType("carousel") + elements.filter { it.type in TITLE_TYPES })
            .firstNotNullOfOrNull { it.existingPathOrNull("fontPath") } ?: d.bodyFont
        return d
    }

    private const val CHROMA_FLOOR = 0.35

    /** A declared colour as ARGB, null when unset or fully transparent. */
    private fun EsDeThemeElement.colorArgb(name: String): Int? {
        val packed = (this as EsDeThemeElement?).colorOrNull(name) ?: return null
        val argb = rgbaToArgb(packed)
        return if (ColorMath.alpha(argb) == 0) null else argb
    }

    private fun opaqueColor(element: EsDeThemeElement, name: String): Int? =
        element.colorArgb(name)?.takeIf { ColorMath.alpha(it) == 0xFF }

    private fun firstColor(list: List<Int?>): Int? = list.firstOrNull { it != null }

    private fun rgbaToArgb(rgba: Long): Int =
        (((rgba ushr 8) and 0xFFFFFF).toInt()) or ((rgba and 0xFF).toInt() shl 24)

    /** A variable's own value (6 or 8 hex digits, RRGGBB[AA]) as ARGB, null when it is not a colour. */
    private fun parseVariableColor(raw: String): Int? {
        val hex = raw.trim().removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        val value = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) (0xFF000000.toInt() or value.toInt()) else rgbaToArgb(value)
    }

    /** An image that fills the screen: its box reaches both edges. */
    private fun covers(element: EsDeThemeElement): Boolean {
        val size = element.valueOrNull<EsDeThemeValue.Pair>("size") ?: return false
        val pos = element.valueOrNull<EsDeThemeValue.Pair>("pos") ?: EsDeThemeValue.Pair(0f, 0f)
        val origin = element.valueOrNull<EsDeThemeValue.Pair>("origin") ?: EsDeThemeValue.Pair(0f, 0f)
        val left = pos.x - origin.x * size.x
        val top = pos.y - origin.y * size.y
        return left <= 0.02f && top <= 0.02f && left + size.x >= 0.98f && top + size.y >= 0.98f
    }

    // ---- building the roles ------------------------------------------------

    private fun build(d: Declared): GamingThemeColors {
        val base = GamingThemeColors.DEFAULT
        val m = ColorMath
        val ground = d.ground ?: base.ground
        // Which end of the scale text sits at: whichever the ground can carry.
        val ink = if (m.ratio(m.WHITE, ground) >= m.ratio(m.BLACK, ground)) m.WHITE else m.BLACK
        val overlay = d.surface?.let { m.over(it, ground) }
            ?.takeIf { m.ratio(it, ground) > 1.04 }
            ?: m.mix(ground, ink, 0.11)
        val card = m.mix(ground, ink, 0.10)
        val cardFocused = m.mix(ground, ink, 0.165)
        val cardInset = m.mix(ground, ink, 0.08)
        val hintBar = m.mix(ground, ink, 0.07)
        val chosenTile = m.mix(overlay, ink, 0.17)
        val surfaces = listOf(ground, overlay, card, cardFocused, cardInset, hintBar, chosenTile)

        val primary = m.ensure(d.primary ?: ink, surfaces, TEXT_FLOOR)
        val muted = m.ensure(d.secondary ?: m.mix(primary, overlay, 0.35), surfaces, TEXT_FLOOR)
        val value = m.ensure(m.mix(primary, muted, 0.35), surfaces, TEXT_FLOOR)
        val placeholder = m.ensure(m.mix(muted, ground, 0.2), surfaces, TEXT_FLOOR)
        val sectionLabel = m.ensure(m.mix(muted, ground, 0.1), surfaces, TEXT_FLOOR)
        val disabled = m.ensure(m.mix(primary, overlay, 0.5), surfaces, DISABLED_FLOOR)
        // A theme that names no accent takes droidtop's azure, held to the text floor on its own surfaces by lightness
        // alone (ColorMath.ensure mixes toward white or black, never another hue): owner, 2026-10-24, Droidtop/tracker#644,
        // "do we still grant accents and stuff from the selected retro theme?" Its own accent, when it has one, wins.
        val accent = m.ensure(d.accent ?: base.accent, surfaces, TEXT_FLOOR)

        val plate = m.mix(ground, base.danger, 0.22)
        val plateFlat = m.over(m.withAlpha(plate, 0xCC), ground)
        val surfacesWithPlate = surfaces + plateFlat
        val danger = m.ensure(base.danger, surfacesWithPlate, TEXT_FLOOR)
        val affirmative = m.ensure(base.affirmative, surfaces, TEXT_FLOOR)
        val favourite = m.ensure(base.favourite, surfaces, TEXT_FLOOR)

        // The launch button is a fill with a label on it: the label is the
        // ink the rest of the chrome reads in, so the fill is what moves.
        val awayFromInk = if (m.luminance(primary) > 0.4) m.BLACK else m.WHITE
        val launch = m.ensureFill(m.mix(ground, accent, 0.40), primary, TEXT_FLOOR, awayFromInk)
        val launchFocused = m.ensureFill(m.mix(launch, primary, 0.12), primary, TEXT_FLOOR, awayFromInk)
        val onLaunchMuted = m.ensure(m.mix(primary, launch, 0.15), listOf(launch, launchFocused), TEXT_FLOOR)

        val selected = primary
        return GamingThemeColors(
            ground = ground,
            overlaySurface = overlay,
            card = card,
            cardFocused = cardFocused,
            cardInset = cardInset,
            hintBar = hintBar,
            onSurface = primary,
            onSurfaceMuted = muted,
            value = value,
            placeholder = placeholder,
            sectionLabel = sectionLabel,
            onSurfaceDisabled = disabled,
            accent = accent,
            onAccent = m.pickOn(accent),
            danger = danger,
            affirmative = affirmative,
            favourite = favourite,
            // Fills are the ink at low alpha, so they work on a light ground too.
            rowFill = m.withAlpha(ink, 0x0D),
            // A focused row is the accent at 22 percent (Droidtop/tracker#644, 4.5 "Focus"): the theme's accent where it has one.
            rowFillSelected = m.withAlpha(accent, 0x38),
            cardOutline = m.withAlpha(ink, 0x1F),
            hintPillOutline = m.withAlpha(ink, 0x33),
            selected = selected,
            onSelected = m.pickOn(selected),
            scrim = m.withAlpha(ground, 0xCC),
            dangerPlate = m.withAlpha(plate, 0xCC),
            launch = launch,
            launchFocused = launchFocused,
            launchDisabled = cardInset,
            onLaunchMuted = onLaunchMuted,
            bodyFontPath = d.bodyFont,
            displayFontPath = d.displayFont,
            textureImagePath = d.texturePath,
            declared = true,
        )
    }
}
