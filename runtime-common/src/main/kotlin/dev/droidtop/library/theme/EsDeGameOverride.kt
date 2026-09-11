package dev.droidtop.library.theme

import java.io.File

/**
 * Real `gameOverridePath` (ImageComponent.cpp:723-736 parses it,
 * :147-161 resolves it, GamelistView.cpp:1336-1337 calls it).
 *
 * It is a per-game override of a STATIC theme image, addressed by the
 * game file's own basename under `<gameOverridePath>/<system>/<basename>`
 * with any of five extensions tried in ES-DE's own order. Its whole point
 * is that a theme can ship, say, a different background for one specific
 * game without that game needing anything scraped.
 *
 * Three rules from the source that are easy to get wrong:
 *
 *  * It is parsed ONLY when the element declared no `imageType`
 *    (ImageComponent.cpp:723 `if (mThemeImageTypes.empty() && ...)`) --
 *    "it's by design not possible to override scraped media", as the
 *    source comment there puts it.
 *  * The declared path is normalised to end in a separator before the
 *    system name is appended (:730-731).
 *  * When no override file exists the element falls back to the path it
 *    declared BEFORE the override was applied (:160, `mGameOverrideOriginalPath`,
 *    captured at :733-736) -- and to nothing at all when it declared none.
 */
private val OVERRIDE_EXTENSIONS = listOf(".jpg", ".png", ".webp", ".gif", ".svg")

fun esDeGameOverrideImage(
    gameOverridePath: String?,
    system: String?,
    baseName: String?,
    originalPath: String?,
): String? {
    if (gameOverridePath.isNullOrEmpty() || system.isNullOrEmpty() || baseName.isNullOrEmpty()) {
        return originalPath
    }
    val root = if (gameOverridePath.endsWith("/")) gameOverridePath else "$gameOverridePath/"
    val stem = "$root$system/$baseName"
    for (extension in OVERRIDE_EXTENSIONS) {
        if (File(stem + extension).isFile) return stem + extension
    }
    return originalPath
}
