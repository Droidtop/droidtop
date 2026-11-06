package dev.droidtop.input.chinese

import java.util.Locale

/**
 * Which RIME schema and character forms an input language gets (Droidtop/tracker#903 H8b). rime-data-luna has three
 * schemas over one dictionary, luna_pinyin_simp, luna_pinyin and luna_pinyin_tw (build-data-luna.sh), and each offers
 * the same radio group of output forms, librime filters through OpenCC: `zh_hant` (傳統漢字, the dictionary's own
 * forms, no conversion), `zh_hans` (简化字, t2s), `zh_hant_hk` (香港字形, t2hk) and `zh_hant_tw` (臺灣字形, t2tw).
 * Pure.
 */
object RimeSchemas {
    /** The radio group's options, every one of which is set on each change, since librime's set_option sets just one. */
    val FORM_OPTIONS = listOf("zh_hant", "zh_hans", "zh_hant_hk", "zh_hant_tw")

    /** The schemas [forLanguage] gives, each of which must select before the engine is Ready. */
    val SCHEMAS = listOf("luna_pinyin_simp", "luna_pinyin_tw")

    /** librime's switch between Chinese and passing letters through. */
    const val ASCII_MODE = "ascii_mode"

    /** A language's schema: [traditional] is the option its 繁 mode sets; [simplifiedFirst] orders its modes. */
    class Choice(val schema: String, val traditional: String, val simplifiedFirst: Boolean) {
        override fun equals(other: Any?) =
            other is Choice && other.schema == schema && other.traditional == traditional && other.simplifiedFirst == simplifiedFirst
        override fun hashCode() = schema.hashCode() * 31 + traditional.hashCode()
        override fun toString() = "$schema/$traditional${if (simplifiedFirst) "/simp" else ""}"
    }

    /** Whether RIME types [language] ("zh", "zh_CN", "zh-TW"). */
    @JvmStatic
    fun serves(language: String): Boolean = parts(language).firstOrNull() == "zh"

    /**
     * The schema for [language], null when it is not Chinese. zh_TW gets luna_pinyin_tw with Taiwan forms; zh_HK and
     * zh_MO the same schema with Hong Kong forms; zh, zh_CN, zh_SG and the rest luna_pinyin_simp, simplified first, with
     * 傳統漢字 as its traditional mode. A script subtag (Hans, Hant) counts as its region would.
     */
    @JvmStatic
    fun forLanguage(language: String): Choice? {
        val parts = parts(language)
        if (parts.firstOrNull() != "zh") return null
        val rest = parts.drop(1)
        return when {
            "hk" in rest || "mo" in rest -> Choice("luna_pinyin_tw", "zh_hant_hk", simplifiedFirst = false)
            "tw" in rest || ("hant" in rest && "cn" !in rest && "sg" !in rest) -> Choice("luna_pinyin_tw", "zh_hant_tw", simplifiedFirst = false)
            else -> Choice("luna_pinyin_simp", "zh_hant", simplifiedFirst = true)
        }
    }

    /** The options to set for simplified ([simplified] true) or [choice]'s traditional forms: one true, the rest false. */
    @JvmStatic
    fun formOptions(choice: Choice, simplified: Boolean): List<Pair<String, Boolean>> {
        val on = if (simplified) "zh_hans" else choice.traditional
        return FORM_OPTIONS.map { it to (it == on) }
    }

    private fun parts(language: String): List<String> =
        language.split('_', '-').filter { it.isNotEmpty() }.map { it.lowercase(Locale.ROOT) }
}
