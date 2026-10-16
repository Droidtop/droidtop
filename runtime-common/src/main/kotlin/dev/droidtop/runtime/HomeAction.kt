package dev.droidtop.runtime

/**
 * What a home-screen gesture or a home icon's swipe does in Standard (docs/SPEC.md, Launcher mode "Gestures" and
 * "Icon swipes"; Droidtop/tracker#564): one of Standard's own actions, by its `GestureAction` name, or starting an
 * app. One type and one stored form for both, so a gesture slot, an icon's swipe and an imported Nova or Apex action
 * all mean the same thing. Pure.
 */
sealed class HomeAction {
    /** One of Standard's `GestureAction`s, by name (`OPEN_DRAWER`, `LOCK_SCREEN`, ...). */
    data class Droidtop(val action: String) : HomeAction()

    /** Start an app's launcher activity, [component] flattened (`pkg/pkg.Cls`). */
    data class Launch(val component: String) : HomeAction()

    /** The stored form: the action's name, or [APP_PREFIX] and the component. */
    val encoded: String
        get() = when (this) {
            is Droidtop -> action
            is Launch -> APP_PREFIX + component
        }

    companion object {
        const val APP_PREFIX = "app:"
        const val NONE = "NONE"

        /** The stored form back; null for nothing stored, `NONE`, or an app with no component. */
        fun decode(text: String?): HomeAction? {
            val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (value.startsWith(APP_PREFIX)) {
                val component = value.removePrefix(APP_PREFIX).trim()
                return if ('/' in component) Launch(component) else null
            }
            return if (value == NONE) null else Droidtop(value)
        }
    }
}
