package dev.droidtop.library.theme

/**
 * The notice colours (docs/SPEC.md 7k, "Notices"; Droidtop/tracker#485), as
 * ARGB ints derived from the theme's own [GamingThemeColors], so a plain JVM
 * test can hold every bundled theme to them. Three tones, three tokens each:
 * the ink (glyph, title, edge bar), an opaque plate the notice sits on, and
 * the body text on that plate; plus the danger fill the risky button and the
 * Full access chip use, with its label colour.
 *
 * - **Info** is the theme accent, unless the accent is within [NEAR_LSTAR]
 *   L* and [NEAR_HUE] degrees of caution's under normal vision or a
 *   simulated colour vision deficiency; then it is the neutral text colour,
 *   and the glyph (circle i, triangle !) tells the two apart.
 * - **Caution** is a fixed amber ([CAUTION_DARK] on a dark ground,
 *   [CAUTION_LIGHT] on a light one). **Danger** is the palette's danger.
 *   Both keep their hue: only their lightness moves, to reach the contrast
 *   floor and, for danger, to stay [NEAR_LSTAR] L* away from caution under
 *   deuteranopia, protanopia and tritanopia.
 * - The floor is [GamingThemeMapping.TEXT_FLOOR], or [HIGH_CONTRAST_FLOOR]
 *   while Android's high-contrast text is on; contrast wins over the
 *   separation when both cannot hold.
 *
 * Computed once per palette (the shell caches the palette per theme), never
 * in a composition.
 */
data class NoticeColors(
    val infoInk: Int,
    val infoPlate: Int,
    val onInfo: Int,
    val cautionInk: Int,
    val cautionPlate: Int,
    val onCaution: Int,
    val dangerInk: Int,
    val dangerPlate: Int,
    val onDanger: Int,
    val dangerFill: Int,
    val onDangerFill: Int,
    /** True when info fell back to neutral ink because the accent read as caution. */
    val infoNeutral: Boolean,
    /** Secondary text on any of the three plates ("from <plugin>"). */
    val muted: Int,
    /**
     * Whether danger and caution are [NEAR_LSTAR] apart under every simulated dichromacy. False only when no
     * readable pair exists: on a mid-tone ground readable ink is confined to one end of the scale, contrast wins,
     * and the glyphs (triangle, octagon) tell the two tones apart.
     */
    val separated: Boolean,
) {
    companion object {
        const val CAUTION_DARK: Int = 0xFFFFC56B.toInt()
        const val CAUTION_LIGHT: Int = 0xFF8A5A00.toInt()
        const val HIGH_CONTRAST_FLOOR = 7.0
        const val NEAR_LSTAR = 20.0
        const val NEAR_HUE = 30.0

        /** How far each tone's plate is mixed from the ground toward its ink. */
        const val INFO_PLATE_MIX = 0.14
        const val CAUTION_PLATE_MIX = 0.20
        const val DANGER_PLATE_MIX = 0.24

        fun derive(colors: GamingThemeColors, highContrast: Boolean = false): NoticeColors {
            val m = ThemeColorMath
            val floor = if (highContrast) HIGH_CONTRAST_FLOOR else GamingThemeMapping.TEXT_FLOOR
            val ground = colors.ground
            val surfaces = listOf(ground, colors.overlaySurface, colors.card)

            fun plate(ink: Int, k: Double) = m.mix(ground, ink, k)
            fun body(plate: Int) = m.ensure(colors.onSurface, listOf(plate), floor)

            // Each plate is mixed from the ink it carries, so the two are chosen together.
            val cautionBase = if (colors.isLight) CAUTION_LIGHT else CAUTION_DARK
            val cautionSurfaces = { ink: Int -> surfaces + plate(ink, CAUTION_PLATE_MIX) }
            val dangerSurfaces = { ink: Int -> surfaces + plate(ink, DANGER_PLATE_MIX) }
            fun apart(danger: Int, caution: Int) =
                ColorVision.SIMULATIONS.all { sim -> ColorVision.lightnessApart(danger, caution, sim) >= NEAR_LSTAR }

            // Caution at the readable lightness nearest its own, and danger apart from it. When no readable danger is
            // apart from that caution, caution steps away from its own lightness, nearest first, until one is; when
            // none ever is, contrast wins over the separation.
            var cautionInk = Hsl.withLightness(cautionBase, floor, backgrounds = cautionSurfaces)
            val dangerReadable = Hsl.readableByCloseness(colors.danger, floor, dangerSurfaces)
            var dangerInk = dangerReadable.firstOrNull { apart(it, cautionInk) }
            if (dangerInk == null) {
                for (caution in Hsl.readableByCloseness(cautionBase, floor, cautionSurfaces)) {
                    val danger = dangerReadable.firstOrNull { apart(it, caution) } ?: continue
                    cautionInk = caution
                    dangerInk = danger
                    break
                }
            }
            val dangerFinal = dangerInk ?: Hsl.withLightness(colors.danger, floor, backgrounds = dangerSurfaces)
            val cautionPlate = plate(cautionInk, CAUTION_PLATE_MIX)
            val dangerPlate = plate(dangerFinal, DANGER_PLATE_MIX)

            val accentNearCaution = ColorVision.ALL.any { sim ->
                ColorVision.lightnessApart(colors.accent, cautionInk, sim) < NEAR_LSTAR &&
                    ColorVision.hueApart(colors.accent, cautionInk, sim) < NEAR_HUE
            }
            val infoBase = if (accentNearCaution) colors.onSurface else colors.accent
            val infoPlate = plate(infoBase, INFO_PLATE_MIX)
            val infoInk = m.ensure(infoBase, surfaces + infoPlate, floor)

            // The risky button: the danger hue as a fill a white label reads on, else a black one.
            val fillWhite = Hsl.fillFor(colors.danger, m.WHITE, floor)
            val (fill, onFill) = if (fillWhite != null) fillWhite to m.WHITE else
                (Hsl.fillFor(colors.danger, m.BLACK, floor) ?: m.ensureFill(colors.danger, m.BLACK, floor, m.WHITE)) to m.BLACK

            return NoticeColors(
                infoInk = infoInk,
                infoPlate = infoPlate,
                onInfo = body(infoPlate),
                cautionInk = cautionInk,
                cautionPlate = cautionPlate,
                onCaution = body(cautionPlate),
                dangerInk = dangerFinal,
                dangerPlate = dangerPlate,
                onDanger = body(dangerPlate),
                dangerFill = fill,
                onDangerFill = onFill,
                infoNeutral = accentNearCaution,
                muted = m.ensure(colors.onSurfaceMuted, listOf(infoPlate, cautionPlate, dangerPlate), floor),
                separated = dangerInk != null,
            )
        }
    }
}

