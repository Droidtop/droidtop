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
 * the engine actually used) and `reason`. When the engine did not use droidtop's folder (Enginehost refused the
 * path, or the engine saves beside the game) nothing is uploaded: that folder holds nothing new.
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
                if (!EnginehostSaves.usedOurFolder(key, saveFolder)) {
                    android.util.Log.i(TAG, "$path ended (${intent.getStringExtra("reason")}) saving to $saveFolder, not droidtop's folder: nothing to upload")
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
