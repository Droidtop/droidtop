package dev.droidtop.stores.util

import java.util.Locale

/**
 * Which language's files a store download takes. GameNative asked the person
 * for a "container language" (Steam's language names: "english", "german",
 * "schinese", ...) and GOG's and Epic's downloads pick their language files by
 * it; droidtop has no such setting, so the device's own language answers,
 * named the way those downloads expect, and English when the device's
 * language is not one of them.
 */
internal object StoreLanguage {
    const val ENGLISH = "english"

    /** The device's language as the store downloads name it. */
    fun current(): String = forLocale(Locale.getDefault())

    fun forLocale(locale: Locale): String {
        val country = locale.country.uppercase()
        val script = locale.script
        return when (locale.language.lowercase()) {
            "ar" -> "arabic"
            "bg" -> "bulgarian"
            "zh" -> if (script == "Hant" || country == "TW" || country == "HK" || country == "MO") "tchinese" else "schinese"
            "cs" -> "czech"
            "da" -> "danish"
            "nl" -> "dutch"
            "en" -> ENGLISH
            "fi" -> "finnish"
            "fr" -> "french"
            "de" -> "german"
            "el" -> "greek"
            "hu" -> "hungarian"
            "it" -> "italian"
            "ja" -> "japanese"
            "ko" -> "koreana"
            "nb", "no", "nn" -> "norwegian"
            "pl" -> "polish"
            "pt" -> if (country == "BR") "brazilian" else "portuguese"
            "ro" -> "romanian"
            "ru" -> "russian"
            "es" -> if (country.isEmpty() || country == "ES") "spanish" else "latam"
            "sv" -> "swedish"
            "th" -> "thai"
            "tr" -> "turkish"
            "uk" -> "ukrainian"
            "vi" -> "vietnamese"
            else -> ENGLISH
        }
    }
}
