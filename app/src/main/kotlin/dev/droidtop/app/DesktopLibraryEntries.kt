package dev.droidtop.app

import android.content.Context
import android.net.Uri
import android.os.FileObserver
import android.util.Log
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryKinds
import dev.droidtop.runtime.ContainerLauncher
import dev.droidtop.runtime.ContainerLayout
import dev.droidtop.runtime.DesktopLaunchRequests
import dev.droidtop.runtime.SharedVolume
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The Android half of [ContainerLauncher] for one desktop session
 * (docs/SPEC.md 2a, Droidtop/tracker#353): keeps one desktop entry per
 * library game in step with the library's own published list, and turns
 * the launch helper's requests into [DesktopLaunchRequests].
 *
 * Off the main thread throughout. The list is the library's index as the
 * shells already see it ([Library.backgroundScanState]); nothing here
 * touches a game's files. An entry is rewritten only when its text
 * changed since it was last written, which the manifest beside the entries
 * remembers across sessions, so a session start with an unchanged library
 * writes nothing.
 */
internal class DesktopLibraryEntries(private val context: Context, private val library: Library) {
    private val filesDir = context.filesDir
    private val applicationsDir = ContainerLauncher.hostApplicationsDir(filesDir)
    private val requestsDir = ContainerLauncher.hostRequestsDir(filesDir)
    private val manifest = File(ContainerLauncher.hostDir(filesDir), MANIFEST)

    /** Token to library id, for what is published now; read by the request observer's thread. */
    private val published = AtomicReference<Map<String, String>>(emptyMap())

    @Volatile private var observer: FileObserver? = null

    /** Runs in [scope] until it ends; [stop] ends the request watch. */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            prepare()
            watchRequests()
            // Loads the games from the index when nothing has asked for them yet; joins a scan already running.
            library.scanInBackground(LibraryKinds.GAMES)
            library.backgroundScanState(LibraryKinds.GAMES).filterNotNull().collectLatest { entries ->
                // A walk publishes in segments; write once it settles.
                delay(SETTLE_MS)
                runCatching { write(entries) }.onFailure { Log.w(TAG, "Writing the library's desktop entries failed", it) }
            }
        }
    }

    fun stop() {
        observer?.stopWatching()
        observer = null
    }

    private fun prepare() {
        applicationsDir.mkdirs()
        requestsDir.mkdirs()
        val helper = ContainerLauncher.hostHelper(filesDir)
        helper.parentFile?.mkdirs()
        val script = ContainerLauncher.helperScript()
        if (!helper.isFile || helper.readText() != script) helper.writeText(script)
        // Requests from a session that has ended are not this session's to answer.
        requestsDir.listFiles()?.forEach { it.delete() }
    }

    @Suppress("DEPRECATION") // FileObserver(File, Int) needs API 29; the String form works on every level.
    private fun watchRequests() {
        val watched = object : FileObserver(requestsDir.absolutePath, FileObserver.MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                val name = path ?: return
                val token = ContainerLauncher.requestToken(name) ?: return
                File(requestsDir, name).delete()
                val id = published.get()[token]
                when {
                    id == null -> Log.w(TAG, "A launch request named $token, which droidtop has not published; ignored")
                    !DesktopLaunchRequests.offer(id) -> Log.w(TAG, "No Desktop shell is showing to launch $id; request dropped")
                }
            }
        }
        watched.startWatching()
        observer = watched
    }

    private fun write(entries: List<LibraryEntry>) {
        val volumes = SharedVolume.mounted(context)
        val wanted = LinkedHashMap<String, String>()
        val tokens = HashMap<String, String>()
        entries.asSequence().filter { !it.hidden && !it.missing }.forEach { entry ->
            val token = ContainerLauncher.token(entry.id)
            tokens[token] = entry.id
            wanted[ContainerLauncher.entryFileName(token)] = ContainerLauncher.desktopEntry(token, entry.title, iconPath(entry, volumes))
        }
        published.set(tokens)

        val written = readManifest()
        applicationsDir.list()?.forEach { name ->
            if (name.startsWith(ContainerLauncher.ENTRY_PREFIX) && name !in wanted) File(applicationsDir, name).delete()
        }
        var changed = 0
        val next = HashMap<String, Int>(wanted.size)
        wanted.forEach { (name, text) ->
            val hash = text.hashCode()
            next[name] = hash
            if (written[name] == hash) return@forEach
            val tmp = File(applicationsDir, ".$name")
            tmp.writeText(text)
            if (!tmp.renameTo(File(applicationsDir, name))) tmp.delete()
            changed++
        }
        if (changed > 0 || next.size != written.size) writeManifest(next)
        Log.i(TAG, "Library in the desktop: ${wanted.size} games, $changed entries written")
    }

    /**
     * The entry's own art as a path inside the container, when it is a
     * local file on shared storage or in the app's files; null otherwise
     * (a remote URL, a content URI). No file is checked: a launcher shows
     * its default icon for one that is not there.
     */
    private fun iconPath(entry: LibraryEntry, volumes: List<SharedVolume>): String? {
        val uri = entry.iconUri ?: entry.artworkUri ?: return null
        val file = when {
            uri.startsWith("/") -> File(uri)
            uri.startsWith("file:") -> Uri.parse(uri).path?.let { File(it) }
            else -> null
        } ?: return null
        if (file.absolutePath.startsWith(filesDir.absolutePath + "/")) {
            return ContainerLayout.hostStorageToContainerPath(filesDir, file)
        }
        return ContainerLayout.sharedStorageToContainerPath(volumes, file)
    }

    private fun readManifest(): Map<String, Int> =
        runCatching {
            manifest.readLines().mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to (line.substring(tab + 1).toIntOrNull() ?: return@mapNotNull null)
            }.toMap()
        }.getOrDefault(emptyMap())

    private fun writeManifest(entries: Map<String, Int>) {
        val tmp = File(manifest.parentFile, ".$MANIFEST")
        tmp.writeText(entries.entries.joinToString("") { (name, hash) -> "$name\t$hash\n" })
        if (!tmp.renameTo(manifest)) tmp.delete()
    }

    private companion object {
        const val TAG = "droidtop.DesktopLibrary"
        const val MANIFEST = "entries.manifest"
        const val SETTLE_MS = 2_000L
    }
}
