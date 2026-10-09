package dev.droidtop.library.social

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import dev.droidtop.library.UserFacingException
import timber.log.Timber

/**
 * The one direct-reply path (docs/SPEC.md "Social: messages from your apps" and "The companion's tabs"): a
 * notification's reply action has its text input filled and is fired, exactly what the notification shade's reply
 * box does. The Social place's conversations and the companion's notification rows both reply through here.
 */
object NotificationReply {
    private const val TAG = "droidtop.Reply"

    /** The actions a notification offers that can be fired. */
    fun actions(n: Notification): List<Notification.Action> = n.actions.orEmpty().filter { it.actionIntent != null }

    /** [actions] as plain data, by their place in that list. */
    fun describe(actions: List<Notification.Action>): List<NoteAction> = actions.mapIndexed { index, action ->
        val inputs = action.remoteInputs.orEmpty()
        NoteAction(
            index = index,
            title = action.title?.toString().orEmpty(),
            semantic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) action.semanticAction else 0,
            hasRemoteInput = inputs.isNotEmpty(),
            freeForm = inputs.any { it.allowFreeFormInput },
        )
    }

    /** The action that takes a typed reply ([AppMessagesModel.pickReply]), or null when the notification has none. */
    fun replyAction(n: Notification): Notification.Action? {
        val actions = actions(n)
        return AppMessagesModel.pickReply(describe(actions))?.let { actions.getOrNull(it.index) }
    }

    /** Fills [action]'s free-form input with [text] and fires it. */
    fun send(context: Context, action: Notification.Action, text: String): Result<Unit> = runCatching {
        val input = action.remoteInputs.orEmpty().first { it.allowFreeFormInput }
        val fill = Intent()
        val results = Bundle().apply { putCharSequence(input.resultKey, text) }
        RemoteInput.addResultsToIntent(action.remoteInputs, fill, results)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
        action.actionIntent.send(context, 0, fill)
        Unit
    }.recoverCatching { e ->
        Timber.tag(TAG).w(e, "Reply failed")
        throw if (e is PendingIntent.CanceledException) UserFacingException("The app no longer takes replies to this") else e
    }
}
