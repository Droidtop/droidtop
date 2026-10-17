package dev.droidtop.runtime

import android.util.Log
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The one way a failure reaches the screen (docs/SPEC.md, copy rules: "no backend error text"). The throwable goes to
 * the log, where it helps; the person gets a plain clause saying what kind of thing went wrong. Code that knows the
 * words a person should read throws [PlainFailure] with them, and those words are shown as they are.
 */
object FailureText {
    /** Logs [failure] under [tag] and returns a plain clause for it, to follow "Not saved: " and the like. */
    fun of(tag: String, failure: Throwable): String {
        Log.w(tag, "failed", failure)
        return reason(failure)
    }

    /** The plain clause for [failure], without logging. Pure. */
    fun reason(failure: Throwable): String = when (failure) {
        is PlainFailure -> failure.message.orEmpty()
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> "there is no connection"
        is SocketTimeoutException -> "the connection timed out"
        is FileNotFoundException -> "the file could not be found"
        is SecurityException -> "Android did not allow it"
        is IOException -> "reading or writing failed"
        is OutOfMemoryError -> "there was not enough memory"
        else -> "something went wrong (the details are in the log)"
    }
}

/** A failure whose [message] is already written for a person, so [FailureText] shows it as it is. */
class PlainFailure(message: String) : RuntimeException(message)
