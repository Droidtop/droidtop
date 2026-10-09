package dev.droidtop.runtime

/**
 * Which XKB layout the desktop's physical keyboard types in (docs/SPEC.md 6b
 * "Keyboard layout", Droidtop/tracker#387), and how the container compiles
 * it. droidtop ships no keymap data: the keymap is compiled in the primary by
 * the distro's own xkbcommon tool ([compileCommand]) from the distro's own
 * xkeyboard-config, and the list of layouts a person may pick is that
 * xkeyboard-config's `evdev.lst` ([parseLayoutList]).
 */
object KeyboardLayouts {
    /** The setting's value for Automatic; any other value is a [XkbLayout.id]. */
    const val AUTOMATIC = "auto"

    /** The SharedPreferences key, in the launcher prefs file. */
    const val KEY = "pref_desktop_keyboard_layout"

    /** xkeyboard-config's layout list in every distro droidtop provisions. */
    const val LAYOUT_LIST = "/usr/share/X11/xkb/rules/evdev.lst"

    /** The xkbcommon command-line tool per distro, added to the desktop's plan. */
    val CLI_PACKAGES = mapOf("alpine" to "xkbcli", "debian" to "libxkbcommon-tools")

    val US = XkbLayout("us")

    /**
     * A layout as xkbcommon names it. [options] carries a group switch for a
     * layout that cannot type Latin letters: such a layout is offered as
     * "us,<layout>" with Alt+Shift switching, the way a Linux desktop sets one
     * up, so shortcuts and commands stay typeable.
     */
    data class XkbLayout(val layout: String, val variant: String? = null, val options: String? = null) {
        /** The stored form: `layout` or `layout:variant`. */
        val id: String get() = if (variant.isNullOrEmpty()) layout else "$layout:$variant"

