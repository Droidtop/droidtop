package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Checks for the real ES-DE textlist layout port. Expected values are
 * derived by hand from `TextListComponent<T>::render()`/`applyTheme()`
 * in real ES-DE's own source, cited per test.
 */
class EsDeTextListLayoutTest {

    private fun element(vararg properties: Pair<String, EsDeThemeValue>) = EsDeThemeElement(
        type = "textlist",
        key = "textlist_test",
        properties = properties.toMap(),
    )

    private fun config(vararg properties: Pair<String, EsDeThemeValue>) = esDeTextListConfig(
        element = element(*properties),
        width = 400f,
        height = 300f,
        screenWidth = 1000f,
        screenHeight = 1000f,
        fontSize = 10f,
    )

    @Test
    fun `row count uses the real half-line-spacing slack`() {
        // entrySize = 10 * 1.5 = 15, lineSpacingHeight = 15 - 10 = 5, so
        // screenCount = floor((300 + 2.5) / 15) = 20. The half
        // line-spacing slack is what lets the list fit one more row than
        // a plain height/entrySize division sometimes would.
        val window = layoutEsDeTextList(config(), height = 300f, cursor = 0, entryCount = 100)
        assertEquals(15f, window.entrySize, 0f)
        assertEquals(20, window.screenCount)
        assertEquals(0, window.startEntry)
        assertEquals(20, window.listCutoff)
    }

    @Test
    fun `the window keeps the cursor centered and clamps at both ends`() {
        val cfg = config()
        // Real formula: startEntry = cursor - screenCount / 2, clamped to
        // [0, size - screenCount]. screenCount is 20 here.
        assertEquals(0, layoutEsDeTextList(cfg, 300f, cursor = 3, entryCount = 100).startEntry)
        assertEquals(40, layoutEsDeTextList(cfg, 300f, cursor = 50, entryCount = 100).startEntry)
        assertEquals(80, layoutEsDeTextList(cfg, 300f, cursor = 99, entryCount = 100).startEntry)
        // A list shorter than the window never scrolls at all.
        assertEquals(0, layoutEsDeTextList(cfg, 300f, cursor = 4, entryCount = 5).startEntry)
        assertEquals(5, layoutEsDeTextList(cfg, 300f, cursor = 4, entryCount = 5).listCutoff)
    }

    @Test
    fun `the selector sits on the cursor's own row`() {
        // Real: (cursor - startEntry) * entrySize + selectorVerticalOffset.
        val cfg = config("selectorVerticalOffset" to EsDeThemeValue.FloatValue(0.002f))
        // 0.002 of a 1000px-tall screen is 2px.
        assertEquals(2f, cfg.selectorVerticalOffset, 0.001f)
        val window = layoutEsDeTextList(cfg, 300f, cursor = 50, entryCount = 100)
        assertEquals((50 - 40) * 15f + 2f, window.selectorY, 0.001f)
        assertEquals(150f, window.rowY(50), 0.001f)
    }

    @Test
    fun `selector size falls back to the real defaults`() {
        // selectorWidth falls back to the textlist's OWN width, while
        // selectorHeight falls back to 1.5 font sizes -- deliberately not
        // to lineSpacing, which is a separate property.
        val cfg = config("lineSpacing" to EsDeThemeValue.FloatValue(3f))
        assertEquals(400f, cfg.selectorWidth, 0f)
        assertEquals(15f, cfg.selectorHeight, 0f)
        assertEquals(30f, cfg.entrySize, 0f)
        // Declared values are fractions of the SCREEN, not of the element.
        val declared = config(
            "selectorWidth" to EsDeThemeValue.FloatValue(0.5f),
            "selectorHeight" to EsDeThemeValue.FloatValue(0.05f),
        )
        assertEquals(500f, declared.selectorWidth, 0f)
        assertEquals(50f, declared.selectorHeight, 0f)
    }

