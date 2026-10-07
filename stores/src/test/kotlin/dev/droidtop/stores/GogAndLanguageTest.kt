package dev.droidtop.stores

import dev.droidtop.stores.gog.GOGConstants
import dev.droidtop.stores.gog.GOGManager
import dev.droidtop.stores.util.StoreLanguage
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** GOG's play-task arguments and the language store downloads take (docs/SPEC.md 7g, "Stores"). */
class GogAndLanguageTest {

    @Test
    fun `play task arguments split on spaces and keep quoted runs whole`() {
        assertEquals(listOf("-windowed", "-lang", "en"), GOGManager.splitArguments("-windowed  -lang en"))
        assertEquals(listOf("--config", "My Settings.ini", "-x"), GOGManager.splitArguments("--config \"My Settings.ini\" -x"))
        assertEquals(emptyList<String>(), GOGManager.splitArguments("   "))
    }

    @Test
    fun `the device language picks the store download language, English otherwise`() {
        assertEquals("german", StoreLanguage.forLocale(Locale.GERMANY))
        assertEquals("brazilian", StoreLanguage.forLocale(Locale("pt", "BR")))
        assertEquals("portuguese", StoreLanguage.forLocale(Locale("pt", "PT")))
        assertEquals("latam", StoreLanguage.forLocale(Locale("es", "MX")))
        assertEquals("spanish", StoreLanguage.forLocale(Locale("es", "ES")))
        assertEquals("tchinese", StoreLanguage.forLocale(Locale.TRADITIONAL_CHINESE))
        assertEquals("schinese", StoreLanguage.forLocale(Locale.SIMPLIFIED_CHINESE))
        assertEquals("koreana", StoreLanguage.forLocale(Locale.KOREA))
        assertEquals("english", StoreLanguage.forLocale(Locale("eo")))
    }

    @Test
    fun `every language the device can answer with is one GOG knows`() {
        val tags = listOf("ar", "bg", "zh-TW", "zh-CN", "cs", "da", "nl", "en", "fi", "fr", "de", "el", "hu", "it", "ja", "ko", "nb", "pl",
            "pt-BR", "pt-PT", "ro", "ru", "es-ES", "es-MX", "sv", "th", "tr", "uk", "vi")
        for (tag in tags) {
            val name = StoreLanguage.forLocale(Locale.forLanguageTag(tag))
            assertEquals(tag, name, GOGConstants.containerLanguageToGogCodes(name).first())
        }
    }
}
