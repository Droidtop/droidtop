package dev.droidtop.ui.shared

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.theme.GamingThemeColors
import dev.droidtop.library.theme.NoticeColors

/**
 * The notice colours as Compose colours (docs/SPEC.md 7k, "Notices"): what
 * [NoticeCard] and the danger dialog draw with. Built from [NoticeColors],
 * which the theme mapping derives once per palette; a surface provides its
 * own through [LocalNoticePalette] (Gaming: the active theme's), and every
 * other surface gets droidtop's own.
 */
@Immutable
class NoticePalette(val colors: NoticeColors) {
    val muted = Color(colors.muted)
    val dangerFill = Color(colors.dangerFill)
    val onDangerFill = Color(colors.onDangerFill)

    private val inks = listOf(colors.infoInk, colors.cautionInk, colors.dangerInk).map { Color(it) }
    private val plates = listOf(colors.infoPlate, colors.cautionPlate, colors.dangerPlate).map { Color(it) }
    private val ons = listOf(colors.onInfo, colors.onCaution, colors.onDanger).map { Color(it) }

    /** The tone's glyph, title and edge-bar colour. */
    fun ink(tone: NoticeTone): Color = inks[tone.ordinal]

    /** The tone's opaque plate. */
    fun plate(tone: NoticeTone): Color = plates[tone.ordinal]

    /** Body text on the tone's plate. */
    fun on(tone: NoticeTone): Color = ons[tone.ordinal]

    companion object {
        /** droidtop's own palette's notices; built on first use, which [warm] moves off the main thread. */
        val Default: NoticePalette by lazy { of(GamingThemeColors.DEFAULT) }

        fun of(colors: GamingThemeColors, highContrast: Boolean = false): NoticePalette =
            NoticePalette(NoticeColors.derive(colors, highContrast))

        /** Builds [Default] now; called off the main thread at process start so no composition pays for it. */
        fun warm() {
            Default
        }
    }
}

/** The notice palette of the surface being drawn; droidtop's own unless a themed surface provides one. */
val LocalNoticePalette = staticCompositionLocalOf { NoticePalette.Default }
