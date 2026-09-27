package dev.droidtop.display

/**
 * Which secondary display, if any, an Activity-based droidtop surface
 * (MainActivity's own SecondScreenPresentation) currently owns.
 *
 * Read by SecondScreenAttachService (:app, docs/SPEC.md 4c "Attaching
 * without Home") before it shows its own Presentation: that service exists
 * for when droidtop does NOT hold the Home role, so SecondaryDisplayActivity
 * (this module's own SECONDARY_HOME activity) never gets placed by the
 * platform -- but MainActivity can still be opened as an ordinary app
 * (from another launcher's drawer) whatever the Home role says, and its
 * own display orchestration already covers the second screen correctly
 * whenever it does. Without this flag the service could not tell that
 * case apart from "nothing of droidtop's is on the display", and would
 * stack a second window on top of MainActivity's own.
 *
 * [SecondaryDisplayActivity.resumedDisplayId] answers the same question
 * for the OTHER Activity-based surface (the idle SECONDARY_HOME one) and
 * is checked alongside this, not folded into it: that Activity only ever
 * runs while droidtop DOES hold Home, which is already the condition the
 * service stands down for entirely.
 */
object SecondScreenOwnership {
    @Volatile
    var activityOwnedDisplayId: Int? = null
}