/** Hue-preserving lightness moves, in HSL: the hue and saturation stay, only L changes. */
object Hsl {
    fun toHsl(argb: Int): DoubleArray {
        val r = ThemeColorMath.red(argb) / 255.0
        val g = ThemeColorMath.green(argb) / 255.0
        val b = ThemeColorMath.blue(argb) / 255.0
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val l = (mx + mn) / 2
        if (mx == mn) return doubleArrayOf(0.0, 0.0, l)
        val d = mx - mn
        val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> ((g - b) / d + (if (g < b) 6 else 0))
            g -> ((b - r) / d + 2)
            else -> ((r - g) / d + 4)
        } * 60
        return doubleArrayOf(h, s, l)
    }

    fun fromHsl(h: Double, s: Double, l: Double): Int {
        if (s == 0.0) {
            val v = Math.round(l * 255).toInt()
            return ThemeColorMath.rgb(v, v, v)
        }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun channel(t0: Double): Int {
            var t = t0
            if (t < 0) t += 1
            if (t > 1) t -= 1
            val c = when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 1.0 / 2 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
            return Math.round(c * 255).toInt()
        }
        val hk = h / 360
        return ThemeColorMath.rgb(channel(hk + 1.0 / 3), channel(hk), channel(hk - 1.0 / 3))
    }

    private const val STEPS = 200

    private fun candidates(base: Int): List<Pair<Int, Double>> {
        val hsl = toHsl(base)
        return (0..STEPS).map { fromHsl(hsl[0], hsl[1], it.toDouble() / STEPS) to it.toDouble() / STEPS }
    }

    /**
     * Every lightness of [base]'s hue that reads at [floor] over every one of [backgrounds] (asked per candidate,
     * since a plate mixed from the ink moves with it), nearest [base]'s own lightness first.
     */
    fun readableByCloseness(base: Int, floor: Double, backgrounds: (Int) -> List<Int>): List<Int> {
        val l0 = toHsl(base)[2]
        return candidates(base)
            .filter { ThemeColorMath.worstRatio(it.first, backgrounds(it.first)) >= floor }
            .sortedBy { Math.abs(it.second - l0) }
            .map { it.first }
    }

    /** The first of [readableByCloseness] that passes [also], or null. */
    fun readableNearest(base: Int, floor: Double, backgrounds: (Int) -> List<Int>, also: (Int) -> Boolean): Int? =
        readableByCloseness(base, floor, backgrounds).firstOrNull(also)

    /**
     * [base] at the readable lightness nearest its own ([readableByCloseness]); the best-reading lightness when
     * none reaches [floor].
     */
    fun withLightness(base: Int, floor: Double, backgrounds: (Int) -> List<Int>): Int =
        readableByCloseness(base, floor, backgrounds).firstOrNull()
            ?: candidates(base).maxByOrNull { ThemeColorMath.worstRatio(it.first, backgrounds(it.first)) }!!.first

    /** [base]'s hue as a fill [label] reads on at [floor], the lightest such for a white label and the darkest for black; null when none does. */
    fun fillFor(base: Int, label: Int, floor: Double): Int? {
        val (h, s) = toHsl(base).let { it[0] to it[1] }
        // A saturated fill: a pale danger colour as a button reads as disabled, not as danger.
        val sat = maxOf(s, 0.65)
        val range = if (label == ThemeColorMath.WHITE) (STEPS downTo 0) else (0..STEPS)
        return range.asSequence()
            .map { fromHsl(h, sat, it.toDouble() / STEPS) }
            .firstOrNull { ThemeColorMath.ratio(label, it) >= floor }
    }
}

