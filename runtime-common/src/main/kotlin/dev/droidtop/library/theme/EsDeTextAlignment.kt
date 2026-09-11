package dev.droidtop.library.theme

/**
 * Real ES-DE text alignment, for the four element types whose component
 * IS a plain `TextComponent`: `text`, `gamelistinfo` (GamelistView.cpp:329,
 * :354 -- both `std::make_unique<TextComponent>()`) and `datetime`/`clock`
 * (GamelistView.cpp:345, :373 -- `DateTimeComponent`, which derives from
 * TextComponent and reads both properties itself at
 * DateTimeComponent.cpp:316-345).
 *
 * The substance here is the DEFAULTS, which are not the obvious ones and
 * which droidtop had wrong on the vertical axis. `TextComponent`'s own
 * default constructor initializes `mHorizontalAlignment {ALIGN_LEFT}` and
 * `mVerticalAlignment {ALIGN_CENTER}` (TextComponent.cpp:33-34), and none
 * of the four view-side construction sites calls `setVerticalAlignment`
 * before `applyTheme` (verified by grep over GamelistView.cpp and
 * SystemView.cpp: neither file contains a single call). So a themed text
 * element with a declared `size` and no `verticalAlignment` of its own
 * centers its line inside that box vertically -- droidtop top-aligned it,
 * which visibly raises every boxed label by half its slack.
 *
 * (The one real exception is `textlist`, whose horizontal default is LEFT
 * *and* whose rows are laid out by their own component, not this path --
 * see EsDeTextListLayout's own alignment comment.)
 *
 * An unrecognized value logs a warning in real ES-DE and leaves the
 * current value in place (DateTimeComponent.cpp:325-345,
 * TextComponent.cpp:483-510), which for a theme-loaded component is the
 * default -- hence `else ->` returning the default here rather than
 * throwing.
 */
enum class EsDeHorizontalAlignment { LEFT, CENTER, RIGHT }

/** See [EsDeHorizontalAlignment]. */
enum class EsDeVerticalAlignment { TOP, CENTER, BOTTOM }

/** Real default ALIGN_LEFT (TextComponent.cpp:33). */
fun esDeHorizontalAlignment(value: String?): EsDeHorizontalAlignment = when (value) {
    "center" -> EsDeHorizontalAlignment.CENTER
    "right" -> EsDeHorizontalAlignment.RIGHT
    else -> EsDeHorizontalAlignment.LEFT
}

/** Real default ALIGN_CENTER (TextComponent.cpp:34). */
fun esDeVerticalAlignment(value: String?): EsDeVerticalAlignment = when (value) {
    "top" -> EsDeVerticalAlignment.TOP
    "bottom" -> EsDeVerticalAlignment.BOTTOM
    else -> EsDeVerticalAlignment.CENTER
}
