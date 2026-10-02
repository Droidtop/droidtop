package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one mapping from an active ES-DE theme to the Gaming design tokens:
 * a declared value is used, an undeclared one falls back, and every text
 * role clears WCAG AA over every surface it is drawn on, whatever the
 * theme declared.
 */
class GamingThemeMappingTest {

    private fun color(rrggbb: String, aa: String = "FF") =
        EsDeThemeValue.Color((rrggbb + aa).toLong(16))

    private fun element(type: String, name: String, vararg props: Pair<String, EsDeThemeValue>) =
        EsDeThemeElement(type, "${type}_$name", props.toMap())

    private fun theme(
        system: List<EsDeThemeElement> = emptyList(),
        gamelist: List<EsDeThemeElement> = emptyList(),
        variables: Map<String, String> = emptyMap(),
    ) = EsDeTheme(
        variables = variables,
        views = buildMap {
            if (system.isNotEmpty()) put("system", EsDeThemeView(system.associateBy { it.key }))
            if (gamelist.isNotEmpty()) put("gamelist", EsDeThemeView(gamelist.associateBy { it.key }))
        },
    )

    private fun argb(rrggbb: String) = 0xFF000000.toInt() or rrggbb.toInt(16)

    private val fullScreen = arrayOf<Pair<String, EsDeThemeValue>>(
        "pos" to EsDeThemeValue.Pair(0f, 0f),
        "size" to EsDeThemeValue.Pair(1f, 1f),
    )

    /** Every text role over every surface it is drawn on. */
    private fun assertAccessible(c: GamingThemeColors) {
        val m = ThemeColorMath
        val surfaces = listOf(c.ground, c.overlaySurface, c.card, c.cardFocused, c.cardInset, c.hintBar)
        for (surface in surfaces) {
            listOf(
                "onSurface" to c.onSurface,
                "onSurfaceMuted" to c.onSurfaceMuted,
                "value" to c.value,
                "placeholder" to c.placeholder,
                "sectionLabel" to c.sectionLabel,
                "accent" to c.accent,
                "danger" to c.danger,
                "affirmative" to c.affirmative,
                "favourite" to c.favourite,
            ).forEach { (name, fg) ->
                val ratio = m.ratio(fg, surface)
                assertTrue("$name is $ratio:1 on ${Integer.toHexString(surface)}", ratio >= GamingThemeMapping.TEXT_FLOOR - 0.01)
            }
            val disabled = m.ratio(c.onSurfaceDisabled, surface)
            assertTrue("disabled is $disabled:1", disabled >= GamingThemeMapping.DISABLED_FLOOR - 0.01)
        }
        assertTrue(m.ratio(c.onSelected, c.selected) >= 4.5)
        listOf(c.launch, c.launchFocused).forEach { fill ->
            assertTrue("label on launch", m.ratio(c.onSurface, fill) >= 4.5 - 0.01)
            assertTrue("muted label on launch", m.ratio(c.onLaunchMuted, fill) >= 4.5 - 0.01)
        }
    }

    @Test
    fun `no theme and a theme that declares nothing are droidtop's own palette`() {
        assertSame(GamingThemeColors.DEFAULT, GamingThemeMapping.map(null))
        val bare = theme(system = listOf(element("carousel", "c", "zIndex" to EsDeThemeValue.FloatValue(1f))))
        assertSame(GamingThemeColors.DEFAULT, GamingThemeMapping.map(bare))
        assertFalse(GamingThemeColors.DEFAULT.declared)
    }

    @Test
    fun `declared ground, text, help text and help plate are used`() {
        val t = theme(
            system = listOf(
                element("image", "background", "color" to color("202830")),
                element("clock", "clock", "color" to color("F0E8D0")),
                element(
                    "helpsystem", "help",
                    "textColor" to color("D8DEE6"),
                    "backgroundColor" to color("2C3642"),
                ),
            ),
        )
        val c = GamingThemeMapping.map(t)
        assertTrue(c.declared)
        assertEquals(argb("202830"), c.ground)
        assertEquals(argb("F0E8D0"), c.onSurface)
        assertEquals(argb("D8DEE6"), c.onSurfaceMuted)
        assertEquals(argb("2C3642"), c.overlaySurface)
        assertAccessible(c)
    }

