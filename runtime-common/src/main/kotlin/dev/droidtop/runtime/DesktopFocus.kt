package dev.droidtop.runtime

/**
 * Word that another desktop window has the focus (docs/SPEC.md 3d "Keyboard", Droidtop/tracker#787 slice 6): the
 * Desktop shell, which follows the compositor's windows, calls [changed]; the app's keyboard layout listens, so a
 * container with a layout of its own types in it while its window has the focus. One listener; main thread.
 */
object DesktopFocus {
    @Volatile
    var listener: (() -> Unit)? = null

    fun changed() {
        listener?.invoke()
    }
}
