package dev.droidtop.library

import android.content.Context
import android.content.Intent

/** The native app page and actual launch resolve the same Android package entry point. */
object NativeAppLaunch {
    const val RUNNER = "Native Android"
    const val MISSING = "This Android app has no launch activity. It may have been removed. Refresh Apps and try again."

    data class State(val available: Boolean, val reason: String? = null)

    val Ready = State(available = true)

    /** Package-manager access: call off the main thread. No PC/engine override applies to a package. */
    fun intent(context: Context, packageName: String): Intent? = context.packageManager.getLaunchIntentForPackage(packageName)

    fun state(context: Context, packageName: String): State = try {
        if (intent(context, packageName) != null) Ready else State(false, MISSING)
    } catch (failure: Exception) {
        State(false, "Could not check Android launch: ${userFacingErrorMessage(failure)}")
    }

    /** A disappeared entry must fail visibly, rather than recording an app as opened when nothing started. */
    fun requireIntent(context: Context, packageName: String): Intent = intent(context, packageName) ?: error(MISSING)
}
