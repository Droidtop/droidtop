package dev.droidtop.library.theme

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.merge.MergeResult
import org.json.JSONObject
import java.io.File

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
 */
object ThemeDownloader {
    private const val THEMES_LIST_URL = "https://gitlab.com/es-de/themes/themes-list.git"
    private const val THEMES_LIST_DIR_NAME = "themes-list"

    // Real per-system metadata overlay for droidtop's own invented engine
    // systems (Ren'Py, RPG Maker variants, KiriKiri) -- no real ES-DE
    // theme has any metadata for these, since they're not consoles. See
    // https://github.com/bi0shacker001/droidtop-theme-patches's own
    // README for the real overlay format/mechanism.
    private const val THEME_PATCHES_URL = "https://github.com/bi0shacker001/droidtop-theme-patches.git"

    enum class ThemeSyncStatus { CLONED, UPDATED, UP_TO_DATE, DIVERGED, FAILED }
    data class ThemeSyncResult(val status: ThemeSyncStatus, val error: Throwable? = null)

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

    fun syncThemesList(userThemesDir: File): ThemeSyncResult =
        syncRepository(themesListDir(userThemesDir), THEMES_LIST_URL, allowReset = true)

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
    ): ThemeSyncResult {
        val dirName = entry.reponame.ifBlank { entry.name }
        return syncRepository(File(userThemesDir, dirName), entry.url, allowReset)
    }

    private fun syncRepository(dir: File, url: String, allowReset: Boolean): ThemeSyncResult {
        return try {
            if (!File(dir, ".git").isDirectory) {
                dir.parentFile?.mkdirs()
                Git.cloneRepository().setURI(url).setDirectory(dir).call().close()
                ThemeSyncResult(ThemeSyncStatus.CLONED)
            } else {
                Git.open(dir).use { git ->
                    val pullResult = git.pull().setFastForward(MergeCommand.FastForwardMode.FF_ONLY).call()
                    if (pullResult.isSuccessful) {
                        if (pullResult.mergeResult?.mergeStatus == MergeResult.MergeStatus.ALREADY_UP_TO_DATE) {
                            ThemeSyncResult(ThemeSyncStatus.UP_TO_DATE)
                        } else {
                            ThemeSyncResult(ThemeSyncStatus.UPDATED)
                        }
                    } else if (allowReset) {
                        git.fetch().call()
                        val branch = git.repository.branch
                        git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/$branch").call()
                        ThemeSyncResult(ThemeSyncStatus.UPDATED)
                    } else {
                        ThemeSyncResult(ThemeSyncStatus.DIVERGED)
                    }
                }
            }
        } catch (t: GitAPIException) {
            ThemeSyncResult(ThemeSyncStatus.FAILED, t)
        } catch (t: Exception) {
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
