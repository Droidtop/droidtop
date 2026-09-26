package dev.droidtop.library.theme

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.ProgressMonitor
import org.json.JSONObject
import java.io.File
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
 * `themes.json`) is git-cloned once and fetched/updated on subsequent
 * syncs; each individual theme is its own separate git repo, cloned the
 * same way on demand. Real ES-DE uses libgit2; droidtop uses JGit (pure
 * Java, no native git binding on Android) -- same real repos, same real
 * update semantics, different git library underneath.
 *
 * Real update semantics mirrored via [syncRepository]: fast-forward-only
 * by default (matches real ES-DE's own default -- it refuses to silently
 * discard local changes or a diverged history), reporting
 * [ThemeSyncStatus.DIVERGED] instead of forcing anything; a caller can
 * retry with `allowReset = true` (real ES-DE's own `allowReset` param,
 * exposed via a real user confirmation dialog there -- droidtop has no
 * such UI yet, so this always defaults to `false` for individual themes).
 * The themes-list index itself is always synced with `allowReset = true`
 * (real ES-DE's own stated behavior: "we always hard reset the themes
 * list as it should never contain any local changes").
 *
 * Known, honest gap vs. real ES-DE: no detached-HEAD recovery (real
 * ES-DE's own handling for a repository somehow left in that state) --
 * not mirrored, since JGit's own clone/pull never leaves a repo that way
 * under droidtop's own usage.
 *
 * No shallow clone: the vendored JGit release droidtop ships
 * (org.eclipse.jgit:org.eclipse.jgit 5.13.3, gradle/libs.versions.toml)
 * predates `CloneCommand.setDepth` (added upstream in JGit 6.4; confirmed
 * absent by decompiling this exact jar's `CloneCommand.class` -- it has
 * no such method). A full, real git history of `themes-list.git`
 * (160+ community themes, each with its own checked-in screenshot,
 * accumulated over every edit anyone has ever made to that repo) is
 * genuinely large to transfer on a plain connection -- the multi-minute
 * first fetch (rig, p2-rig-theme-browser-fetch-hang) is real transfer
 * time, not a bug in what droidtop asks for (`setCloneAllBranches(false)`
 * already limits it to the one branch, not a recursive per-theme walk).
 * [ThemeSyncProgress] and cooperative cancellation below make that wait
 * visible and boundable instead of trying to make the transfer itself
 * artificially fast.
 */
object ThemeDownloader {
    private const val THEMES_LIST_URL = "https://gitlab.com/es-de/themes/themes-list.git"
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
     * (this doc's own note on `themes-list.git`'s real history size) is
     * left alone; only a genuine stall (dead connection, server gone
     * quiet) is cut off. Fixes rig p2-rig-theme-browser-fetch-hang (no
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

    fun syncThemesList(
        userThemesDir: File,
        progress: ThemeSyncProgress? = null,
        isCancelled: () -> Boolean = { false },
    ): ThemeSyncResult =
        syncRepository(themesListDir(userThemesDir), THEMES_LIST_URL, allowReset = true, progress, isCancelled)

    /**
     * Same "always hard reset, it's read-only to the app" treatment as
     * [syncThemesList] -- droidtop never commits into its own clone of
     * this repo, so a diverged/local-changes state can only mean a
     * corrupted local clone, not real user work to preserve.
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
