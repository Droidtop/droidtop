package dev.droidtop.library.theme

/**
 * `stationary` (GuiComponent.h:127, parsed per element type at e.g.
 * ImageComponent.cpp:563-575): whether an element stays PUT on screen
 * while the view slides past it, instead of travelling with its view.
 *
 * ES-DE's default is NEVER (GuiComponent.cpp:33).
 */
enum class EsDeStationaryMode {
    NEVER,
    ALWAYS,
    WITHIN_VIEW,
    BETWEEN_VIEWS,
    ;

    companion object {
        /**
         * Anything unrecognised is NEVER, as ES-DE's own unmatched branch
         * is (ImageComponent.cpp:573-575 warns and keeps the default).
         */
        fun parse(raw: String?): EsDeStationaryMode = when (raw?.trim()) {
            "always" -> ALWAYS
            "withinView" -> WITHIN_VIEW
            "betweenViews" -> BETWEEN_VIEWS
            else -> NEVER
        }
    }
}

/**
 * The two transition-time element behaviours, both of which ES-DE decides
 * per rendered child in the same two places: GamelistView.cpp:484-548 and
 * SystemView.cpp:1583-1610 plus :1673-1740.
 */
object EsDeTransitionBehaviour {
    /**
     * Whether `stationary` applies at all to a transition of this kind.
     *
     * ES-DE checks for the SLIDE animation three times over
     * (GamelistView.cpp:505-524, SystemView.cpp:1589-1607) and sets
     * `stationaryApplicable` only there: with a fade or a cut nothing is
     * held stationary, because nothing moves for it to be held against.
     * The startup transitions are excluded for the same reason ES-DE
     * excludes them (GamelistView.cpp:502-504): there is no previous view
     * to stay still relative to.
     */
    fun stationaryApplies(
        kind: EsDeViewTransition,
        animation: EsDeTransitionAnimation,
    ): Boolean = animation == EsDeTransitionAnimation.SLIDE &&
        kind != EsDeViewTransition.STARTUP_TO_SYSTEM &&
        kind != EsDeViewTransition.STARTUP_TO_GAMELIST

    /**
     * Whether THIS element is held stationary during a transition of this
     * kind (GamelistView.cpp:526-548, SystemView.cpp:1673-1692).
     *
     * `withinView` covers a move that stays in the same kind of view --
     * gamelist to gamelist, system to system -- and `betweenViews` covers
     * a move from one kind to the other; `always` covers both and `never`
     * neither.
     */
    fun isStationary(
        mode: EsDeStationaryMode,
        kind: EsDeViewTransition,
        animation: EsDeTransitionAnimation,
    ): Boolean {
        if (!stationaryApplies(kind, animation)) return false
        val withinView = kind == EsDeViewTransition.SYSTEM_TO_SYSTEM ||
            kind == EsDeViewTransition.GAMELIST_TO_GAMELIST
        return when (mode) {
            EsDeStationaryMode.NEVER -> false
            EsDeStationaryMode.ALWAYS -> true
            EsDeStationaryMode.WITHIN_VIEW -> withinView
            EsDeStationaryMode.BETWEEN_VIEWS -> !withinView
        }
    }

    /**
     * `renderDuringTransitions` (ImageComponent.cpp:579-580; ES-DE's own
     * default is true, GuiComponent.cpp:35).
     *
     * When a theme turns it off, ES-DE renders the element through
     * `renderChildCondFunc` (GamelistView.cpp:486-500, SystemView.cpp:
     * 1646-1660) instead of directly, and that function draws the child
     * only when the camera is NOT moving, when the view kind did not
     * change, or when the transition into the destination view is not a
     * slide. The camera only ever moves for a slide (ViewController.cpp:
     * 556-563 compares the camera against the view's own position, and
     * fade and instant both snap it), so an element that opts out is
     * hidden exactly while a slide between two DIFFERENT view kinds runs.
     */
    fun rendersDuringTransition(
        renderDuringTransitions: Boolean,
        kind: EsDeViewTransition,
        animation: EsDeTransitionAnimation,
    ): Boolean {
        if (renderDuringTransitions) return true
        if (animation != EsDeTransitionAnimation.SLIDE) return true
        return kind == EsDeViewTransition.SYSTEM_TO_SYSTEM ||
            kind == EsDeViewTransition.GAMELIST_TO_GAMELIST
    }
}
