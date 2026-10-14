package dev.droidtop.runtime.tasks

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Ends Enginehost's running game through Enginehost itself (`dev.enginehost.END_GAME`, Enginehost docs/SPEC.md "Ending
 * the running game"): the task manager's close step for Enginehost when no helper can force-stop it
 * ([CloseStep.ENGINEHOST_END_GAME]). Enginehost answers droidtop alone, by Android's own record of the caller, which
 * exists only for `startActivityForResult`; so this invisible activity starts the request, takes the answer and
 * finishes, and [request] waits for it on the caller's (background) thread. Before this, Kill on an engine game without
 * Shizuku could only ask Android and said "Not confirmed" even when the game had gone (Droidtop/tracker#289).
 */
class EnginehostEndGame : Activity() {
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null || waiting[id] == null) {
            finish()
            return
        }
        if (savedInstanceState != null) return
        val ask = Intent(ACTION_END_GAME).setPackage(TaskPolicy.ENGINEHOST_PACKAGE)
        try {
            startActivityForResult(ask, REQUEST)
        } catch (e: ActivityNotFoundException) {
            // An Enginehost older than END_GAME, or none installed: nothing to ask.
            answer(id, null)
        } catch (e: SecurityException) {
            answer(id, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val ended = if (resultCode == RESULT_OK) data?.getBooleanExtra(EXTRA_ENDED, false) ?: false else null
        if (ended == null) Log.i(TAG, "Enginehost refused END_GAME: ${data?.getStringExtra(EXTRA_ERROR) ?: "no answer"}")
        answer(id, ended)
    }

    override fun onDestroy() {
        // Gone without an answer (Android took the activity away): that is "could not ask".
        if (!answered && isFinishing) intent.getStringExtra(EXTRA_ID)?.let { waiting.remove(it)?.give(null) }
        super.onDestroy()
    }

    private fun answer(id: String, ended: Boolean?) {
        answered = true
        waiting.remove(id)?.give(ended)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private class Answer {
        val latch = CountDownLatch(1)
        val value = AtomicReference<Boolean?>(null)

        fun give(ended: Boolean?) {
            value.set(ended)
            latch.countDown()
        }
    }

    companion object {
        private const val TAG = "droidtop.TaskManager"
        private const val EXTRA_ID = "dev.droidtop.runtime.tasks.endgame.id"
        private const val REQUEST = 1

        /** Enginehost's contract (LaunchEntryActivity in Droidtop/enginehost). */
        const val ACTION_END_GAME = "dev.enginehost.END_GAME"
        const val EXTRA_ENDED = "ended"
        const val EXTRA_ERROR = "error"

        /** A person pressed Kill and is watching; Enginehost answers at once, so a long silence means the start was blocked. */
        private const val TIMEOUT_MS = 4_000L

        private val waiting = ConcurrentHashMap<String, Answer>()

        /**
         * Asks Enginehost to end its game and blocks until it answers: true when a game was running and has ended,
         * false when none was running, null when Enginehost could not be asked (not installed, too old, the start was
         * refused or timed out) or refused droidtop. Never on the main thread.
         */
        fun request(context: Context): Boolean? {
            val id = UUID.randomUUID().toString()
            val answer = Answer()
            waiting[id] = answer
            val start = Intent(context, EnginehostEndGame::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_ID, id)
            return try {
                context.startActivity(start)
                if (answer.latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    answer.value.get()
                } else {
                    Log.i(TAG, "Enginehost did not answer END_GAME in $TIMEOUT_MS ms")
                    null
                }
            } catch (e: RuntimeException) {
                Log.d(TAG, "Couldn't ask Enginehost to end its game", e)
                null
            } finally {
                waiting.remove(id)
            }
        }
    }
}
