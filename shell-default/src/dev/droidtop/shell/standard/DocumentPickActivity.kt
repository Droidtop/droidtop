package dev.droidtop.shell.standard

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Standard's one way to ask for a file (Droidtop/tracker#771): the system's picker opens as the screen starts, and the
 * file chosen goes to [onPicked]. Nothing chosen finishes the screen. Registered with the activity result API, so a
 * screen recreated while the picker was open still gets the answer. Its screens are the picture for a home-screen item
 * ([ItemPictureActivity]) and the Nova or Apex backup to import ([LauncherImportActivity]).
 */
abstract class DocumentPickActivity : ComponentActivity() {
    /** The kinds of file the picker offers. */
    protected abstract val mimeTypes: Array<String>

    /** The file the person chose. */
    protected abstract fun onPicked(uri: Uri)

    /** No picker could open; finishes by default. */
    protected open fun onNoPicker() = finish()

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) finish() else onPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        try {
            pick.launch(mimeTypes)
        } catch (e: Exception) {
            Log.w("droidtop.DocumentPick", "no file picker", e)
            onNoPicker()
        }
    }
}
