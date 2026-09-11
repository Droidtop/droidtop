package dev.droidtop.library.theme

/**
 * The three animations a view transition can be
 * (ThemeData.cpp:54-57 `sSupportedTransitionAnimations`). `INSTANT` is
 * also the value every transition starts at before a theme profile is
 * applied (ThemeData.cpp:1053-1054).
 */
enum class EsDeTransitionAnimation { INSTANT, SLIDE, FADE }

/**
 * The six transitions a theme can animate separately
 * (ThemeData.cpp:46-52 `sSupportedTransitions`). The tag names are
 * capabilities.xml's own.
 */
enum class EsDeViewTransition(val tag: String) {
    SYSTEM_TO_SYSTEM("systemToSystem"),
    SYSTEM_TO_GAMELIST("systemToGamelist"),
    GAMELIST_TO_GAMELIST("gamelistToGamelist"),
    GAMELIST_TO_SYSTEM("gamelistToSystem"),
    STARTUP_TO_SYSTEM("startupToSystem"),
    STARTUP_TO_GAMELIST("startupToGamelist"),
}

/**
 * One `<transitions name="...">` block of a theme's capabilities.xml
 * (parsed at ThemeData.cpp:1568-1620). A profile need not name all six
 * transitions; the ones it leaves out keep whatever they already had,
 * which is INSTANT (ThemeData.cpp:1082-1099 only assigns the keys the
 * profile's own map contains).
 */
data class EsDeTransitionProfile(
    val name: String,
    val label: String? = null,
    /** `<selectable>`: whether ES-DE's own theme menu offers this profile by name. */
    val selectable: Boolean = true,
    val animations: Map<EsDeViewTransition, EsDeTransitionAnimation> = emptyMap(),
)

/**
 * Real `ThemeData::setThemeTransitions` (ThemeData.cpp:1042-1120), which
 * decides the animation for every one of the six transitions.
 *
 * The order of the rules is what matters and is easy to get backwards:
 *
 * 1. Everything starts at INSTANT (:1053-1054). A theme that declares no
 *    profile at all therefore cuts between views, and so does droidtop.
 * 2. With the setting on `automatic` (its real default), the profile is
 *    the one the selected VARIANT named if it named one, and otherwise
 *    the theme's FIRST declared profile (:1060-1065). With the setting on
 *    a name, that name is the profile (:1067).
 * 3. If that profile exists, its own per-transition map is applied over
 *    the INSTANT baseline (:1069-1099).
 * 4. Only if it does NOT exist do the two built-in settings mean anything
 *    (:1101-1113), and even then a theme may suppress either of them by
 *    listing it in `suppressedTransitionProfiles`.
 *
 * So a theme profile always beats `builtin-slide`/`builtin-fade`: those
 * are the answer for a theme that has no profile by that name, not an
 * override of one that does.
 */
fun esDeTransitionAnimations(
    profiles: List<EsDeTransitionProfile>,
    setting: String? = null,
    variantDefinedTransitions: String? = null,
    suppressedProfiles: List<String> = emptyList(),
): Map<EsDeViewTransition, EsDeTransitionAnimation> {
    val baseline = EsDeViewTransition.entries.associateWith { EsDeTransitionAnimation.INSTANT }
    val resolvedSetting = setting ?: "automatic"
    val profileName = if (resolvedSetting == "automatic") {
        variantDefinedTransitions?.takeIf { it.isNotEmpty() } ?: profiles.firstOrNull()?.name
    } else {
        resolvedSetting
    }
    val profile = profiles.firstOrNull { it.name == profileName }
    if (profile != null) return baseline + profile.animations
    if (resolvedSetting in setOf("builtin-slide", "builtin-fade") && resolvedSetting !in suppressedProfiles) {
        val animation =
            if (resolvedSetting == "builtin-slide") EsDeTransitionAnimation.SLIDE
            else EsDeTransitionAnimation.FADE
        return EsDeViewTransition.entries.associateWith { animation }
    }
    return baseline
}

/** Parses one `<...>instant|slide|fade</...>` value; anything else is INSTANT, as ES-DE's own unmatched case is. */
fun esDeTransitionAnimation(value: String?): EsDeTransitionAnimation = when (value?.trim()) {
    "slide" -> EsDeTransitionAnimation.SLIDE
    "fade" -> EsDeTransitionAnimation.FADE
    else -> EsDeTransitionAnimation.INSTANT
}
