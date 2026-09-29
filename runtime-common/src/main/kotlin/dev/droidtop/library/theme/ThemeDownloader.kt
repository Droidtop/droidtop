package dev.droidtop.library.theme

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.ProgressMonitor
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real ES-DE theme-downloader parity -- mirrors `GuiThemeDownloader`'s own
 * actual mechanism (`es-app/src/guis/GuiThemeDownloader.cpp`, read
 * directly from the local reference clone, not guessed): a master index
 * repo (`https://gitlab.com/es-de/themes/themes-list.git`, containing
 * `themes.json`) and each individual theme as its own separate git repo,
 * downloaded on demand. Real ES-DE uses libgit2; droidtop uses JGit (pure
 * Java, no native git binding on Android) -- same real repos, same real
 * update semantics, different git library underneath. The one deliberate
 * divergence: the INDEX is not git-cloned here; it is fetched as a single
 * raw file over HTTPS, because the vendored JGit cannot shallow-clone and
 * the full history is genuinely large (details two paragraphs down).
 *
 * Real update semantics mirrored via [syncRepository]: fast-forward-only
 * by default (matches real ES-DE's own default -- it refuses to silently
 * discard local changes or a diverged history), reporting
 * [ThemeSyncStatus.DIVERGED] instead of forcing anything; a caller can
 * retry with `allowReset = true` (real ES-DE's own `allowReset` param,
 * exposed via a real user confirmation dialog there -- droidtop has no
 * such UI yet, so this always defaults to `false` for individual themes).
 * The index itself is a downloaded file, replaced whole on every sync --
 * real ES-DE's own stated spirit for it ("we always hard reset the
 * themes list as it should never contain any local changes"), minus the
 * git transfer that spirit does not need.
 *
 * Known, honest gap vs. real ES-DE: no detached-HEAD recovery (real
 * ES-DE's own handling for a repository somehow left in that state) --
 * not mirrored, since JGit's own clone/pull never leaves a repo that way
 * under droidtop's own usage.
 *
 * The index is one raw HTTPS file, not a clone. The vendored JGit
 * release droidtop ships (org.eclipse.jgit:org.eclipse.jgit 5.13.3,
 * gradle/libs.versions.toml) predates `CloneCommand.setDepth` (added
 * upstream in JGit 6.4; confirmed absent by decompiling this exact
 * jar's `CloneCommand.class` -- it has no such method), and no JGit
 * that has it runs on droidtop's minSdk 26: reading the real 6.4.0
 * jar's class files shows its own clone/checkout path
 * (`DirCacheCheckout`) calling `InputStream.transferTo`, and its
 * `StringUtils`/`CommitConfig` calling `String.strip`/
 * `String.stripTrailing` -- all Android API 33 additions (the latest
 * desugar_jdk_libs, 2.1.5, bridges `transferTo` but ships no shim for
 * the String methods, and the build carries no core-library desugaring
 * anyway); JGit 7 only adds more of the same, and is what crashed
 * Android 9 with NoSuchMethodError on "Get more themes" and Settings >
 * Browse themes (rig, dq-onboard-01). A full, real git history of
 * `themes-list.git` (68 theme entries carrying 278 checked-in
 * screenshot images, counted from the real themes.json 2026-09-29;
 * the history is every edit anyone has ever made to that repo)
 * measured about four minutes to clone on the rig -- genuine transfer
 * time, not a bug in what droidtop asked for. So [syncThemesList]
 * downloads just `themes.json` from GitLab's raw-file endpoint of the
 * same repo (153 KB as measured, real byte counts reported into the
 * SAME progress/stall wiring), and the git clone path stays for the
 * per-theme downloads and theme patches, where git semantics
 * (fast-forward, divergence reporting) are actually needed. The
 * screenshot previews that used to fall out of the clone's working
 * tree now stream from the same raw endpoint on demand (see
 * [themesListFileUrl]).
 */
object ThemeDownloader {
    // The index repo -- still the one upstream source of `themes.json`,
    // now read over GitLab's raw-file endpoint instead of a clone (see
    // the object's own doc comment for why not a shallow clone).
    private const val THEMES_LIST_REPO_URL = "https://gitlab.com/es-de/themes/themes-list"
    // The index repo's own default branch, per GitLab's project API
    // (2026-09-29). A raw URL has no HEAD indirection the way a clone
    // does, so the branch is pinned by name; if ES-DE ever renames it,
    // this is the one line to move.
    private const val THEMES_LIST_REF = "master"
    private const val THEMES_LIST_FILE = "themes.json"
    private const val THEMES_LIST_DIR_NAME = "themes-list"

    // Real per-system metadata overlay for droidtop's own invented engine
    // systems (Ren'Py, RPG Maker variants, KiriKiri) -- no real ES-DE
    // theme has any metadata for these, since they're not consoles. See
    // https://github.com/droidtop/droidtop-theme-patches's own
    // README for the real overlay format/mechanism.
    private const val THEME_PATCHES_URL = "https://github.com/droidtop/droidtop-theme-patches.git"

    enum class ThemeSyncStatus { CLONED, UPDATED, UP_TO_DATE, DIVERGED, FAILED, CANCELLED }
    data class ThemeSyncResult(val status: ThemeSyncStatus, val error: Throwable? = null)

    /**
     * Real progress off JGit's own [ProgressMonitor] (rig,
     * p2-rig-theme-browser-fetch-hang: the "Fetching the theme list..."
     * screen never moved, and a newcomer had no way to tell "still
     * working" from "stuck forever"). JGit reports whole transport
     * phases as tasks ("remote: Counting objects", "Receiving objects",
     * "Resolving deltas"), each with its own completed/total -- exactly
     * what a real spinner needs, already computed, no polling of our own.
     * The raw-file index fetch (see the object's own doc comment) reports
     * its real byte counts through this same interface, so every route
     * keeps one progress shape.
     */
    fun interface ThemeSyncProgress {
        fun onUpdate(task: String, completed: Int, total: Int)
    }

    data class ThemeScreenshot(val image: String, val caption: String)

    /** Real schema, field names matching real ES-DE's own `themes.json` exactly (`GuiThemeDownloader::parseThemesList`). */
    data class ThemeDownloadEntry(
        val name: String,
        val reponame: String,
        val url: String,
        val author: String,
        val deprecated: Boolean,
        val variants: List<String>,
        val colorSchemes: List<String>,
        val aspectRatios: List<String>,
        val fontSizes: List<String>,
        val screenshots: List<ThemeScreenshot>,
    )

    fun themesListDir(userThemesDir: File): File = File(userThemesDir, THEMES_LIST_DIR_NAME)

    /**
     * Runs one blocking [sync] call (one of this object's own methods,
     * given as a trailing lambda so every caller shares the SAME
     * cancellation/progress wiring rather than reimplementing it -- the
     * ONE mechanism droidtop's own Browse-themes screen and its
     * onboarding background download both go through) off the main
     * thread, with a stall watchdog: [ThemeSyncProgress] resets a clock
     * on every real update JGit reports, and if [STALL_TIMEOUT_MS] passes
     * with NO progress at all, `isCancelled` flips true -- JGit polls
     * that cooperatively and stops cleanly rather than this needing to
     * interrupt a blocking thread. A slow-but-progressing real transfer
     * (a real theme repo's own history can be just as large as the
     * index's used to be) is left alone; only a genuine stall (dead
     * connection, server gone quiet) is cut off. Fixes rig p2-rig-theme-browser-fetch-hang (no
     * indication of a stall at all) and the download-never-completing
     * half of p1-rig-onboarding-artbooknext-never-downloads (previously
     * this could hang forever with no eventual FAILED status either).
     */
    suspend fun withStallWatchdog(
        onProgress: (task: String, completed: Int, total: Int) -> Unit = { _, _, _ -> },
        sync: (ThemeSyncProgress, () -> Boolean) -> ThemeSyncResult,
    ): ThemeSyncResult = coroutineScope {
        val cancelled = AtomicBoolean(false)
        val lastProgressAt = AtomicLong(android.os.SystemClock.elapsedRealtime())
        val progress = ThemeSyncProgress { task, completed, total ->
            lastProgressAt.set(android.os.SystemClock.elapsedRealtime())
            onProgress(task, completed, total)
        }
        val watchdog = launch(Dispatchers.Default) {
            while (isActive) {
                delay(STALL_CHECK_INTERVAL_MS)
                if (android.os.SystemClock.elapsedRealtime() - lastProgressAt.get() > STALL_TIMEOUT_MS) {
                    cancelled.set(true)
                    break
                }
            }
        }
        val result = withContext(Dispatchers.IO) { sync(progress) { cancelled.get() } }
        watchdog.cancel()
        result
    }

    /** No progress at all for this long reads as stalled, not "still working" -- see [withStallWatchdog]. */
    private const val STALL_TIMEOUT_MS = 30_000L
    private const val STALL_CHECK_INTERVAL_MS = 3_000L

    /**
     * One raw HTTPS file (the object's own doc comment says why not a
     * clone): [THEMES_LIST_FILE] from GitLab's raw endpoint, validated
     * as JSON before it replaces the old list, reported as UP_TO_DATE
     * when the bytes did not change, and always rewritten so its own
     * mtime is what "the index is a week old" reads (Browse themes) --
     * the role the clone's FETCH_HEAD used to play.
     */
    fun syncThemesList(
        userThemesDir: File,
        progress: ThemeSyncProgress? = null,
        isCancelled: () -> Boolean = { false },
    ): ThemeSyncResult =
        fetchThemesList(themesListDir(userThemesDir), progress, isCancelled)

    private fun fetchThemesList(
        dir: File,
        progress: ThemeSyncProgress? = null,
        isCancelled: () -> Boolean = { false },
    ): ThemeSyncResult {
        dir.mkdirs()
        val dest = File(dir, THEMES_LIST_FILE)
        val temp = File(dir, THEMES_LIST_FILE + ".downloading")
        val hadList = dest.isFile
        return try {
            val bytes = downloadThemesListBytes(progress, isCancelled)
            // The platform databases' own validate-before-replace
            // contract: nothing on disk changes until the downloaded
            // bytes parsed as what they claim to be, and the replacement
            // is a rename, so a killed process leaves either the old
            // list or the new one, never half of either.
            JSONObject(String(bytes, Charsets.UTF_8))
            val status = when {
                !hadList -> ThemeSyncStatus.CLONED
                dest.readBytes().contentEquals(bytes) -> ThemeSyncStatus.UP_TO_DATE
                else -> ThemeSyncStatus.UPDATED
            }
            temp.writeBytes(bytes)
            check(temp.renameTo(dest) || run { dest.delete(); temp.renameTo(dest) }) {
                "Couldn't move the downloaded $THEMES_LIST_FILE into place"
            }
            deleteLegacyCloneLeftovers(dir)
            ThemeSyncResult(status)
        } catch (t: Exception) {
            temp.delete()
            ThemeSyncResult(if (isCancelled()) ThemeSyncStatus.CANCELLED else ThemeSyncStatus.FAILED, t)
        }
    }

    /** One transport phase the raw index fetch reports; JGit's clone phases ("Receiving objects", ...) play the same role. */
    private const val RECEIVING_THE_THEME_LIST = "Receiving the theme list"
    private const val CONNECT_TIMEOUT_MS = 15_000

    // Above [STALL_TIMEOUT_MS] on purpose: the stall WATCHDOG decides
    // when a fetch has gone quiet (it flips isCancelled), and this read
    // timeout only exists to unblock the thread so that decision can
    // take effect -- the same cooperative role JGit's own polling plays
    // for the clone path.
    private const val READ_TIMEOUT_MS = 45_000

    // The real themes.json is a few hundred KiB. The fetch must never
    // read unbounded, whatever the server does.
    private const val MAX_THEMES_LIST_BYTES = 8L * 1024 * 1024

    /**
     * One plain [HttpURLConnection] GET, the plugin catalog's own
     * download shape: 64 KiB at a time, real byte counts into [progress]
     * on every read (what keeps [withStallWatchdog] fed), [isCancelled]
     * honoured between reads, and the full length checked against
     * Content-Length so a cut connection is a failure, not a silently
     * short list.
     */
    private fun downloadThemesListBytes(
        progress: ThemeSyncProgress? = null,
        isCancelled: () -> Boolean = { false },
    ): ByteArray {
        val connection = (URL(themesListFileUrl(THEMES_LIST_FILE)).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        return try {
            when (val code = connection.responseCode) {
                200 -> {
                    val total = connection.contentLengthLong
                    if (total > MAX_THEMES_LIST_BYTES) {
                        throw IOException("the theme list is larger than droidtop reads ($total bytes)")
                    }
                    val out = ByteArrayOutputStream(if (total > 0) total.toInt() else 64 * 1024)
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        progress?.onUpdate(RECEIVING_THE_THEME_LIST, 0, total.coerceAtLeast(0L).toInt())
                        while (true) {
                            if (isCancelled()) throw IOException("cancelled")
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > MAX_THEMES_LIST_BYTES) {
                                throw IOException("the theme list grew past $MAX_THEMES_LIST_BYTES bytes mid-download")
                            }
                            out.write(buffer, 0, read)
                            progress?.onUpdate(RECEIVING_THE_THEME_LIST, written.toInt(), total.coerceAtLeast(0L).toInt())
                        }
                        if (total > 0 && written != total) {
                            throw IOException("the download was interrupted ($written of $total bytes)")
                        }
                        out.toByteArray()
                    }
                }
                404, 410 -> throw IOException("the theme list is not there (HTTP $code)")
                else -> throw IOException("the server answered HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * GitLab's raw-file URL for [path] in the index repo, pinned to
     * [THEMES_LIST_REF]. Browse themes' screenshot previews load through
     * this too -- one image per visible row, on demand, through the
     * app's one image loader -- instead of every theme's images
     * arriving with the index the way the clone's working tree made them.
     */
    fun themesListFileUrl(path: String): String =
        THEMES_LIST_REPO_URL + "/-/raw/" + THEMES_LIST_REF + "/" +
            path.split('/').filter { it.isNotBlank() }.joinToString("/") { rawPathSegment(it) }

    /**
     * Percent-encodes what may not appear verbatim in a URI path
     * segment. The real screenshot paths in themes.json are plain
     * ASCII; this is the safety net for anything a theme author names
     * a file (and it collapses the double slashes at least one real
     * entry carries).
     */
    private fun rawPathSegment(segment: String): String =
        URI(null, null, null, -1, "/$segment", null, null).rawPath.removePrefix("/")

    /**
     * One-time migration: an install that cloned the index as a full
     * git repo is sitting on the multi-minute history (`.git`) and
     * every theme's screenshot images (`screenshots/`), both dead now
     * that the list is one raw file and previews load on demand. Runs
     * only after a successful replacement, so a failed fetch never
     * costs the old list.
     */
    internal fun deleteLegacyCloneLeftovers(dir: File) {
        for (dead in listOf(".git", "screenshots")) {
            File(dir, dead).takeIf { it.exists() }?.deleteRecursively()
        }
    }

    /**
     * The same read-only-to-the-app treatment as the themes list itself
     * (always brought back to upstream's own state): droidtop never
     * commits into its own clone of this repo, so a diverged/local-changes
     * state can only mean a corrupted local clone, not real user work to
     * preserve.
     */
    fun syncThemePatches(patchesDir: File): ThemeSyncResult =
        syncRepository(patchesDir, THEME_PATCHES_URL, allowReset = true)

    fun downloadOrUpdateTheme(
        userThemesDir: File,
        entry: ThemeDownloadEntry,
        allowReset: Boolean = false,
        progress: ThemeSyncProgress? = null,
        isCancelled: () -> Boolean = { false },
    ): ThemeSyncResult {
        val dirName = entry.reponame.ifBlank { entry.name }
        return syncRepository(File(userThemesDir, dirName), entry.url, allowReset, progress, isCancelled)
    }

    private fun syncRepository(
        dir: File,
        url: String,
        allowReset: Boolean,
        progress: ThemeSyncProgress? = null,
        isCancelled: () -> Boolean = { false },
    ): ThemeSyncResult {
        // A caller's own timeout (ThemeBrowserScreen, OnboardingThemeDownload)
        // flips this rather than interrupting the thread: JGit polls
        // isCancelled() between transport phases and throws its own
        // CancelledException, which the catches below turn into a real,
        // reportable CANCELLED result instead of a half-written repo
        // nobody knows the state of.
        val monitor = object : ProgressMonitor {
            private var task = ""
            private var total = 0
            private var done = 0
            override fun start(totalTasks: Int) {}
            override fun beginTask(title: String, totalWork: Int) {
                task = title
                total = totalWork
                done = 0
                progress?.onUpdate(task, done, total)
            }
            override fun update(completed: Int) {
                done += completed
                progress?.onUpdate(task, done, total)
            }
            override fun endTask() {}
            override fun isCancelled(): Boolean = isCancelled()
        }
        // A clone that never finishes (network died, or the caller's own
        // timeout cancelled it) leaves a `.git` directory that looks like
        // a real repo to the `isDirectory` check below but is actually
        // incomplete -- without cleaning it up, every future attempt
        // would open THIS half-written repo instead of cloning fresh, and
        // fail forever. Only the fresh-clone path can leave this behind;
        // an existing repo being pulled/reset is never deleted.
        val freshClone = !File(dir, ".git").isDirectory
        return try {
            if (freshClone) {
                dir.parentFile?.mkdirs()
                Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(dir)
                    .setCloneAllBranches(false)
                    .setProgressMonitor(monitor)
                    .call().close()
                ThemeSyncResult(ThemeSyncStatus.CLONED)
            } else {
                Git.open(dir).use { git ->
                    val pullResult = git.pull()
                        .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                        .setProgressMonitor(monitor)
                        .call()
                    if (pullResult.isSuccessful) {
                        if (pullResult.mergeResult?.mergeStatus == MergeResult.MergeStatus.ALREADY_UP_TO_DATE) {
                            ThemeSyncResult(ThemeSyncStatus.UP_TO_DATE)
                        } else {
                            ThemeSyncResult(ThemeSyncStatus.UPDATED)
                        }
                    } else if (allowReset) {
                        git.fetch().setProgressMonitor(monitor).call()
                        val branch = git.repository.branch
                        git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/$branch").call()
                        ThemeSyncResult(ThemeSyncStatus.UPDATED)
                    } else {
                        ThemeSyncResult(ThemeSyncStatus.DIVERGED)
                    }
                }
            }
        } catch (t: GitAPIException) {
            if (freshClone) dir.deleteRecursively()
            ThemeSyncResult(if (isCancelled()) ThemeSyncStatus.CANCELLED else ThemeSyncStatus.FAILED, t)
        } catch (t: Exception) {
            if (freshClone) dir.deleteRecursively()
            ThemeSyncResult(if (isCancelled()) ThemeSyncStatus.CANCELLED else ThemeSyncStatus.FAILED, t)
        } catch (t: LinkageError) {
            // A library method this Android version does not have is a
            // failed download, not a crashed app (rig, dq-onboard-01).
            if (freshClone) dir.deleteRecursively()
            ThemeSyncResult(ThemeSyncStatus.FAILED, t)
        }
    }

    /**
     * Real Android-specific behavior: `GuiThemeDownloader::parseThemesList`
     * reads BOTH the "themes" and "themesAndroid" JSON array keys when
     * compiled for Android (`#if defined(__ANDROID__)`), not just "themes"
     * -- confirmed directly from source, not guessed.
     */
    fun parseThemesList(userThemesDir: File): List<ThemeDownloadEntry> {
        val file = File(themesListDir(userThemesDir), "themes.json")
        if (!file.isFile) return emptyList()
        val doc = JSONObject(file.readText())
        val entries = mutableListOf<ThemeDownloadEntry>()
        for (key in listOf("themes", "themesAndroid")) {
            val arr = doc.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                entries += ThemeDownloadEntry(
                    name = t.optString("name"),
                    reponame = t.optString("reponame"),
                    url = t.optString("url"),
                    author = t.optString("author"),
                    deprecated = t.optBoolean("deprecated", false),
                    variants = t.optStringList("variants"),
                    colorSchemes = t.optStringList("colorSchemes"),
                    aspectRatios = t.optStringList("aspectRatios"),
                    fontSizes = t.optStringList("fontSizes"),
                    screenshots = t.optJSONArray("screenshots")?.let { ss ->
                        (0 until ss.length()).map { idx ->
                            val s = ss.getJSONObject(idx)
                            ThemeScreenshot(s.optString("image"), s.optString("caption"))
                        }
                    } ?: emptyList(),
                )
            }
        }
        return entries
    }

    private fun JSONObject.optStringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }
}
