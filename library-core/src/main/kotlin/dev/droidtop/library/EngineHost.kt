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
 * directly for the engines it covers (the engines database declares the mapping).
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
 * source, not assumed). An optional `title` names the game on
 * enginehost's launch screen; droidtop always sends the library title
 * inline, and because the folder wins at every key, a folder that names
 * itself keeps its own name.
 */
object EngineHost {

    /**
     * Installed by the shell that owns launch UI: presents the real
     * installed [EnginehostRunOption]s for a game droidtop could not
     * classify, and calls back with the user's pick.
     *
     * Same swappable-seam pattern as [LaunchDisplay.chooser] and
     * [PcGameRuntimeRegistry] -- library-core cannot show UI, and the
     * alternative (handing the user to enginehost's own CONFIGURE
     * screen) is the forced hand-off this exists to avoid.
     */
    @Volatile
    var runOptionChooser: ((
        gameFolder: java.io.File,
        options: List<EnginehostRunOption>,
        onChosen: (EnginehostRunOption) -> Unit,
    ) -> Unit)? = null

    const val PACKAGE_NAME = "dev.enginehost"

    /**
     * The contract's config-creator action (Codex response 2026-08-31,
     * accepted+implemented): enginehost scans the supplied directory,
     * lets the user repair or complete detection IN ITS OWN UI, and
     * writes `<path>/enginehost.json` after validation. This is the
     * contract's designed flow for a version droidtop cannot determine
     * -- their own guidance for RPG Maker 2000/2003 and KiriKiri2 is
     * "leave unknown and open CONFIGURE", never fabricate.
     */
    const val ACTION_CONFIGURE = "dev.enginehost.CONFIGURE"

    /** Global enginehost settings (2026-08-31 10:55 response) -- the emulator's own settings screen, surfaced like any player's. */
    const val ACTION_CONFIGURE_SETTINGS = "dev.enginehost.CONFIGURE_SETTINGS"

    /** Narrower saves-only alias of [ACTION_CONFIGURE_SETTINGS]; kept for surfaces that want to deep-link saves directly. */
    const val ACTION_CONFIGURE_SAVES = "dev.enginehost.CONFIGURE_SAVES"
    private const val ACTION_LAUNCH = "dev.enginehost.LAUNCH"

    /**
     * A package-scoped implicit intent, exactly as the contract
     * prescribes: the action is exported, so scoping it to
     * [PACKAGE_NAME] is what stops another installed app from claiming
     * it and receiving a game path meant for enginehost.
     */
    private fun launchIntent(): Intent = Intent(ACTION_LAUNCH).setPackage(PACKAGE_NAME)

    /**
     * Whether enginehost can open [gameFolder] *as a filesystem path*,
     * which is the only form its V1 contract accepts.
     *
     * The limit here is the contract's path-only shape, NOT an Android
     * one: droidtop can and does share app-private files with other apps
     * through FileProvider `content://` URIs plus an explicit
     * `grantUriPermission` (exactly how it hands ROMs to emulators, see
     * [dev.droidtop.library.consoles.AmStartCommandToIntentConverter]).
     * What it cannot do is give another UID a raw path into
     * `/data/data/dev.droidtop.app/...`. So a game sitting there is
     * reachable in principle and unreachable in practice until enginehost
     * accepts a URI -- which is what
     * `/root/coordination/enginehost-claude-requests.md` item 1 asks for.
     *
     * Mostly this should not arise, because games are supposed to install
     * to user-accessible storage (internal shared storage or an SD card)
     * rather than app-private storage. It still can: gamenative keeps its
     * Wine containers under `context.getFilesDir()/imagefs/home/xuser-<id>/`
     * (checked in the vendored source), and a game installed into a
     * prefix rather than to a real volume ends up there too. This gate
     * keeps droidtop from offering a launch that would fail, without
     * pretending the situation is unfixable.
     */
    fun canReachGameFolder(context: Context, gameFolder: File): Boolean {
        val path = gameFolder.absolutePath
        val privateRoots = buildList {
            add(context.filesDir.absolutePath)
            add(context.dataDir.absolutePath)
            context.getExternalFilesDirs(null).filterNotNull().forEach { add(it.absolutePath) }
        }
        return privateRoots.none { path == it || path.startsWith("$it/") }
    }

    /**
     * Resolves the real launch Activity rather than merely checking the
     * package exists -- the contract's own recommended check
     * (`resolveActivity(launch, MATCH_DEFAULT_ONLY)`), which additionally
     * proves this installed build actually serves the launch action
     * rather than just sharing its package name.
     *
     * Package visibility (API 30+) is already covered by :app's own
     * `QUERY_ALL_PACKAGES` declaration, held legitimately because
     * droidtop ships a real HOME/LAUNCHER activity -- checked, not
     * assumed, so no additional `<queries>` block is needed here.
     */
    fun isInstalled(context: Context): Boolean =
        context.packageManager.resolveActivity(launchIntent(), PackageManager.MATCH_DEFAULT_ONLY) != null