    @Test
    fun `a translucent help plate is flattened onto the ground`() {
        val t = theme(
            system = listOf(
                element("image", "background", "color" to color("000000")),
                element("clock", "clock", "color" to color("FFFFFF")),
                element("helpsystem", "help", "backgroundColor" to color("202020", "80")),
            ),
        )
        val c = GamingThemeMapping.map(t)
        assertEquals(0xFF, ThemeColorMath.alpha(c.overlaySurface))
        assertEquals(argb("101010"), c.overlaySurface)
    }

    @Test
    fun `the ground falls back to a full screen image, then to the background variable, then to droidtop's`() {
        val byImage = theme(
            system = listOf(
                element("image", "wall", *fullScreen, "color" to color("303A44")),
                element("clock", "clock", "color" to color("FFFFFF")),
            ),
        )
        assertEquals(argb("303A44"), GamingThemeMapping.map(byImage).ground)

        // A translucent overlay is not a ground.
        val overlay = theme(
            system = listOf(
                element("image", "veil", *fullScreen, "color" to color("303A44", "AA")),
                element("clock", "clock", "color" to color("FFFFFF")),
            ),
            variables = mapOf("backgroundColor" to "123456"),
        )
        assertEquals(argb("123456"), GamingThemeMapping.map(overlay).ground)

        val none = theme(system = listOf(element("clock", "clock", "color" to color("FFFFFF"))))
        assertEquals(GamingThemeColors.DEFAULT.ground, GamingThemeMapping.map(none).ground)
    }

    @Test
    fun `unset roles are derived from what is declared, not left at the dark defaults`() {
        // Only a light ground: the surface, cards and fills must follow it.
        val t = theme(
            system = listOf(
                element("image", "background", "color" to color("E8E8E4")),
                element("clock", "clock", "color" to color("202020")),
            ),
        )
        val c = GamingThemeMapping.map(t)
        assertTrue(c.isLight)
        assertTrue(ThemeColorMath.luminance(c.overlaySurface) > 0.5)
        assertTrue(ThemeColorMath.luminance(c.card) > 0.5)
        // Fills are the ink at low alpha, so they show on a light ground too.
        assertEquals(0x0D, ThemeColorMath.alpha(c.rowFill))
        assertTrue(ThemeColorMath.luminance(c.rowFill) < 0.2)
        assertAccessible(c)
    }

    @Test
    fun `the first colour with chroma is the accent, and a neutral theme gets a neutral one`() {
        val coloured = theme(
            system = listOf(
                element("image", "background", "color" to color("000000")),
                element("clock", "clock", "color" to color("FFC800")),
                element("helpsystem", "help", "iconColor" to color("FFC800")),
            ),
        )
        assertEquals(argb("FFC800"), GamingThemeMapping.map(coloured).accent)

        val neutral = theme(
            system = listOf(
                element("image", "background", "color" to color("404040")),
                element("clock", "clock", "color" to color("FFFFFF")),
                element("helpsystem", "help", "iconColor" to color("A6A6A6")),
            ),
        )
        val n = GamingThemeMapping.map(neutral)
        assertEquals(argb("FFFFFF"), n.accent)
        assertEquals(argb("FFFFFF"), n.onSurface)
    }

    @Test
    fun `text that fails contrast is moved the shortest way to AA and keeps its hue`() {
        // Tan text on a tan ground: the declared colours are identical.
        val t = theme(
            system = listOf(
                element("image", "background", "color" to color("C59A6F")),
                element("clock", "clock", "color" to color("C59A6F")),
            ),
        )
        val c = GamingThemeMapping.map(t)
        assertNotEquals(argb("C59A6F"), c.onSurface)
        assertTrue(ThemeColorMath.ratio(c.onSurface, c.ground) >= GamingThemeMapping.TEXT_FLOOR)
        assertAccessible(c)

        val math = ThemeColorMath
        // Already readable: untouched.
        assertEquals(argb("FFFFFF"), math.ensure(argb("FFFFFF"), listOf(argb("000000")), 4.5))
        // Mid grey on a mid grey cannot be fixed without leaving the grey axis.
        val fixed = math.ensure(argb("808080"), listOf(argb("808080")), 4.5)
        assertTrue(math.ratio(fixed, argb("808080")) >= 4.5)
    }

