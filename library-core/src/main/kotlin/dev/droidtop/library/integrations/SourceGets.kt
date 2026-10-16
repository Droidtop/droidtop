package dev.droidtop.library.integrations

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * A search row's Get (docs/SPEC.md 13, "There is no Get games sheet"; docs/plugin-api.md "Fetchers answer in the one
 * search", Droidtop/tracker#218): the source's `acquire` job for a result that carries `get`, started from the row.
 * It runs in a scope of its own, not the search's, because it is a download: leaving the search does not stop it, and
 * its progress is in Downloads like every other.
 */
object SourceGets {
    // Lazy: the Main dispatcher does not exist in a JVM unit test, which only reads the lines.
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    /** The line the search shows while [title] is being fetched. */
    fun startedLine(title: String): String = "Getting $title. It is in Downloads"

    /** Starts [hit]'s Get into [destination]; [onStatus] hears the job's lines and then its outcome's sentence. */
    fun start(
        context: Context,
        hit: SourceHit,
        systemId: String?,
        systemName: String?,
        destination: File,
        onStatus: (String) -> Unit,
    ): Job {
        val app = context.applicationContext
        return scope.launch {
            val outcome = hit.source.get(app, hit.result, systemId, systemName, destination, onStatus)
            onStatus(outcome.message)
        }
    }
}
