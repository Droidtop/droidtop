package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import dev.droidtop.library.integrations.JobOpen
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.library.settings.Place
import dev.droidtop.pluginhost.OpenTarget
import java.io.File

/**
 * Open on a job or a line of its report (docs/SPEC.md "Jobs and their results", review finding 15), installed into
 * [JobOpen] at start: a game opens its page (in Gaming over the row, [dev.droidtop.library.GameSurfaces]), a place opens where the person is (Gaming or the screen host), and a
 * folder or a file on shared storage opens in Android's own files app, which is what can show it whatever the
 * person has installed. A path outside shared storage (droidtop's private folders) has nothing that can open it, so
 * it does not.
 */
object JobOpener {
    fun install() {
        JobOpen.opener = ::open
    }

    private fun open(context: Context, target: OpenTarget): Boolean {
        // In Gaming a game's page opens over the job row, so Back returns to it; Standard's own games screen has its
        // page; any other mode has none to offer, and a job row never opens another mode's (Droidtop/tracker#639).
        if (target is OpenTarget.Game && dev.droidtop.library.GameSurfaces.showPage?.invoke(target.entryId) == true) return true
        val inStandard = dev.droidtop.library.settings.Modes.lastMode(context) == dev.droidtop.library.settings.Mode.LAUNCHER.id
        val intent = when (target) {
            is OpenTarget.Game -> if (inStandard) LauncherGamesActivity.pageIntent(context, target.entryId) else null
            is OpenTarget.Place -> Place.byScreenId(target.id)?.let { PlaceLinks.intent(context, it) }
                ?: CatalogScreenLink.intent(context, target.id)
            is OpenTarget.Folder -> documentIntent(File(target.path), DocumentsContract.Document.MIME_TYPE_DIR)
            is OpenTarget.File -> documentIntent(File(target.path), mimeOf(target.path))
        } ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    /** The files app's view of [file], when it is on the primary shared storage; null when nothing can show it. */
    private fun documentIntent(file: File, mime: String): Intent? {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val path = file.absolutePath
        if (path != root && !path.startsWith("$root/")) return null
        val relative = path.removePrefix(root).trimStart('/')
        val uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:$relative")
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun mimeOf(path: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substringAfterLast('.', "").lowercase()) ?: "*/*"

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
}
