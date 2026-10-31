package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** "Tint themes to my colours" in the theme's own views (docs/SPEC.md 7k, "Colours outside Gaming"; Droidtop/tracker#844). */
class ThemeTintTest {
    private val amber = 0xE0A030FFL
    private val white = 0xFFFFFFFFL
    private val violet = 0xFFA67CFF.toInt()

    private fun color(rgba: Long) = EsDeThemeValue.Color(rgba)

    private fun theme(vararg elements: EsDeThemeElement) = EsDeTheme(
        variables = emptyMap(),
        views = mapOf("gamelist" to EsDeThemeView(elements.associateBy { it.key })),
    )

    private fun EsDeTheme.colorOf(key: String, property: String): Long =
        (views.getValue("gamelist").elements.getValue(key).properties.getValue(property) as EsDeThemeValue.Color).argbLikeRgba

    @Test
    fun `the theme's accent is swapped everywhere it is declared, keeping each colour's alpha`() {
        val list = EsDeThemeElement("textlist", "textlist_gamelist", mapOf("selectorColor" to color(amber), "primaryColor" to color(white)))
        val glow = EsDeThemeElement("image", "image_glow", mapOf("color" to color((amber and 0xFFFFFF00) or 0x80)))
        val tinted = GamingThemeMapping.tintTheme(theme(list, glow), violet)
        val selector = tinted.colorOf("textlist_gamelist", "selectorColor")
        assertNotEquals(amber ushr 8, selector ushr 8)
        assertEquals(0xFFL, selector and 0xFF)
        assertEquals(selector ushr 8, tinted.colorOf("image_glow", "color") ushr 8)
        assertEquals(0x80L, tinted.colorOf("image_glow", "color") and 0xFF)
        assertEquals(white, tinted.colorOf("textlist_gamelist", "primaryColor"))
    }

    @Test
    fun `the colour put in is the accent the chrome is tinted with`() {
        val list = EsDeThemeElement("textlist", "textlist_gamelist", mapOf("selectorColor" to color(amber)))
        val source = theme(list)
        val chrome = GamingThemeMapping.tint(GamingThemeMapping.map(source), violet).accent
        assertEquals(chrome.toLong() and 0xFFFFFF, GamingThemeMapping.tintTheme(source, violet).colorOf("textlist_gamelist", "selectorColor") ushr 8)
    }

    @Test
    fun `a theme that declares no accent is left as it is`() {
        val plain = theme(EsDeThemeElement("text", "text_title", mapOf("color" to color(white))))
        assertSame(plain, GamingThemeMapping.tintTheme(plain, violet))
    }
}