    @Test
    fun `a danger, favourite and affirmative colour are darkened on a light ground`() {
        val t = theme(
            system = listOf(
                element("image", "background", "color" to color("F4F4F0")),
                element("clock", "clock", "color" to color("202020")),
            ),
        )
        val c = GamingThemeMapping.map(t)
        assertTrue(ThemeColorMath.ratio(c.danger, c.ground) >= 4.5)
        assertTrue(ThemeColorMath.ratio(c.favourite, c.ground) >= 4.5)
        assertTrue(ThemeColorMath.ratio(c.affirmative, c.ground) >= 4.5)
    }

    @Test
    fun `fonts and texture are read only when the file exists`() {
        val tmp = java.io.File.createTempFile("gaming-theme", ".ttf").apply { deleteOnExit() }
        val png = java.io.File.createTempFile("gaming-theme", ".png").apply { deleteOnExit() }
        val missing = "/definitely/not/here.ttf"
        val t = theme(
            system = listOf(
                element(
                    "image", "background",
                    "color" to color("404040"),
                    "path" to EsDeThemeValue.Path(png.absolutePath),
                    "tile" to EsDeThemeValue.Bool(true),
                ),
                element("clock", "clock", "color" to color("FFFFFF"), "fontPath" to EsDeThemeValue.Path(missing)),
                element("helpsystem", "help", "fontPath" to EsDeThemeValue.Path(tmp.absolutePath)),
                element("carousel", "car", "fontPath" to EsDeThemeValue.Path(missing)),
            ),
        )
        val c = GamingThemeMapping.map(t)
        assertEquals(tmp.absolutePath, c.bodyFontPath)
        // The carousel's font is missing and the help font is the next that exists.
        assertEquals(tmp.absolutePath, c.displayFontPath)
        assertEquals(png.absolutePath, c.textureImagePath)

        val untiled = theme(
            system = listOf(
                element(
                    "image", "background",
                    "color" to color("404040"),
                    "path" to EsDeThemeValue.Path(png.absolutePath),
                ),
            ),
        )
        assertNull(GamingThemeMapping.map(untiled).textureImagePath)
    }

    @Test
    fun `droidtop's own palette is itself accessible`() {
        val d = GamingThemeColors.DEFAULT
        val m = ThemeColorMath
        listOf(d.onSurface, d.value, d.accent, d.danger, d.favourite).forEach {
            assertTrue(m.ratio(it, d.ground) >= 4.5)
            assertTrue(m.ratio(it, d.overlaySurface) >= 4.5)
        }
        assertTrue(m.ratio(d.onSurfaceMuted, d.overlaySurface) >= 3.0)
    }

    @Test
    fun aThemeThatDrawsItsOwnClockOrStatusIsSaid() {
        assertFalse(GamingThemeMapping.drawsOwnStatus(null))
        assertFalse(GamingThemeMapping.drawsOwnStatus(theme(system = listOf(element("image", "logo", *fullScreen)))))
        assertTrue(GamingThemeMapping.drawsOwnStatus(theme(system = listOf(element("clock", "clock")))))
        assertTrue(GamingThemeMapping.drawsOwnStatus(theme(gamelist = listOf(element("systemstatus", "status")))))
    }

    @Test
    fun aClockThatNeverShowsInAViewDoesNotCountAsDrawn() {
        val menuOnly = element("clock", "clock", "scope" to EsDeThemeValue.Str("menu"))
        val never = element("systemstatus", "status", "scope" to EsDeThemeValue.Str("none"))
        assertFalse(GamingThemeMapping.drawsOwnStatus(theme(system = listOf(menuOnly, never))))
        val inView = element("clock", "clock", "scope" to EsDeThemeValue.Str("view"))
        assertTrue(GamingThemeMapping.drawsOwnStatus(theme(system = listOf(inView))))
    }
}
