package dev.droidtop.runtime

/**
 * What tells one widget's tap from every other's (Droidtop/tracker#563; rig, build 2011: a tap on a freshly placed
 * Downloads widget once started a game). Android keeps one PendingIntent per request code and intent identity, and a
 * widget's update replaces its extras, so two taps that shared both would carry whichever was drawn last. Every tap is
 * keyed by its widget's id as well as its own key: the request code, and for an intent naming its component the data
 * address too, which makes them distinct even when two codes collide. Pure.
 */
object WidgetTaps {
    const val SCHEME = "droidtop-widget"

    fun requestCode(widgetId: Int, key: String): Int = "$widgetId|$key".hashCode()

    /** The address an explicit intent carries: never resolved, only compared. */
    fun address(widgetId: Int, key: String): String = "$SCHEME://$widgetId/" + java.net.URLEncoder.encode(key, "UTF-8")
}
