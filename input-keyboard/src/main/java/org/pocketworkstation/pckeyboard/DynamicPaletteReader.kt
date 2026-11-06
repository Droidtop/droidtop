package org.pocketworkstation.pckeyboard

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Reads Android's Material You palette (Droidtop/tracker#903 H12b): the `android.R.color.system_*` tones, which exist
 * from Android 12 (API 31). A class of its own so nothing below Android 12 loads it: its only caller is
 * [DynamicKeyColors.palette], under SDK_INT >= 31. Its shape names only [Context] and Kotlin types, so
 * build-scripts/check_class_load_api.py needs no allowlist line; the tone ids are constants the compiler inlines.
 */
@RequiresApi(Build.VERSION_CODES.S)
internal object DynamicPaletteReader {
    /** The three families the keyboard uses, each in [DynamicKeyColors.TONES] order. Reads resources only. */
    fun read(context: Context): DynamicKeyColors.TonalPalette = DynamicKeyColors.TonalPalette(
        accent1 = ACCENT1.map { context.getColor(it) },
        neutral1 = NEUTRAL1.map { context.getColor(it) },
        neutral2 = NEUTRAL2.map { context.getColor(it) },
    )

    private val ACCENT1 = intArrayOf(
        android.R.color.system_accent1_0, android.R.color.system_accent1_10, android.R.color.system_accent1_50,
        android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_300,
        android.R.color.system_accent1_400, android.R.color.system_accent1_500, android.R.color.system_accent1_600,
        android.R.color.system_accent1_700, android.R.color.system_accent1_800, android.R.color.system_accent1_900,
        android.R.color.system_accent1_1000,
    )

    private val NEUTRAL1 = intArrayOf(
        android.R.color.system_neutral1_0, android.R.color.system_neutral1_10, android.R.color.system_neutral1_50,
        android.R.color.system_neutral1_100, android.R.color.system_neutral1_200, android.R.color.system_neutral1_300,
        android.R.color.system_neutral1_400, android.R.color.system_neutral1_500, android.R.color.system_neutral1_600,
        android.R.color.system_neutral1_700, android.R.color.system_neutral1_800, android.R.color.system_neutral1_900,
        android.R.color.system_neutral1_1000,
    )

    private val NEUTRAL2 = intArrayOf(
        android.R.color.system_neutral2_0, android.R.color.system_neutral2_10, android.R.color.system_neutral2_50,
        android.R.color.system_neutral2_100, android.R.color.system_neutral2_200, android.R.color.system_neutral2_300,
        android.R.color.system_neutral2_400, android.R.color.system_neutral2_500, android.R.color.system_neutral2_600,
        android.R.color.system_neutral2_700, android.R.color.system_neutral2_800, android.R.color.system_neutral2_900,
        android.R.color.system_neutral2_1000,
    )
}
