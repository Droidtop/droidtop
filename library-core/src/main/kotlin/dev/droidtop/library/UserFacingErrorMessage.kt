package dev.droidtop.library

import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/** A failure whose message is already a sentence for the person ("This conversation can only be answered in its app"). */
class UserFacingException(message: String) : Exception(message)

/**
 * A user-facing sentence for a failure, never the raw exception message.
 * The exception detail is logged separately with [android.util.Log.w].
 */
fun userFacingErrorMessage(exception: Throwable?): String {
    return when {
        exception is UserFacingException -> exception.message ?: "Something went wrong. Try again in a moment."
        exception is IOException || exception is UnknownHostException || exception is TimeoutException -> {
            "Could not connect. Check your connection and try again."
        }
        else -> "Something went wrong. Try again in a moment."
    }
}
