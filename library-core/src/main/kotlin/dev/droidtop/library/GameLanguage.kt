package dev.droidtop.library

import java.util.Locale

/**
 * A Windows game's language (docs/SPEC.md 7i, "Game properties"; Droidtop/tracker#228): the languages Steam's API
 * names ([steam], partner.steamgames.com "Languages") with the Linux locale Wine reads for each ([locale]). The one
 * table for a game's own language, the prefix's language and the Steamworks shim's; a store's download language is
 * chosen when the game is installed and is not this.
 */
enum class GameLanguage(val steam: String, val locale: String, val label: String) {
    ENGLISH("english", "en_US.utf8", "English"),
    GERMAN("german", "de_DE.utf8", "German"),
    FRENCH("french", "fr_FR.utf8", "French"),
    ITALIAN("italian", "it_IT.utf8", "Italian"),
    SPANISH("spanish", "es_ES.utf8", "Spanish (Spain)"),
    LATAM("latam", "es_MX.utf8", "Spanish (Latin America)"),
    PORTUGUESE("portuguese", "pt_PT.utf8", "Portuguese (Portugal)"),
    BRAZILIAN("brazilian", "pt_BR.utf8", "Portuguese (Brazil)"),
    RUSSIAN("russian", "ru_RU.utf8", "Russian"),
    POLISH("polish", "pl_PL.utf8", "Polish"),
    DUTCH("dutch", "nl_NL.utf8", "Dutch"),
    SWEDISH("swedish", "sv_SE.utf8", "Swedish"),
    DANISH("danish", "da_DK.utf8", "Danish"),
    FINNISH("finnish", "fi_FI.utf8", "Finnish"),
    NORWEGIAN("norwegian", "nb_NO.utf8", "Norwegian"),
    CZECH("czech", "cs_CZ.utf8", "Czech"),
    HUNGARIAN("hungarian", "hu_HU.utf8", "Hungarian"),
    ROMANIAN("romanian", "ro_RO.utf8", "Romanian"),
    TURKISH("turkish", "tr_TR.utf8", "Turkish"),
    UKRAINIAN("ukrainian", "uk_UA.utf8", "Ukrainian"),
    GREEK("greek", "el_GR.utf8", "Greek"),
    BULGARIAN("bulgarian", "bg_BG.utf8", "Bulgarian"),
    THAI("thai", "th_TH.utf8", "Thai"),
    VIETNAMESE("vietnamese", "vi_VN.utf8", "Vietnamese"),
    INDONESIAN("indonesian", "id_ID.utf8", "Indonesian"),
    JAPANESE("japanese", "ja_JP.utf8", "Japanese"),
    KOREAN("koreana", "ko_KR.utf8", "Korean"),
    SIMPLIFIED_CHINESE("schinese", "zh_CN.utf8", "Chinese (Simplified)"),
    TRADITIONAL_CHINESE("tchinese", "zh_TW.utf8", "Chinese (Traditional)"),
    ARABIC("arabic", "ar_SA.utf8", "Arabic"),
    ;

    /** The variables a launch in this language sets: the locale Wine takes its Windows locale from. */
    val environment: Map<String, String> get() = mapOf("LANG" to locale, "LC_ALL" to locale)

    companion object {
        /** The language with Steam's code [steam] (any case), or null. Pure. */
        fun bySteam(steam: String?): GameLanguage? = entries.firstOrNull { it.steam.equals(steam, ignoreCase = true) }

        /** [locale]'s language as Steam names it; English when Steam has none for it. Pure. */
        fun of(locale: Locale): GameLanguage = when (locale.language) {
            "de" -> GERMAN
            "fr" -> FRENCH
            "it" -> ITALIAN
            "es" -> if (locale.country in setOf("ES", "")) SPANISH else LATAM
            "pt" -> if (locale.country == "BR") BRAZILIAN else PORTUGUESE
            "ru" -> RUSSIAN
            "pl" -> POLISH
            "nl" -> DUTCH
            "sv" -> SWEDISH
            "da" -> DANISH
            "fi" -> FINNISH
            "nb", "no" -> NORWEGIAN
            "cs" -> CZECH
            "hu" -> HUNGARIAN
            "ro" -> ROMANIAN
            "tr" -> TURKISH
            "uk" -> UKRAINIAN
            "el" -> GREEK
            "bg" -> BULGARIAN
            "th" -> THAI
            "vi" -> VIETNAMESE
            "id", "in" -> INDONESIAN
            "ja" -> JAPANESE
            "ko" -> KOREAN
            "zh" -> if (locale.country in setOf("TW", "HK", "MO") || locale.script == "Hant") TRADITIONAL_CHINESE else SIMPLIFIED_CHINESE
            "ar" -> ARABIC
            else -> ENGLISH
        }
    }
}
