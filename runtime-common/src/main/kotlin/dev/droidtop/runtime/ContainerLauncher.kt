package dev.droidtop.runtime

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The library inside the desktop (docs/SPEC.md 2a, "The injection channel
 * and the launch helper", Droidtop/tracker#353): the one channel by which
 * droidtop's library reaches the container's own launchers, panels and file
 * managers, and by which they ask droidtop to launch something.
 *
 * Both halves are freedesktop standards, so no image changes and nothing of
 * droidtop runs inside the container beyond one shell script:
 *
 *  - droidtop writes one desktop entry per library game into
 *    `<filesDir>/[DIR_NAME]/share/applications` on the Android side. Every
 *    container already sees the app's files directory at
 *    [ContainerLayout.APP_STORAGE_DIR], and [ContainerLayout.clientEnvironment]
 *    adds [DATA_DIR] to `XDG_DATA_DIRS`, so any stock menu that reads the
 *    Desktop Entry Specification lists the library beside the distro's own
 *    programs.
 *  - Each entry's `Exec` runs [HELPER] with the entry's [token]. The helper
 *    drops an empty request file named after the token into [REQUESTS_DIR]
 *    and exits. droidtop watches that directory, maps the token back to the
 *    library id it published, and answers with the Desktop shell's own
 *    Start menu launch ([DesktopLaunchRequests]).
 *
 * The boundary: the helper can only name a token droidtop itself published;
 * an unknown token, or anything that is not a token, is ignored. Nothing
 * the container writes is run, parsed as a command or passed to Android as
 * an argument, apart from Open on Android's one link or shared-storage
 * file ([parseViewRequest] is that bound).
 */
object ContainerLauncher {
    /** The directory under the app's files directory that holds the channel. */
    const val DIR_NAME = "desktop-launcher"

    /** The channel inside every container. */
    const val IN_CONTAINER_DIR = "${ContainerLayout.APP_STORAGE_DIR}/$DIR_NAME"

    /** The XDG data directory droidtop's entries live under, added to `XDG_DATA_DIRS`. */
    const val DATA_DIR = "$IN_CONTAINER_DIR/share"

    /** The launch helper, a POSIX shell script ([helperScript]). */
    const val HELPER = "$IN_CONTAINER_DIR/bin/droidtop-open"

    /** Where the helper drops requests. */
    const val REQUESTS_DIR = "$IN_CONTAINER_DIR/requests"

    /** Every desktop entry droidtop writes starts with this, so it only ever removes its own. */
    const val ENTRY_PREFIX = "droidtop-"

    private const val REQUEST_SUFFIX = ".request"
    private val TOKEN = Regex("[0-9a-f]{16}")

    /** The host side of [IN_CONTAINER_DIR]. */
    fun hostDir(filesDir: File): File = File(filesDir, DIR_NAME)

    fun hostApplicationsDir(filesDir: File): File = File(hostDir(filesDir), "share/applications")

    fun hostHelper(filesDir: File): File = File(hostDir(filesDir), "bin/droidtop-open")

    fun hostRequestsDir(filesDir: File): File = File(hostDir(filesDir), "requests")

    /**
     * A library id as the container sees it: 16 hex digits of its SHA-256.
     * Ids carry paths and arbitrary characters a desktop entry's `Exec`
     * would have to quote; a token needs none, and gives nothing away about
     * where a game lives.
     */
    fun token(entryId: String): String =
        MessageDigest.getInstance("SHA-256").digest(entryId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    /** The desktop entry's file name (its desktop file id) for [token]. */
    fun entryFileName(token: String): String = "$ENTRY_PREFIX$token.desktop"

    /**
     * One game's desktop entry. [iconPath] is a path inside the container
     * (local art only, never a URL), or null for the launcher's own default.
     */
    fun desktopEntry(token: String, title: String, iconPath: String?): String = buildString {
        appendLine("[Desktop Entry]")
        appendLine("Type=Application")
        appendLine("Version=1.5")
        appendLine("Name=${escapeValue(title)}")
        appendLine("Comment=From the droidtop library")
        appendLine("Exec=sh $HELPER $token")
        iconPath?.let { appendLine("Icon=${escapeValue(it)}") }
        appendLine("Categories=Game;")
        appendLine("Terminal=false")
    }

    /**
     * Desktop Entry Specification, "Possible value types": a string value
     * escapes backslash, newline, tab and carriage return. Leading spaces
     * are escaped too, since a reader trims them.
     */
    internal fun escapeValue(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")
        return if (escaped.startsWith(" ")) "\\s" + escaped.substring(1) else escaped
    }

    /**
     * The helper. It refuses anything but a token, writes the request under
     * a hidden name and renames it into place, so droidtop only ever sees a
     * whole request (one rename, inotify's MOVED_TO).
     */
    fun helperScript(): String = buildString {
        appendLine("#!/bin/sh")
        appendLine("# Asks droidtop to launch one library game (docs/SPEC.md 2a). Installed by droidtop.")
        appendLine("set -eu")
        appendLine("case \"${'$'}{1:-}\" in")
        appendLine("  *[!0-9a-f]* | '') echo 'usage: droidtop-open <entry>' >&2; exit 2 ;;")
        appendLine("esac")
        appendLine("dir=$REQUESTS_DIR")
        appendLine("tmp=\"${'$'}dir/.${'$'}1.${'$'}${'$'}\"")
        appendLine(": > \"${'$'}tmp\"")
        appendLine("mv -f \"${'$'}tmp\" \"${'$'}dir/${'$'}1.${'$'}${'$'}$REQUEST_SUFFIX\"")
    }

    // ---- Open on Android (Droidtop/tracker#388) ----

    /** The second helper: hands one http(s) link or shared-storage file to Android ([viewHelperScript]). */
    const val VIEW_HELPER = "$IN_CONTAINER_DIR/bin/droidtop-view"

    /** Its desktop entry, a name that is not one of the library's ([ENTRY_PREFIX]), so a library rewrite never removes it. */
    const val VIEW_ENTRY = "org.droidtop.OpenOnAndroid.desktop"

    /** What "Open on Android" offers to open, besides http(s) links: everyday documents and media. */
    private val VIEW_TYPES = listOf(
        "x-scheme-handler/http", "x-scheme-handler/https",
        "application/pdf", "application/epub+zip", "text/plain",
        "image/png", "image/jpeg", "image/gif", "image/webp",
        "video/mp4", "video/webm", "video/x-matroska",
        "audio/mpeg", "audio/ogg", "audio/flac",
    )

    private const val VIEW_REQUEST_PREFIX = "view."
    private const val MAX_VIEW_REQUEST = 4000

    fun hostViewHelper(filesDir: File): File = File(hostDir(filesDir), "bin/droidtop-view")

    /** "Open on Android" as an application every toolkit's Open With list offers for [VIEW_TYPES]; hidden from menus. */
    fun viewEntry(): String = buildString {
        appendLine("[Desktop Entry]")
        appendLine("Type=Application")
        appendLine("Version=1.5")
        appendLine("Name=Open on Android")
        appendLine("Comment=Opens it in an Android app, through droidtop")
        appendLine("Exec=sh $VIEW_HELPER %u")
        appendLine("NoDisplay=true")
        appendLine("Terminal=false")
        appendLine("MimeType=${VIEW_TYPES.joinToString(";")};")
    }

    /**
     * The lowest-precedence mimeapps.list (it is last on `XDG_DATA_DIRS`):
     * http(s) links open on Android when the container has no browser of
     * its own set, and Open on Android is offered for every [VIEW_TYPES]
     * file. A browser or a person's own choice in ~/.config/mimeapps.list wins.
     */
    fun mimeApps(): String = buildString {
        appendLine("[Default Applications]")
        appendLine("x-scheme-handler/http=$VIEW_ENTRY")
        appendLine("x-scheme-handler/https=$VIEW_ENTRY")
        appendLine("[Added Associations]")
        VIEW_TYPES.forEach { appendLine("$it=$VIEW_ENTRY;") }
    }

    /** The view helper: one argument, an http(s) URL, a file:// URI or an absolute path; written whole, then renamed in. */
    fun viewHelperScript(): String = buildString {
        appendLine("#!/bin/sh")
        appendLine("# Asks droidtop to open one link or shared-storage file in an Android app (docs/SPEC.md 2a). Installed by droidtop.")
        appendLine("set -eu")
        appendLine("t=\"${'$'}{1:-}\"")
        appendLine("case \"${'$'}t\" in")
        appendLine("  http://* | https://* | file://* | /*) ;;")
        appendLine("  *) echo 'usage: droidtop-view <http(s) link or absolute path>' >&2; exit 2 ;;")
        appendLine("esac")
        appendLine("[ ${'$'}{#t} -le $MAX_VIEW_REQUEST ] || { echo 'droidtop-view: too long' >&2; exit 2; }")
        appendLine("dir=$REQUESTS_DIR")
        appendLine("tmp=\"${'$'}dir/.$VIEW_REQUEST_PREFIX${'$'}${'$'}\"")
        appendLine("printf '%s' \"${'$'}t\" > \"${'$'}tmp\"")
        appendLine("mv -f \"${'$'}tmp\" \"${'$'}dir/$VIEW_REQUEST_PREFIX${'$'}${'$'}$REQUEST_SUFFIX\"")
    }

    /** Whether [fileName] is a whole view request (`view.<pid>.request`). */
    fun isViewRequest(fileName: String): Boolean =
        fileName.startsWith(VIEW_REQUEST_PREFIX) && fileName.endsWith(REQUEST_SUFFIX)

    /** What a view request may name: nothing else crosses to Android. */
    sealed interface ViewTarget {
        data class Link(val url: String) : ViewTarget

        /** [relativePath] under the shared volume named [volume] ([ContainerLayout.SHARED_STORAGE_DIR]). */
        data class SharedFile(val volume: String, val relativePath: String) : ViewTarget
    }

    /**
     * The bound (docs/SPEC.md 2a, decided 2026-10-09): an http or https URL
     * with a host, or a file under [ContainerLayout.SHARED_STORAGE_DIR]
     * named without `.` or `..` segments. Anything else, any control
     * character, or more than [MAX_VIEW_REQUEST] characters is null.
     */
    fun parseViewRequest(text: String): ViewTarget? {
        val value = text.trim()
        if (value.isEmpty() || value.length > MAX_VIEW_REQUEST || value.any { it.isISOControl() }) return null
        if (value.startsWith("http://") || value.startsWith("https://")) {
            val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return null
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty()) return null
            return ViewTarget.Link(value)
        }
        val path = if (value.startsWith("file://")) {
            runCatching { java.net.URI(value) }.getOrNull()?.takeIf { it.scheme == "file" && it.host.isNullOrEmpty() }?.path ?: return null
        } else {
            value
        }
        val prefix = ContainerLayout.SHARED_STORAGE_DIR + "/"
        if (!path.startsWith(prefix)) return null
        val segments = path.removePrefix(prefix).split('/')
        if (segments.size < 2 || segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return ViewTarget.SharedFile(segments.first(), segments.drop(1).joinToString("/"))
    }

    /** The token a request file named [fileName] asks for, or null when it is not a request. */
    fun requestToken(fileName: String): String? {
        if (!fileName.endsWith(REQUEST_SUFFIX) || fileName.startsWith(".")) return null
        return fileName.substringBefore('.').takeIf { TOKEN.matches(it) }
    }
}

/**
 * Library launches the container asked for through [ContainerLauncher.HELPER],
 * by library id. The Desktop shell answers each one exactly as it answers a
 * Start menu tap; with no Desktop shell showing there is nobody to answer and
 * [offer] says so.
 */
object DesktopLaunchRequests {
    private val flow = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val requests: SharedFlow<String> = flow.asSharedFlow()

    /** Hands [entryId] to the showing Desktop shell; false when none is collecting. */
    fun offer(entryId: String): Boolean = flow.subscriptionCount.value > 0 && flow.tryEmit(entryId)
}