    @Test
    fun `the real color fallback chain is a chain, not per-property defaults`() {
        // TextListComponent::applyTheme: selectedColor falls back to
        // primaryColor, selectedSecondaryColor to selectedColor, and
        // selectedSecondaryBackgroundColor to selectedBackgroundColor.
        val cfg = config(
            "primaryColor" to EsDeThemeValue.Color(0x112233FFL),
            "selectedBackgroundColor" to EsDeThemeValue.Color(0x445566FFL),
        )
        assertEquals(0x112233FFL, cfg.primaryColor)
        assertEquals(0x112233FFL, cfg.selectedColor)
        assertEquals(0x112233FFL, cfg.selectedSecondaryColor)
        assertEquals(0x445566FFL, cfg.selectedSecondaryBackgroundColor)
        // selectorColorEnd likewise mirrors selectorColor, so declaring
        // only the latter gives a flat bar rather than a gradient into a
        // built-in default.
        val selector = config("selectorColor" to EsDeThemeValue.Color(0xAABBCCFFL))
        assertEquals(0xAABBCCFFL, selector.selectorColorEnd)
    }

    @Test
    fun `secondaryColor keeps its own real default when undeclared`() {
        val cfg = config()
        assertEquals(0x0000FFFFL, cfg.primaryColor)
        assertEquals(0x00FF00FFL, cfg.secondaryColor)
        assertEquals(0x00000000L, cfg.selectedBackgroundColor)
        assertNull(cfg.selectorImagePath)
    }

    @Test
    fun `real defaults and clamps for the remaining properties`() {
        val defaults = config()
        assertEquals(EsDePrimaryAlignment.LEFT, defaults.alignment)
        assertEquals(0f, defaults.horizontalMargin, 0f)
        assertEquals(EsDeLetterCase.NONE, defaults.letterCase)
        assertEquals(EsDeLetterCase.UNDEFINED, defaults.letterCaseAutoCollections)
        assertEquals(EsDeIndicators.SYMBOLS, defaults.indicators)
        assertEquals(3000f, defaults.horizontalScrollDelayMs, 0f)

        val clamped = config(
            "lineSpacing" to EsDeThemeValue.FloatValue(9f),
            "textHorizontalScrollSpeed" to EsDeThemeValue.FloatValue(99f),
            "textHorizontalScrollDelay" to EsDeThemeValue.FloatValue(99f),
            "selectedBackgroundMargins" to EsDeThemeValue.Pair(9f, 0.25f),
            "indicators" to EsDeThemeValue.Str("nonsense"),
        )
        assertEquals(3f, clamped.lineSpacing, 0f)
        assertEquals(10f, clamped.horizontalScrollSpeed, 0f)
        assertEquals(10000f, clamped.horizontalScrollDelayMs, 0f)
        assertEquals(500f, clamped.selectedBackgroundMarginsX, 0f)
        assertEquals(250f, clamped.selectedBackgroundMarginsY, 0f)
        // An unrecognized value warns and falls back to symbols in real
        // ES-DE; it is not a parse failure.
        assertEquals(EsDeIndicators.SYMBOLS, clamped.indicators)
    }

    @Test
    fun `indicator prefixes match real ES-DE`() {
        assertEquals("* ", esDeIndicatorPrefix(EsDeIndicators.ASCII, isFavorite = true))
        assertEquals("", esDeIndicatorPrefix(EsDeIndicators.ASCII, isFavorite = false))
        assertEquals("", esDeIndicatorPrefix(EsDeIndicators.NONE, isFavorite = true))
        assertEquals("★  ", esDeIndicatorPrefix(EsDeIndicators.SYMBOLS, isFavorite = true))
    }

    @Test
    fun `letter case covers all four real values`() {
        assertEquals("SUPER MARIO", EsDeLetterCase.UPPERCASE.applyTo("Super Mario"))
        assertEquals("super mario", EsDeLetterCase.LOWERCASE.applyTo("Super Mario"))
        assertEquals("Super Mario", EsDeLetterCase.CAPITALIZE.applyTo("super mario"))
        assertEquals("super mario", EsDeLetterCase.NONE.applyTo("super mario"))
        assertEquals("super mario", EsDeLetterCase.UNDEFINED.applyTo("super mario"))
    }
}
