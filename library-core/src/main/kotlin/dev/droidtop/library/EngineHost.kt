package dev.droidtop.library

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File

/**
 * `dev.enginehost` (`bi0shacker001/enginehost`) — a standalone, separately
 * developed programmatic multi-engine VN/RPG-Maker game host, NOT a
 * droidtop-owned project (see `/root/coordination/HANDOFF.md` and
 * `ENGINEHOST_CODEX_BRIEF.md`). droidtop fires its real, documented
 * Intent contract rather than launching a third-party interpreter
 * directly for the engines it covers — see [ENGINEHOST_ENGINE_IDS].
 * (A prior direct-JoiPlay integration was removed entirely: JoiPlay
 * doesn't expose an intent contract that lets an external caller launch
 * a specific game, so that integration never actually worked.)
 *
 * The contract (`enginehost`'s own README, read directly, not guessed):
 * fire `ACTION dev.enginehost.LAUNCH` with a `path` extra (the game
 * folder, absolute) and optionally a `config` extra (a raw
 * `enginehost.json`-shaped JSON string) — the folder's own
 * `enginehost.json`, if present, always wins over `config`. `engine` and
 * `engineVersion` are both required fields in whichever config actually
 * gets used, strictly enforced (`EngineConfigReader.parse` throws if
 * either is missing/blank — confirmed by reading enginehost's own
 * source, not assumed).
 */
object EngineHost {
    const val PACKAGE_NAME = "dev.enginehost"
    private const val ACTION_LAUNCH = "dev.enginehost.LAUNCH"

    /** Needs `<queries><package android:name="dev.enginehost" /></queries>` in the caller's manifest on API 30+ (package visibility). */
    fun isInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /**
     * Fires the real `dev.enginehost.LAUNCH` contract for [gameFolder].
     * [engineId]/[engineVersion] are only actually used (and only
     * required) when [gameFolder] has no `enginehost.json` of its own —
     * enginehost reads that file directly and ignores the `config` extra
     * entirely when it exists, so a caller with a folder that already has
     * one can pass `engineVersion = null` and this still launches fine.
     * [engineVersion] being null for a folder that does NOT have its own
     * `enginehost.json` is a genuine caller error (nothing sensible to
     * fall back to — enginehost itself rejects a missing `engineVersion`
     * outright, see this object's own doc comment), and fails loudly
     * here rather than silently guessing or omitting the field.
     */
    fun launch(context: Context, gameFolder: File, engineId: String, engineVersion: String?) {
        check(isInstalled(context)) { "enginehost ($PACKAGE_NAME) isn't installed" }
        val hasOwnConfig = File(gameFolder, "enginehost.json").isFile
        val intent = Intent(ACTION_LAUNCH).apply {
            putExtra("path", gameFolder.absolutePath)
            if (!hasOwnConfig) {
                checkNotNull(engineVersion) {
                    "No engineVersion known for ${gameFolder.absolutePath} -- either add an " +
                        "enginehost.json to that folder, or set a per-folder override " +
                        "(EngineVersionOverridePrefs.set)."
                }
                val config = JSONObject().apply {
                    put("engine", engineId)
                    put("engineVersion", engineVersion)
                }
                putExtra("config", config.toString())
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/**
 * The 11 VN/interactive-fiction-shaped engines enginehost is meant to
 * host (see `ENGINEHOST_CODEX_BRIEF.md` for why GODOT/UNREAL/UNITY are
 * deliberately excluded — they ship their own runtime per game rather
 * than being hosted by a generic interpreter). Only `kirikiri2` is a
 * real, confirmed id, read directly from `enginehost-plugin-kirikiri`'s
 * own manifest `<meta-data>` (`engineVersion="2.32"`). The others have no
 * plugin yet, so these ids are this project's own convention, not a
 * confirmed contract — matching enginehost's own README example
 * (`"rpgmvxace"`) shape; update these if whoever writes each plugin picks
 * a different id.
 */
val ENGINEHOST_ENGINE_IDS: Map<GameEngine, String> = mapOf(
    GameEngine.KIRIKIRI to "kirikiri2",
    GameEngine.RENPY to "renpy",
    GameEngine.RPG_MAKER_MV to "rpgmv",
    GameEngine.RPG_MAKER_MZ to "rpgmz",
    GameEngine.RPG_MAKER_VX_ACE to "rpgmvxace",
    GameEngine.AUGUST to "august",
    GameEngine.BURIKO to "buriko",
    GameEngine.CATSYSTEM2 to "catsystem2",
    GameEngine.CMVS to "cmvs",
    GameEngine.FLASH_AIR to "flash_air",
    GameEngine.TWINE to "twine",
)

/** The one real, confirmed engineVersion — everything else has no known real version yet (no plugin exists to confirm one against). */
private val ENGINEHOST_DEFAULT_ENGINE_VERSION: Map<GameEngine, String> = mapOf(
    GameEngine.KIRIKIRI to "2.32",
)

/**
 * Per-folder `engineVersion` override for a game enginehost will launch
 * without its own `enginehost.json` — real, user-facing stopgap for the
 * gap `ENGINEHOST_CODEX_BRIEF.md` documents (droidtop's own detection
 * only ever determines *which* engine, never a specific version; real
 * version detection is deferred, not attempted here). Same
 * SharedPreferences-backed shape as
 * [dev.droidtop.library.consoles.SystemOverridePrefs]/
 * [dev.droidtop.library.consoles.PlayerOverridePrefs] — one string per
 * exact folder path, `null` meaning "no override set."
 */
object EngineVersionOverridePrefs {
    private const val PREFS_NAME = "droidtop_engine_version_overrides"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(context: Context, folderPath: String): String? = prefs(context).getString(folderPath, null)

    fun set(context: Context, folderPath: String, engineVersion: String?) {
        prefs(context).edit().apply {
            if (engineVersion.isNullOrBlank()) remove(folderPath) else putString(folderPath, engineVersion)
        }.apply()
    }
}

/**
 * The real `engineVersion` to launch [gameFolder] with: an explicit
 * per-folder override first, then the one real confirmed default for
 * engines with an actual installed plugin ([ENGINEHOST_DEFAULT_ENGINE_VERSION]),
 * else null -- meaning the caller must ask the user to set one (see
 * [EngineHost.launch]'s own doc comment on why this is never guessed at).
 * Skipped entirely when [gameFolder] already has its own
 * `enginehost.json`, since enginehost reads that directly regardless of
 * what droidtop would have resolved here.
 */
fun resolveEngineVersion(context: Context, gameFolder: File, engine: GameEngine): String? {
    if (File(gameFolder, "enginehost.json").isFile) return null
    return EngineVersionOverridePrefs.get(context, gameFolder.absolutePath)
        ?: ENGINEHOST_DEFAULT_ENGINE_VERSION[engine]
}
