package dev.droidtop.app

import android.appwidget.AppWidgetHost
import android.content.Context

/**
 * The one `AppWidgetHost` every companion surface shares.
 *
 * There were three, on two different host ids, and the reasoning written
 * beside them was backwards. Widget ids belong to the host that allocated
 * them: only [CompanionActivity] ever calls `allocateAppWidgetId`, and the
 * other surfaces read those ids back out of [CompanionWidgetPrefs] and
 * hand them to their own host. A host cannot render a widget id another
 * host owns, so widgets were unlikely to appear anywhere except the
 * screen that added them — while the code claimed distinct ids were
 * required to stop the hosts fighting.
 *
 * They do have to avoid fighting, but the answer is one host instance
 * rather than one host id each. Two `AppWidgetHost` objects sharing an id
 * in a process both try to own the same listener set; separate ids simply
 * break ownership instead.
 *
 * [startListening]/[stopListening] are reference counted, because the
 * companion can legitimately be on screen twice at once — the
 * `SECONDARY_HOME` activity on one panel and the live `Presentation` on
 * another (docs/SPEC.md §4c). Without counting, whichever surface stopped
 * first would freeze the other's widgets: a clock that never ticks, a
 * now-playing card stuck on the last track.
 */
object CompanionWidgets {

    /**
     * Kept at [CompanionActivity]'s original id so widgets users have
     * already added stay bound. Changing it would orphan them.
     */
    const val HOST_ID = 0xD801

    @Volatile
    private var host: AppWidgetHost? = null
    private var listeners = 0

    @Synchronized
    fun host(context: Context): AppWidgetHost =
        host ?: AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }

    /** Idempotent per caller; the underlying host starts once. */
    @Synchronized
    fun startListening(context: Context) {
        val instance = host(context)
        if (listeners == 0) {
            // A host with no bound widgets throws on some OEM builds
            // rather than no-opping, and a companion with no widgets yet
            // is the normal first-run state.
            runCatching { instance.startListening() }
        }
        listeners++
    }

    @Synchronized
    fun stopListening() {
        if (listeners == 0) return
        listeners--
        if (listeners == 0) {
            runCatching { host?.stopListening() }
        }
    }
}
