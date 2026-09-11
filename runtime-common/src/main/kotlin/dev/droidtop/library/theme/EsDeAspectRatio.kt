package dev.droidtop.library.theme

import kotlin.math.abs

/**
 * ES-DE's aspect-ratio axis, ported from `ThemeData.cpp` rather than
 * approximated. Three separate pieces of ES-DE behaviour live here, all
 * of which droidtop previously either skipped or got wrong:
 *
 *  1. [SUPPORTED] is ES-DE's `sSupportedAspectRatios`
 *     (ThemeData.cpp:66-89) IN ORDER, `"automatic"` first, every
 *     landscape ratio immediately followed by its `_vertical`
 *     counterpart. The order is load-bearing, see [capabilityList].
 *  2. [RATIO_MAP] is ES-DE's `sAspectRatioMap` (ThemeData.cpp:92-115).
 *     A `_vertical` entry is the same shape rotated -- height/width --
 *     so 16:9_vertical is 0.5625, i.e. exactly what
 *     `screenWidth / screenHeight` reports for a portrait screen. Screen
 *     ratio is ALWAYS width/height in ES-DE
 *     (Renderer.cpp:305), so a portrait screen simply produces a value
 *     below 1 and matches the vertical entries naturally; nothing is
 *     flipped anywhere.
 *  3. [capabilityList] reproduces ThemeData.cpp:1232-1252 (collect,
 *     dropping unsupported names and duplicates) plus
 *     ThemeData.cpp:1766-1775 (re-emit in `sSupportedAspectRatios`
 *     order, with `"automatic"` PREPENDED whenever the theme declared at
 *     least one ratio). That prepend is the piece droidtop was missing:
 *     ES-DE's per-axis default is `capabilities.aspectRatios.front()`
 *     (ThemeData.cpp:746), and `front()` is therefore ALWAYS
 *     `"automatic"` for any theme that declares aspect ratios at all --
 *     no real theme writes `<aspectRatio>automatic</aspectRatio>` itself
 *     (DEcaffe and Slate both declare only concrete ratios). droidtop
 *     took the theme's first DECLARED ratio as the default instead, so
 *     the screen was never consulted: DEcaffe always rendered 16:9 and
 *     Slate always rendered 16:9, on every device, including a portrait
 *     one where Slate ships a 16:9_vertical made for exactly that case.
 *  4. [select] is ThemeData.cpp:736-771: the user's setting when the
 *     theme declares it, otherwise `front()`; and when that resolves to
 *     `"automatic"`, the closest declared ratio by absolute difference,
 *     seeded with `"16:9"` and its own difference so a theme whose
 *     declared set is all further away than 16:9 still yields "16:9"
 *     (ThemeData.cpp:750-753). `exactMatch` mirrors
 *     `sAspectRatioMatch` (< 0.01 difference, ThemeData.cpp:761-762).
 *
 * The fallback for a portrait screen on a theme with NO vertical variant
 * falls straight out of (4) and is not a separate code path: DEcaffe on
 * 1080x1920 (screen ratio 0.5625) compares 16:9 (diff 1.2152), 4:3
 * (0.7708), 16:10 (1.0375), 21:9 (1.8078), 19.5:9 (1.6042) and selects
 * `4:3` -- its closest LANDSCAPE layout, drawn stretched over the
 * portrait screen, since ES-DE theme coordinates are normalised to the
 * screen. Nothing letterboxes and nothing rotates. That is why the
 * portrait DEFAULT theme has to be one that ships vertical variants
 * (see `ThemeAssets`), rather than something the engine can paper over.
 */
object EsDeAspectRatio {

    /** ES-DE `sSupportedAspectRatios`, ThemeData.cpp:66-89, in order. */
    val SUPPORTED: List<String> = listOf(
        "automatic",
        "16:9", "16:9_vertical",
        "16:10", "16:10_vertical",
        "3:2", "3:2_vertical",
        "4:3", "4:3_vertical",
        "5:3", "5:3_vertical",
        "5:4", "5:4_vertical",
        "8:7", "8:7_vertical",
        "19.5:9", "19.5:9_vertical",
        "20:9", "20:9_vertical",
        "21:9", "21:9_vertical",
        "32:9", "32:9_vertical",
        "1:1",
    )

    /** ES-DE `sAspectRatioMap`, ThemeData.cpp:92-115, values unchanged. */
    val RATIO_MAP: Map<String, Float> = mapOf(
        "16:9" to 1.7777f, "16:9_vertical" to 0.5625f,
        "16:10" to 1.6f, "16:10_vertical" to 0.625f,
        "3:2" to 1.5f, "3:2_vertical" to 0.6667f,
        "4:3" to 1.3333f, "4:3_vertical" to 0.75f,
        "5:3" to 1.6667f, "5:3_vertical" to 0.6f,
        "5:4" to 1.25f, "5:4_vertical" to 0.8f,
        "8:7" to 1.1429f, "8:7_vertical" to 0.875f,
        "19.5:9" to 2.1667f, "19.5:9_vertical" to 0.4615f,
        "20:9" to 2.2222f, "20:9_vertical" to 0.45f,
        "21:9" to 2.3703f, "21:9_vertical" to 0.4219f,
        "32:9" to 3.5555f, "32:9_vertical" to 0.2813f,
        "1:1" to 1.0f,
    )

