package org.pocketworkstation.pckeyboard

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The keyboard's background threads (Droidtop/tracker#772: the same executor was spelled out in six places). */
object KeyboardThreads {
    /**
     * One daemon thread named [name], running its tasks in order: a job's file, model or scoring work, kept off the
     * main thread and never keeping the process alive.
     */
    fun single(name: String): ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }
}
