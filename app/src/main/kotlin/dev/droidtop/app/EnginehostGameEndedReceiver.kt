package dev.droidtop.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.droidtop.library.stores.EnginehostSaves
import dev.droidtop.library.stores.StoreSaves
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Enginehost's report that a game droidtop launched has ended (`dev.enginehost.GAME_ENDED`, sent to droidtop's
 * package alone; Droidtop/tracker#580). For a store game whose saves droidtop keeps in sync, it starts the upload
 * job, as a Wine game's screen does when it closes. Its extras: `path` (the game folder), `saveFolder` (the folder
 * the engine actually used), `reason`, and for a launch flagged `savesSynced` the reason it refused droidtop's folder
 * (`saveRootRefused`). Enginehost reports exactly the `saveRoot` droidtop passed when it took it; when it saved to the
 * person's own location instead, those saves are brought into droidtop's folder first
 * ([EnginehostSaves.afterGameEnded]), and an engine that saves beside the game leaves nothing to upload.
 *
 * A broadcast naming a folder droidtop never launched starts nothing, so another app sending one can at most ask
 * for a sync of a game droidtop already syncs.
 */
class EnginehostGameEndedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != dev.droidtop.library.EngineHost.ACTION_GAME_ENDED) return
        val path = intent.getStringExtra("path") ?: return
        val saveFolder = intent.getStringExtra("saveFolder")
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val key = EnginehostSaves.keyForFolder(app, path) ?: return@launch
                intent.getStringExtra("saveRootRefused")?.let { why ->
                    android.util.Log.i(TAG, "Enginehost did not take droidtop's save folder for $path: $why")
                }
                if (!EnginehostSaves.afterGameEnded(app, key, path, saveFolder)) {
                    android.util.Log.i(TAG, "$path ended (${intent.getStringExtra("reason")}) saving to $saveFolder: nothing to upload")
                    return@launch
                }
                StoreSaves.afterExit(app, key)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "droidtop.EnginehostSaves"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