/**
 * Colour vision: CIELAB lightness and hue of an sRGB colour, as seen with
 * normal vision and through a simulated dichromacy (Machado, Oliveira and
 * Fernandes 2009, severity 1.0, applied in linear RGB).
 */
object ColorVision {
    enum class Sim { NORMAL, PROTANOPIA, DEUTERANOPIA, TRITANOPIA }

    val SIMULATIONS = listOf(Sim.PROTANOPIA, Sim.DEUTERANOPIA, Sim.TRITANOPIA)
    val ALL = listOf(Sim.NORMAL) + SIMULATIONS

    private val MATRICES = mapOf(
        Sim.PROTANOPIA to doubleArrayOf(
            0.152286, 1.052583, -0.204868,
            0.114503, 0.786281, 0.099216,
            -0.003882, -0.048116, 1.051998,
        ),
        Sim.DEUTERANOPIA to doubleArrayOf(
            0.367322, 0.860646, -0.227968,
            0.280085, 0.672501, 0.047413,
            -0.011820, 0.042940, 0.968881,
        ),
        Sim.TRITANOPIA to doubleArrayOf(
            1.255528, -0.076749, -0.178779,
            -0.078411, 0.930809, 0.147602,
            0.004733, 0.691367, 0.303900,
        ),
    )

    private fun linear(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    /** The colour's linear RGB as seen under [sim]. */
    private fun linearRgb(argb: Int, sim: Sim): DoubleArray {
        val rgb = doubleArrayOf(linear(ThemeColorMath.red(argb)), linear(ThemeColorMath.green(argb)), linear(ThemeColorMath.blue(argb)))
        val mtx = MATRICES[sim] ?: return rgb
        return DoubleArray(3) { row ->
            (mtx[row * 3] * rgb[0] + mtx[row * 3 + 1] * rgb[1] + mtx[row * 3 + 2] * rgb[2]).coerceIn(0.0, 1.0)
        }
    }

    /** CIELAB L*, a*, b* (D65) of the colour under [sim]. */
    fun lab(argb: Int, sim: Sim = Sim.NORMAL): DoubleArray {
        val (r, g, b) = linearRgb(argb, sim).let { Triple(it[0], it[1], it[2]) }
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 216.0 / 24389) Math.cbrt(t) else (24389.0 / 27 * t + 16) / 116
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    fun lightnessApart(a: Int, b: Int, sim: Sim = Sim.NORMAL): Double = Math.abs(lab(a, sim)[0] - lab(b, sim)[0])

    /** The CIELAB hue angle between the two, 0 to 180 degrees; 0 when either is grey (no hue to tell apart). */
    fun hueApart(a: Int, b: Int, sim: Sim = Sim.NORMAL): Double {
        val la = lab(a, sim)
        val lb = lab(b, sim)
        if (Math.hypot(la[1], la[2]) < 5 || Math.hypot(lb[1], lb[2]) < 5) return 0.0
        val ha = Math.toDegrees(Math.atan2(la[2], la[1]))
        val hb = Math.toDegrees(Math.atan2(lb[2], lb[1]))
        val d = Math.abs(ha - hb) % 360
        return if (d > 180) 360 - d else d
    }
}