    /**
     * The config-creator intent for [gameFolder], with what droidtop DID
     * determine prefilled -- the contract says caller facts fill absent
     * fields and never override the folder's own document, so a partial
     * prefill (family without version, say) is exactly what the action
     * is for.
     */
    fun configureIntent(gameFolder: File, target: EnginehostTarget?): Intent =
        Intent(ACTION_CONFIGURE).setPackage(PACKAGE_NAME).apply {
            putExtra("path", gameFolder.absolutePath)
            if (target != null) {
                putExtra("config", target.toConfigJson(engineVersion = null).toString())
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun settingsIntent(): Intent =
        Intent(ACTION_CONFIGURE_SETTINGS).setPackage(PACKAGE_NAME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun savesSettingsIntent(): Intent =
        Intent(ACTION_CONFIGURE_SAVES).setPackage(PACKAGE_NAME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Fires the real `dev.enginehost.LAUNCH` contract for [gameFolder].
     *
     * [target] carries only enginehost's own family/context vocabulary
     * (from the engines database); [engineVersion] is the game's real
     * runtime version, never a label like "MV" or "latest". Both only
     * matter when [gameFolder] has no `enginehost.json` of its own --
     * that file is authoritative at every key, and the contract is
     * explicit that a caller must never try to override it, so droidtop
     * sends no `config` at all in that case rather than sending one that
     * would be silently ignored.
     *
     * `autoinstallPlugin` is set only when droidtop's own detection was
     * confident enough to name BOTH the context and the version, matching
     * the contract's rule that the flag is for callers whose detection
     * can pick code without asking the user. It never bypasses
     * enginehost's signature verification or trust approval; it only
     * skips the release-choice screen.
     */
    fun launch(
        context: Context,
        gameFolder: File,
        target: EnginehostTarget,
        engineVersion: String?,
        title: String? = null,
    ) {
        check(isInstalled(context)) { "enginehost ($PACKAGE_NAME) isn't installed" }
        val hasOwnConfig = File(gameFolder, "enginehost.json").isFile
        val intent = launchIntent().apply {
            // The one inline field that is always worth sending: the folder
            // wins at every key, so this only ever fills a gap.
            title?.takeIf { it.isNotBlank() }?.let { putExtra("config", JSONObject().put("title", it).toString()) }
            // A filesystem path, not a content:// URI -- enginehost holds
            // its own storage permission and the contract is explicit
            // that the caller does not grant its UID by passing a URI.
            putExtra("path", gameFolder.absolutePath)
            if (!hasOwnConfig) {
                // An answer already recorded for this game makes every
                // later launch fully programmatic.
                val remembered = EnginehostManualChoicePrefs.get(context, gameFolder)
                if (remembered != null) {
                    putExtra(
                        "config",
                        target.copy(engineContext = remembered.engineContext)
                            .toConfigJson(remembered.engineVersion)
                            .withTitle(title)
                            .toString(),
                    )
                    if (remembered.engineContext != null) putExtra("autoinstallPlugin", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    LaunchDisplay.start(context, this)
                    return
                }

                val effectiveVersion = engineVersion ?: target.versionSelectorFallback
                if (effectiveVersion == null) {
                    // Ask in DROIDTOP, over what enginehost actually has
                    // installed, rather than handing the user to another
                    // app's UI. The answer is then persisted -- into the
                    // game's own enginehost.json when the folder is
                    // writable, so any caller benefits and it travels with
                    // the game, otherwise into droidtop's preferences.
                    val options = EnginehostCapabilities.runOptionsFor(context, target.engine)
                    val ask = runOptionChooser
                    if (options.isNotEmpty() && ask != null) {
                        ask(gameFolder, options) { chosen ->
                            val choice = EnginehostManualChoicePrefs.Choice(
                                chosen.engineContext,
                                chosen.engineVersion,
                            )
                            val wroteFolder = EnginehostManualChoicePrefs
                                .writeFolderConfig(gameFolder, target.engine, choice)
                            if (!wroteFolder) {
                                EnginehostManualChoicePrefs.set(context, gameFolder, choice)
                            }
                            launch(context, gameFolder, target, chosen.engineVersion, title)
                        }
                        return
                    }
                    // The contract's own flow for an undetectable
                    // version: open enginehost's CONFIGURE screen (with
                    // what droidtop DID determine prefilled) so the user
                    // completes detection in the emulator's own UI and
                    // it writes the folder's authoritative
                    // enginehost.json -- after which every future launch
                    // is a plain LAUNCH. This replaced a hard error:
                    // "add an enginehost.json by hand" was droidtop
                    // refusing to use the mechanism built for exactly
                    // this case.
                    LaunchDisplay.start(context, configureIntent(gameFolder, target))
                    return
                }
                putExtra("config", target.toConfigJson(effectiveVersion).withTitle(title).toString())
                // Confident = we know the exact compatibility line AND the
                // exact version. A family whose context droidtop cannot
                // yet determine (RPG Maker 2000 vs 2003, CMVS ps2 vs ps3)
                // deliberately lands in enginehost's own choice UI rather
                // than auto-installing against a guess.
                if (target.engineContext != null) putExtra("autoinstallPlugin", true)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        LaunchDisplay.start(context, intent)
    }

    private fun JSONObject.withTitle(title: String?): JSONObject = apply {
        title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
    }
}

/**
 * A detected engine expressed in enginehost's own real family/context
 * vocabulary (`enginehost-contract.md`'s own table, transcribed -- not
 * droidtop's internal [GameEngine] names). Two rules from that contract
 * drive the shape: RPG Maker generations are a CONTEXT inside one
 * `rpgmaker` family rather than separate families, and August is not a
 * family at all -- it is `buriko` with the August context.
 *
 * [engineContext] is null only where droidtop genuinely cannot yet tell
 * which line a game belongs to; that nullness is meaningful (see
 * [EngineHost.launch], which refuses to auto-install on it).
 */
data class EnginehostTarget(
    val engine: String,
    val engineContext: String?,
    /**
     * Exact component versions the runtime must provide, participating in
     * enginehost's bundle resolution (an RGSS3 game pinned to Ruby 1.9.2
     * must not resolve a Ruby 3.1 capability).
     */
    val runtimeRequirements: Map<String, String> = emptyMap(),
    /**
     * A version value to launch with when detection finds none --
     * ONLY for engines whose engineVersion is an implementation
     * SELECTOR rather than a metadata claim (per direction: KiriKiri's
     * accepts any value; an unmapped one falls through to the default
     * implementation, and only one exists). Null everywhere the field
     * is real metadata, which keeps the CONFIGURE detour for exactly
     * the engines whose version genuinely matters and genuinely can't
     * be detected.
     */
    val versionSelectorFallback: String? = null,
) {
    /**
     * [engineVersion] nullable for the CONFIGURE prefill case: the
     * contract's config creator accepts partial caller facts (family
     * without version) and fills the rest through its own scan+UI.
     * LAUNCH-path callers still always pass a real version -- the
     * null-version launch path routes to CONFIGURE instead of here.
     */
    fun toConfigJson(engineVersion: String?): JSONObject = JSONObject().apply {
        put("engine", engine)
        engineVersion?.let { put("engineVersion", it) }
        engineContext?.let { put("engineContext", it) }
        if (runtimeRequirements.isNotEmpty()) {
            put("runtimeRequirements", JSONObject(runtimeRequirements.toMap()))
        }
    }
}

/**
 * Every [GameEngine] droidtop can hand to enginehost, mapped onto that
 * project's real engine family/context vocabulary. Transcribed directly
 * from `enginehost-contract.md`'s own table -- these are no longer
 * droidtop-invented ids (the previous map guessed "rpgmv"/"rpgmz"/
 * "rpgmvxace"/"august" as engine FAMILIES, which the contract explicitly
 * rejects: generations are contexts, and August is a buriko context).
 *
 * UNREAL and UNITY stay absent deliberately: they ship their own runtime
 * per game rather than being hosted by a generic interpreter. GODOT is
 * present now because the contract lists `godot` as a real family.
 */
// The engine-to-enginehost mapping now lives in engines-database.json
// (docs/SPEC.md §7e2b v4) -- EnginesDatabase.enginehostTargetFor is the
// lookup. The map that used to sit here was the hardwired duplicate the
// registry direction explicitly retired.


/**
 * Per-folder `engineVersion` override for a game enginehost will launch
 * without its own `enginehost.json` — the user's explicit last word,
 * outranking even [EngineVersionDetector]'s real detection (a repacked
 * game can genuinely carry a stale version file its actual runtime no
 * longer matches, and the user is the one who can tell). Same
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
 * The real `engineVersion` to launch [gameFolder] with, in descending
 * order of authority: an explicit per-folder override, then the version
 * the game actually declares about itself ([EngineVersionDetector] --
 * read out of the game's own files, never guessed), then the one real
 * else null.
 *
 * There is deliberately no hardcoded per-engine fallback version any
 * more. The previous one claimed KiriKiri2 games were version "2.32",
 * read off an old plugin's manifest rather than off any actual game --
 * exactly what the contract names as a thing callers must not do
 * ("invent an engine version, especially KiriKiri2 or RPG Maker JS
 * versions"), and doubly wrong now that real KiriKiri2 versions are
 * four-component FileVersions like 2.31.2009.825.
 *
 * Real bug this closes: with detection absent, this returned null for
 * essentially every real game folder, [GameLaunchStrategyResolver]
 * dropped [GameLaunchStrategy.ENGINEHOST] from the available list on
 * that basis, and a Ren'Py game with a perfectly good native runtime
 * available fell through to [GameLaunchStrategy.WINE_PREFIX] instead --
 * "Ren'Py needs a full Wine implementation" was that gap, not a real
 * property of the engine.
 *
 * Skipped entirely when [gameFolder] already has its own
 * `enginehost.json`, since enginehost reads that directly regardless of
 * what droidtop would have resolved here.
 */
fun resolveEngineVersion(context: Context, gameFolder: File, engine: GameEngine): String? {
    if (File(gameFolder, "enginehost.json").isFile) return null
    return EngineVersionOverridePrefs.get(context, gameFolder.absolutePath)
        ?: EngineVersionDetector.detect(engine, gameFolder)?.version
}