        companion object {
            fun fromId(id: String): XkbLayout? {
                val layout = id.substringBefore(':').trim()
                if (!LAYOUT_NAME.matches(layout)) return null
                val variant = id.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }
                if (variant != null && !LAYOUT_NAME.matches(variant)) return null
                return withSwitch(XkbLayout(layout, variant))
            }
        }
    }

    private val LAYOUT_NAME = Regex("[a-z][a-z0-9_-]{0,31}")

    /** Layouts with no Latin letters: compiled as "us,<layout>" with a group switch ([XkbLayout.options]). */
    private val NON_LATIN = setOf("ru", "ua", "by", "bg", "rs", "mk", "gr", "il", "ara", "ir", "th", "kr", "in", "ge", "am")

    private fun withSwitch(layout: XkbLayout): XkbLayout =
        if (layout.layout in NON_LATIN) layout.copy(options = "grp:alt_shift_toggle") else layout

    /**
     * The `xkbcli compile-keymap` argv for [layout]; its standard output is the
     * keymap text host-bridge uploads. A non-Latin layout is the second group
     * after us.
     */
    fun compileCommand(layout: XkbLayout): List<String> = buildList {
        add("xkbcli")
        add("compile-keymap")
        add("--layout")
        add(if (layout.options != null) "us,${layout.layout}" else layout.layout)
        layout.variant?.let {
            add("--variant")
            add(if (layout.options != null) ",$it" else it)
        }
        layout.options?.let {
            add("--options")
            add(it)
        }
    }

    /**
     * A BCP 47 language tag (what Android reports for a physical keyboard, or
     * an on-screen keyboard's language) to an XKB layout, region first
     * ("de-CH" is Swiss, "en-GB" British, "pt-BR" Brazilian). [layoutType] is
     * Android's physical layout type where it gives one ("dvorak",
     * "colemak", "turkish_f"). Null for a language this table does not know:
     * the caller falls back and says so.
     */
    fun fromLanguageTag(tag: String?, layoutType: String? = null): XkbLayout? {
        if (tag.isNullOrBlank()) return null
        val parts = tag.replace('_', '-').split('-')
        val language = parts[0].lowercase()
        val region = parts.drop(1).firstOrNull { it.length == 2 || it.length == 3 && it.all(Char::isDigit) }?.uppercase()
        val base = BY_REGION["$language-$region"] ?: BY_LANGUAGE[language] ?: return null
        val typed = when (layoutType?.lowercase()) {
            "dvorak" -> if (base.layout == "us") base.copy(variant = "dvorak") else base
            "colemak" -> if (base.layout == "us") base.copy(variant = "colemak") else base
            "turkish_f" -> if (base.layout == "tr") base.copy(variant = "f") else base
            else -> base
        }
        return withSwitch(typed)
    }

    private val BY_REGION = mapOf(
        "en-GB" to XkbLayout("gb"), "en-IE" to XkbLayout("ie"),
        "de-CH" to XkbLayout("ch"), "fr-CH" to XkbLayout("ch", "fr"), "it-CH" to XkbLayout("ch"),
        "fr-CA" to XkbLayout("ca"), "fr-BE" to XkbLayout("be"), "nl-BE" to XkbLayout("be"),
        "pt-BR" to XkbLayout("br"), "es-419" to XkbLayout("latam"), "es-MX" to XkbLayout("latam"),
        "es-AR" to XkbLayout("latam"), "es-CO" to XkbLayout("latam"), "es-CL" to XkbLayout("latam"),
    )

    private val BY_LANGUAGE = mapOf(
        "en" to XkbLayout("us"), "de" to XkbLayout("de"), "fr" to XkbLayout("fr"), "es" to XkbLayout("es"),
        "it" to XkbLayout("it"), "pt" to XkbLayout("pt"), "nl" to XkbLayout("nl"), "sv" to XkbLayout("se"),
        "da" to XkbLayout("dk"), "nb" to XkbLayout("no"), "nn" to XkbLayout("no"), "no" to XkbLayout("no"),
        "fi" to XkbLayout("fi"), "is" to XkbLayout("is"), "pl" to XkbLayout("pl"), "cs" to XkbLayout("cz"),
        "sk" to XkbLayout("sk"), "hu" to XkbLayout("hu"), "ro" to XkbLayout("ro"), "hr" to XkbLayout("hr"),
        "sl" to XkbLayout("si"), "et" to XkbLayout("ee"), "lv" to XkbLayout("lv"), "lt" to XkbLayout("lt"),
        "tr" to XkbLayout("tr"), "ru" to XkbLayout("ru"), "uk" to XkbLayout("ua"), "be" to XkbLayout("by"),
        "bg" to XkbLayout("bg"), "sr" to XkbLayout("rs"), "mk" to XkbLayout("mk"), "el" to XkbLayout("gr"),
        "he" to XkbLayout("il"), "iw" to XkbLayout("il"), "ar" to XkbLayout("ara"), "fa" to XkbLayout("ir"),
        "ja" to XkbLayout("jp"), "ko" to XkbLayout("kr"), "zh" to XkbLayout("cn"), "th" to XkbLayout("th"),
        "vi" to XkbLayout("vn"), "ka" to XkbLayout("ge"), "hy" to XkbLayout("am"),
    )

    /**
     * The layout a physical keyboard's own key character map says it has:
     * Android applies the layout the person picked for that keyboard (or its
     * default) to its `KeyCharacterMap`, which is the public way it reports
     * one. [charOf] is the base character a key code produces with no
     * modifier (a dead key's accent, not its marker bit). A few keys tell the
     * common layouts apart: where Q and Y are, and what the key right of L,
     * the apostrophe, grave and backslash keys type. Null for a map that
     * matches none of them; the caller falls back to the on-screen
     * keyboard's language.
     */
    fun fromKeyCharacters(charOf: (keyCode: Int) -> Int): XkbLayout? {
        fun c(keyCode: Int): Char? = charOf(keyCode).takeIf { it > 0 }?.toChar()?.lowercaseChar()
        val q = c(android.view.KeyEvent.KEYCODE_Q)
        val y = c(android.view.KeyEvent.KEYCODE_Y)
        val semicolon = c(android.view.KeyEvent.KEYCODE_SEMICOLON)
        val apostrophe = c(android.view.KeyEvent.KEYCODE_APOSTROPHE)
        val layout = when {
            q == 'й' -> if (c(android.view.KeyEvent.KEYCODE_S) == 'і') "ua" else "ru"
            c(android.view.KeyEvent.KEYCODE_A) == 'α' -> "gr"
            q == 'a' -> "fr"
            y == 'z' -> when (semicolon) {
                'ů' -> "cz"
                'é' -> "hu"
                else -> if (c(android.view.KeyEvent.KEYCODE_GRAVE) == '§') "ch" else "de"
            }
            semicolon == 'ñ' -> if (apostrophe == '{') "latam" else "es"
            semicolon == 'ò' -> "it"
            semicolon == 'ç' -> if (apostrophe == '~') "br" else "pt"
            semicolon == 'ö' -> "se"
            semicolon == 'æ' -> "dk"
            semicolon == 'ø' -> "no"
            semicolon == ';' && q == 'q' -> if (c(android.view.KeyEvent.KEYCODE_BACKSLASH) == '#') "gb" else "us"
            else -> return null
        }
        return withSwitch(XkbLayout(layout))
    }

    /** The layouts [fromLanguageTag] can name, for a choice list before the container's own list is known. */
    val KNOWN: Set<String> get() = (BY_REGION.values + BY_LANGUAGE.values).map { it.layout }.toSortedSet()

    /**
     * The `! layout` section of xkeyboard-config's evdev.lst ("  de  German")
     * as (id, description) pairs, in the file's order.
     */
    fun parseLayoutList(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var inLayouts = false
        for (line in text.lineSequence()) {
            if (line.startsWith("!")) {
                inLayouts = line.trim() == "! layout"
                continue
            }
            if (!inLayouts || line.isBlank()) continue
            val trimmed = line.trim()
            val id = trimmed.substringBefore(' ')
            val description = trimmed.substringAfter(' ', "").trim()
            if (LAYOUT_NAME.matches(id) && description.isNotEmpty()) out += id to description
        }
        return out
    }
}
