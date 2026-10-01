package dev.droidtop.library.theme

/**
 * Colour arithmetic for the Gaming design tokens, on plain ARGB `Int`s so
 * the whole mapping ([GamingThemeMapping]) is a pure function a JVM unit
 * test can run. Lives in `:runtime-common` next to the theme parser it
 * reads; `:shell-gamepad` turns the result into Compose colours.
 */
object ThemeColorMath {
    fun alpha(argb: Int): Int = argb ushr 24 and 0xFF
    fun red(argb: Int): Int = argb shr 16 and 0xFF
    fun green(argb: Int): Int = argb shr 8 and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun withAlpha(argb: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (argb and 0xFFFFFF)

    private fun channel(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    /** WCAG relative luminance of the colour's RGB (alpha ignored). */
    fun luminance(argb: Int): Double =
        0.2126 * channel(red(argb)) + 0.7152 * channel(green(argb)) + 0.0722 * channel(blue(argb))

    /** [fg] composited onto the opaque [bg], as it is on screen; the result is opaque. */
    fun over(fg: Int, bg: Int): Int {
        val a = alpha(fg) / 255.0
        fun mixc(f: Int, b: Int) = Math.round(f * a + b * (1 - a)).toInt()
        return rgb(mixc(red(fg), red(bg)), mixc(green(fg), green(bg)), mixc(blue(fg), blue(bg)))
    }

    /** WCAG contrast ratio of [fg] (composited over [bg] first) against [bg]. */
    fun ratio(fg: Int, bg: Int): Double {
        val l1 = luminance(over(fg, bg))
        val l2 = luminance(bg)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    /** [a] moved [t] of the way to [b] (0 = a, 1 = b); the result is opaque. */
    fun mix(a: Int, b: Int, t: Double): Int {
        fun m(x: Int, y: Int) = Math.round(x + (y - x) * t).toInt()
        return rgb(m(red(a), red(b)), m(green(a), green(b)), m(blue(a), blue(b)))
    }

    /** HSV saturation, 0 for a grey and 1 for a pure hue. */
    fun saturation(argb: Int): Double {
        val mx = maxOf(red(argb), green(argb), blue(argb))
        val mn = minOf(red(argb), green(argb), blue(argb))
        return if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
    }

    const val WHITE: Int = 0xFFFFFFFF.toInt()
    const val BLACK: Int = 0xFF000000.toInt()

    /** The smallest worst-case ratio of [fg] over every one of [backgrounds]. */
    fun worstRatio(fg: Int, backgrounds: List<Int>): Double =
        backgrounds.minOf { ratio(fg, it) }

    /**
     * [fg], unchanged if it already clears [floor] over every background,
     * otherwise moved toward white or toward black, whichever reaches the
     * floor with the smaller change to the colour, so a theme's amber stays
     * an amber and only gets as light or dark as it must. If neither end
     * can reach the floor (backgrounds on both sides of the text), the end
     * that gets closest. The result is opaque.
     */
    fun ensure(fg: Int, backgrounds: List<Int>, floor: Double): Int {
        val flat = over(fg, backgrounds.first())
        if (worstRatio(flat, backgrounds) >= floor) return flat
        var best: Int? = null
        var bestStep = Int.MAX_VALUE
        var closest = flat
        var closestWorst = worstRatio(flat, backgrounds)
        for (target in intArrayOf(WHITE, BLACK)) {
            for (step in 1..50) {
                val candidate = mix(flat, target, step / 50.0)
                val worst = worstRatio(candidate, backgrounds)
                if (worst >= floor) {
                    if (step < bestStep) {
                        bestStep = step
                        best = candidate
                    }
                    break
                }
                if (worst > closestWorst) {
                    closestWorst = worst
                    closest = candidate
                }
            }
        }
        return best ?: closest
    }

    /**
     * [bg] moved toward [target] until [fg] reads on it at [floor]; for a
     * fill that carries a label (the launch button), where the label is
     * fixed and the fill is what gives way.
     */
    fun ensureFill(bg: Int, fg: Int, floor: Double, target: Int): Int {
        if (ratio(fg, bg) >= floor) return bg
        for (step in 1..50) {
            val candidate = mix(bg, target, step / 50.0)
            if (ratio(fg, candidate) >= floor) return candidate
        }
        return mix(bg, target, 1.0)
    }

    /** Black or white, whichever reads better on [bg]. */
    fun pickOn(bg: Int): Int = if (ratio(WHITE, bg) >= ratio(BLACK, bg)) WHITE else BLACK
}

/**
 * Every colour role the Gaming chrome draws, as ARGB ints, plus the theme's
 * own typefaces and background texture. `:shell-gamepad`'s design tokens
 * (`MenuTokens`, the Material scheme) are this and nothing else, so one
 * theme change moves every surface droidtop draws itself.
 *
 * [DEFAULT] is droidtop's own palette, the exact values the chrome used
 * before any theme reached it. [declared] is false for it.
 */
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
    val isLight: Boolean get() = ThemeColorMath.luminance(ground) > 0.4

    companion object {
        val DEFAULT = GamingThemeColors(
            ground = 0xFF000000.toInt(),
            overlaySurface = 0xFF1C2027.toInt(),
            card = 0xFF1A1A1A.toInt(),
            cardFocused = 0xFF2A2A2A.toInt(),
            cardInset = 0xFF141414.toInt(),
            hintBar = 0xFF111111.toInt(),
            onSurface = 0xFFFFFFFF.toInt(),
            onSurfaceMuted = 0xFF8A93A1.toInt(),
            value = 0xFFAEB7C4.toInt(),
            placeholder = 0xFF6B7480.toInt(),
            sectionLabel = 0xFF7D8794.toInt(),
            onSurfaceDisabled = 0x80FFFFFF.toInt(),
            accent = 0xFF8AB4FF.toInt(),
            onAccent = 0xFF0B1220.toInt(),
            danger = 0xFFFFB4AB.toInt(),
            affirmative = 0xFF7FE08A.toInt(),
            favourite = 0xFFFFD700.toInt(),
            rowFill = 0x0DFFFFFF,
            rowFillSelected = 0x2BFFFFFF,
            cardOutline = 0x1FFFFFFF,
            hintPillOutline = 0x33FFFFFF,
            selected = 0xFFFFFFFF.toInt(),
            onSelected = 0xFF000000.toInt(),
            scrim = 0xCC000000.toInt(),
            dangerPlate = 0xCC330E0B.toInt(),
            launch = 0xFF2B5C3C.toInt(),
            launchFocused = 0xFF3D7A52.toInt(),
            launchDisabled = 0xFF232323.toInt(),
            onLaunchMuted = 0xFFD7E6DC.toInt(),
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
 * ([ThemeColorMath.ensure]); roles that are fills (launch) give way to
 * their label instead. A theme that declares nothing of the above is
 * [GamingThemeColors.DEFAULT], untouched.
 */
object GamingThemeMapping {
    /** WCAG AA for body text. */
    const val TEXT_FLOOR = 4.5

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

    fun map(theme: EsDeTheme?): GamingThemeColors {
        if (theme == null) return GamingThemeColors.DEFAULT
        val declared = read(theme)
        if (!declared.any) return GamingThemeColors.DEFAULT
        return build(declared)
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
        d.accent = accentCandidates.firstOrNull { it != null && ThemeColorMath.saturation(it) >= CHROMA_FLOOR && ThemeColorMath.alpha(it) >= 0x80 }

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
        return if (ThemeColorMath.alpha(argb) == 0) null else argb
    }

    private fun opaqueColor(element: EsDeThemeElement, name: String): Int? =
        element.colorArgb(name)?.takeIf { ThemeColorMath.alpha(it) == 0xFF }

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
        val m = ThemeColorMath
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
        val accent = m.ensure(d.accent ?: primary, surfaces, TEXT_FLOOR)

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
            rowFillSelected = m.withAlpha(ink, 0x2B),
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