    /**
     * The human label of each ratio: the SECOND half of every
     * `sSupportedAspectRatios` pair (ThemeData.cpp:66-89), which is what
     * `ThemeData::getAspectRatioLabel` returns (:1018-1028) and what
     * ES-DE's own "THEME ASPECT RATIO" menu shows, uppercased
     * (GuiMenu.cpp:389-393).
     */
    val LABELS: Map<String, String> = mapOf(
        "automatic" to "automatic",
        "16:9" to "16:9", "16:9_vertical" to "16:9 vertical",
        "16:10" to "16:10", "16:10_vertical" to "16:10 vertical",
        "3:2" to "3:2", "3:2_vertical" to "3:2 vertical",
        "4:3" to "4:3", "4:3_vertical" to "4:3 vertical",
        "5:3" to "5:3", "5:3_vertical" to "5:3 vertical",
        "5:4" to "5:4", "5:4_vertical" to "5:4 vertical",
        "8:7" to "8:7", "8:7_vertical" to "8:7 vertical",
        "19.5:9" to "19.5:9", "19.5:9_vertical" to "19.5:9 vertical",
        "20:9" to "20:9", "20:9_vertical" to "20:9 vertical",
        "21:9" to "21:9", "21:9_vertical" to "21:9 vertical",
        "32:9" to "32:9", "32:9_vertical" to "32:9 vertical",
        "1:1" to "1:1",
    )

    /** ES-DE's own `getAspectRatioLabel` (ThemeData.cpp:1018-1028), invalid name included. */
    fun labelFor(aspectRatio: String): String = LABELS[aspectRatio] ?: "invalid ratio"

    /** A theme declares a vertical variant when any of its ratios is a `_vertical` one. */
    fun hasVerticalVariant(capabilities: List<String>): Boolean =
        capabilities.any { it.endsWith("_vertical") }

    /**
     * ThemeData.cpp:1232-1252 + :1766-1775 -- validate and de-duplicate
     * the raw `<aspectRatio>` texts, then re-emit them in [SUPPORTED]
     * order with `"automatic"` first. Empty in, empty out: ES-DE only
     * prepends `"automatic"` when the theme declared something
     * (`if (!aspectRatiosTemp.empty())`).
     */
    fun capabilityList(declared: List<String>): List<String> {
        val accepted = LinkedHashSet<String>()
        for (value in declared) {
            // "automatic" is IN sSupportedAspectRatios, so a theme that
            // declares it passes ES-DE's validation like any other name
            // (ThemeData.cpp:1232-1243) and is then emitted twice, once
            // by the unconditional prepend and once by the loop below
            // (:1766-1775). Kept rather than smoothed over: no real
            // theme declares it, and the only place this shows is the
            // aspect-ratio setting's own option list.
            if (value !in SUPPORTED) continue
            accepted += value
        }
        if (accepted.isEmpty()) return emptyList()
        return buildList {
            add("automatic")
            for (ratio in SUPPORTED) if (ratio in accepted) add(ratio)
        }
    }

    /** What [select] resolved, and whether it was an exact match (ES-DE `sAspectRatioMatch`). */
    data class Selection(val name: String, val exactMatch: Boolean)

    /**
     * ThemeData.cpp:736-771, unchanged.
     *
     * @param capabilities the theme's list as [capabilityList] built it.
     * @param setting the user's "ThemeAspectRatio" choice, honoured only
     *   when the theme actually declares it -- otherwise ES-DE falls back
     *   to `front()`, which [capabilityList] guarantees is `"automatic"`.
     * @param screenAspectRatio the live screen's width/height. Null means
     *   no screen to measure (a headless/unit-test parse), which keeps
     *   the "16:9" seed ES-DE starts from.
     */
    fun select(
        capabilities: List<String>,
        setting: String? = null,
        screenAspectRatio: Float? = null,
    ): Selection {
        // A theme that declares no aspect ratio at all selects NOTHING,
        // not "16:9": ES-DE only enters the block above when
        // `capabilities.aspectRatios.size() > 0` (ThemeData.cpp:738), so
        // `sSelectedAspectRatio` keeps its own empty default
        // (ThemeData.h:301) and `parseAspectRatios` returns immediately
        // on it (ThemeData.cpp:2036-2037) -- no <aspectRatio> block in
        // the theme is applied. Returning "16:9" here instead would
        // apply a 16:9 block of a theme whose capabilities.xml never
        // declared one, which ES-DE treats as a theme error
        // (ThemeData.cpp:2057-2061).
        if (capabilities.isEmpty()) return Selection("", false)
        var selected = if (setting != null && setting in capabilities) setting else capabilities.first()
        if (selected != "automatic") return Selection(selected, false)

        selected = "16:9"
        if (screenAspectRatio == null) return Selection(selected, false)
        var exactMatch = false
        var diff = abs((RATIO_MAP["16:9"] ?: 1.7777f) - screenAspectRatio)
        for (ratio in capabilities) {
            if (ratio == "automatic") continue
            val value = RATIO_MAP[ratio] ?: continue
            val newDiff = abs(value - screenAspectRatio)
            if (newDiff < 0.01f) exactMatch = true
            if (newDiff < diff) {
                diff = newDiff
                selected = ratio
            }
        }
        return Selection(selected, exactMatch)
    }
}
