package dev.droidtop.library.theme

import dev.droidtop.notices.NoticeColors
import dev.droidtop.notices.NoticeInputs

/**
 * What the notice colours (the shared `vendor/notices` library, docs/SPEC.md 7k "Notices") are derived from: the
 * theme's own surfaces and roles.
 */
fun GamingThemeColors.noticeInputs(): NoticeInputs = NoticeInputs(
    ground = ground,
    overlaySurface = overlaySurface,
    card = card,
    onSurface = onSurface,
    onSurfaceMuted = onSurfaceMuted,
    accent = accent,
    danger = danger,
)

/** The notice colours of this theme. */
fun GamingThemeColors.noticeColors(highContrast: Boolean = false): NoticeColors = NoticeColors.derive(noticeInputs(), highContrast)
