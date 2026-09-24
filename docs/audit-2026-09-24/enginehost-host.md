# Enginehost host app: code, architecture and repository audit (2026-09-24)

Scope: `/root/enginehost` (droidtop-dev), modules `app`, `plugin-api`, `scripts/`, docs, CI, releases.
Method: read-only. Nothing was built, pushed or run on a device. Line numbers refer to
`codex/engine-bundles` at `4fc2b6e`. Paths are relative to the repo root; `K/` = `app/src/main/kotlin/dev/enginehost/`.

Severity scale: **Critical** (exploitable or data-destroying now) / **High** (wrong behaviour, policy
violation or real security exposure) / **Medium** (robustness, perf or drift that will bite) / **Low** (hygiene).
Size: S (< half a day), M (1-3 days), L (a week or more).

---

## 0. Top findings (by severity)

| # | Sev | Finding | Size |
|---|-----|---------|------|
| 1 | High | Published build is a **debug** APK (debuggable, un-minified, dev install activities exported to every app) | M |
| 2 | High | `dev.enginehost.LAUNCH` is an unauthenticated confused deputy: any zero-permission app can run game code/scripts (and inject missing `options` keys such as script paths) with Enginehost's all-files + internet grant, and make it write `enginehost.json` into arbitrary folders | M |
| 3 | High | No host-side preflight for the launch: missing folder/execFile goes straight into the engine (the Ren'Py SIGABRT class); unwritable save root or missing all-files grant crashes `LaunchActivity` with an uncaught `IllegalArgumentException` | S |
| 4 | High | Save-folder policy conflict: host names and redirects saves for RPG Maker, KiriKiri, Buriko, CatSystem2, CMVS -- engines that save in their game folder -- contrary to "we only redefine SYSTEM locations" | M |
| 5 | High | ABI rule (arm64-v8a AND x86_64) is not enforced by host or tooling; four **testing**-stream releases are arm64-only today | S |
| 6 | High | Single-root key design with no rotation path; the developer-debug key is a universal skeleton key accepted for every origin and ships in every APK | L |
| 7 | High | Repo hygiene: real mainline is the non-default `codex/engine-bundles`; GitHub default `main` is stale and diverged; `latest` tag points at `main` (743d3d8) while the APK is built from 4fc2b6e; one commit authored as `Codex <codex@openai.com>` | S |
| 8 | Medium | Bundled-activity games: nothing in the host kills `:runtime` when the game Activity finishes; next launch waits 6 s then "launches anyway" into the live process (the exact double-load failure the code documents) | S |
| 9 | Medium | Main-thread heavy work: full-tree scans (Godot spine scan, detection) in `LaunchActivity.onCreate`; full payload re-hash of 100-400 MB bundles in `:runtime` main thread on every launch; ~12 ECDSA verifies per `matches()` call, per cached manifest, in catalog `render()` | M |
| 10 | Medium | Plugin resolution ignores trust: a denied/pending bundle outranks an approved one and forces the trust screen; a stale pending launch is relaunched when any plugin is later approved | S |
| 11 | Medium | `configChanges` omits `keyboard|navigation|uiMode|density|screenLayout|smallestScreenSize`: pad attach / dark-mode toggle recreates the runtime Activity inside the same process and double-loads the engine | S |
| 12 | Medium | Docs drift (README twine repo, missing html/nscripter, empty Status, UI-first decision only on stale `main`, `runtimeTransport` spelling, release schema lacks `channel`, catalog doc says builds are never replaced) | S |

---

## 1. Architecture map

### 1.1 Launch flow

1. `dev.enginehost.LAUNCH` -> `LaunchEntryActivity` (`K/LaunchEntryActivity.kt:18-30`, exported, `Theme.NoDisplay`,
   `noHistory`, own `taskAffinity`). Reads `path`, `config`, `autoinstallPlugin`, calls `GameRunner.run`, finishes.
2. `GameRunner.run` -> `LaunchActivity.start` (`K/LaunchActivity.kt:320-329`). One-game rule is decided here from an
   **in-memory static** `RunningGame.folder` (`K/LaunchActivity.kt:338-347`): same folder -> bring task forward
   (extras dropped), different folder -> `NEW_TASK|CLEAR_TASK`, killing the old game's task.
3. `LaunchActivity.onCreate -> launch()` (`K/LaunchActivity.kt:84-129`) calls `GameRunner.plan` **on the UI thread**:
   `EngineConfigReader.resolve` (`K/EngineConfig.kt:96-108`), detection write-back (`K/DetectedConfig.kt:22-31`),
   `PluginRegistry.resolve`, trust check, save-folder creation, controller-binding export
   (`K/GameRunner.kt:39-116`). Outcomes: `Detour` (config editor / catalog / trust screen), `Failure`, `Runtime`.
4. If a `:runtime` process is alive, poll up to 15 x 400 ms, then launch anyway (`K/LaunchActivity.kt:269-277`).
5. `startActivityForResult` into `:runtime`:
   - **plugin-api transport**: `RuntimeActivity` (`K/RuntimeActivity.kt:45-96`) re-resolves config and plugin,
     re-checks trust, arms `CrashWatch`, `InstalledBundleVerifier.verify`, builds `PluginDexLoader`, calls
     `EnginePlugin.onCreate(EnginePluginSession)`.
   - **android-activity transport**: `BundledActivityProxy` placeholder (`K/BundledActivityProxy.kt:6`) swapped in
     `EnginehostComponentFactory.instantiateActivity` (`K/EnginehostComponentFactory.kt:20-74`): discover by
     bundle ID, trust check, verify, arm CrashWatch, attach resource APKs, build loader, instantiate the plugin's
     Activity class.
6. Result back in `LaunchActivity.onActivityResult` (`K/LaunchActivity.kt:210-233`): restart request -> relaunch
   after the process dies; `EXTRA_ERROR` -> shown; else poll `CrashWatch` (`K/CrashWatch.kt:82-117`) against
   `ApplicationExitInfo` and show crash / finish / "runtime died".
7. In-game: `RuntimeInputInstaller` wraps every `:runtime` Activity's `Window.Callback`
   (`K/RuntimeInputTap.kt:174-193`) for profile correction and the host-menu combo; `HostMenu` dialog
   (`K/HostMenu.kt:122-210`).

**Duplication.** Loader construction, resource attachment, native path selection and verification are written
twice, once in `EnginehostComponentFactory.instantiateActivity` (`:39-67`) and once in
`RuntimeActivity.loadPlugin` (`K/RuntimeActivity.kt:103-121`), with different pre-checks: the plugin-api path
re-resolves config and plugin, and the bundled path trusts the bundle ID in the intent. One `PluginLoader.load(installed)`
should serve both. (Medium, S)

### 1.2 Plugin loading and trust

- Install: `EngineBundleInstaller.install` (`K/EngineBundlePackage.kt:156-218`). It streams xz and tar, requires manifest
  then sig as the first two members, verifies the P-256 signature before the payload, enforces order, size, mode and
  per-file plus aggregate SHA-256, rejects links, stages under `.staging-<uuid>`, pins the origin key
  (`PluginOriginKeyStore.matches`), allows same-ID replacement only by a strictly newer build from the same origin
  (`K/PluginUpdates.kt:24-27`), then renames atomically and deletes the old build. The extraction code is solid.
- Keys: built-ins in `res/raw/default_plugin_keys.json` (11 origins) certified by `official_plugin_root_key.json`.
  Custom origins use TOFU from the repo's own `enginehost-public-key.json` (`K/PluginOriginKeys.kt:27-39,137-140`).
  The developer-debug key is accepted for **any** origin (`K/PluginOriginKeys.kt:41-49`).
- Trust: `PluginTrustStore` binds `bundleId:archiveSha256:signer` in SharedPreferences (`K/PluginTrustStore.kt:52-53`).
  The approval UI is `PluginTrustActivity`.
- Launch-time re-verification: `InstalledBundleVerifier.verify` (`K/EngineBundlePackage.kt:331-357`) re-parses,
  re-verifies the signature and re-hashes **every payload byte**.
- Class loading: `RuntimeClassLoader` is the app loader of every process (`K/EnginehostComponentFactory.kt:18`), and
  plugin loaders attach to it so that layout inflation finds plugin classes (`K/RuntimeClassLoader.kt:24-58`).
- Resources: `PluginResources.attach` adds a `ResourcesLoader` to the activity and application `Resources`, and refuses
  package-id collisions (`K/PluginResources.kt:37-80`).

### 1.3 Catalogs and updates

- `CatalogRefresh.run` (`K/PluginCatalogRefresh.kt:200-262`). It uses the unsigned droidtop-platforms
  `plugins/index.json` when fresh (3 days), else the GitHub API with ETag. Envelopes are hash-checked against the index,
  and manifests are signature- and pin-checked (`K/PluginCatalog.kt:61-95`).
- Cache: `PluginCatalogCache` re-verifies every cached manifest on every load (`K/PluginCatalog.kt:276-330`).
- Update pass: `PluginUpdateCheck.maybeRun/run` from `MainActivity.onResume` (`K/PluginUpdateCheck.kt:71-126`,
  `K/MainActivity.kt:62-94`). It also refreshes the engine registry and `release-info.json`, and can auto-install plugin
  updates.
- App self-update: `AppUpdate` (`K/AppUpdate.kt:34-167`) uses the rolling `latest` release, checks the digest, then
  hands off to PackageInstaller.

Duplicated origin lists: `PluginOriginStore.DEFAULT_ORIGINS` is hard-coded (`K/PluginCatalog.kt:355-367`), and the same
set appears again in `default_plugin_keys.json` and `origin_directory.json`. That makes three lists for one fact.
Derive the defaults from the key file. (Low, S)

### 1.4 Config editor, detection

`ConfigEditorActivity` (828 lines) writes `enginehost.json`, using `EngineDetector` and `EngineRegistry` (registry snapshot
from the `vendor/droidtop-platforms` submodule, refreshed unsigned from `raw.githubusercontent.com/droidtop/droidtop-platforms/main`,
`K/EngineRegistry.kt:400-448`). `DetectedConfig` writes the config itself when detection is complete.

### 1.5 Controller mapping

- `ControllerBindingStore` over a file-locked JSON store that both processes watch (`K/ControllerBindings.kt:524-690`).
- Per-device `ControllerProfileStore` and the host-menu combo (`HostMenuHotkeyStore`) are **SharedPreferences**, which
  are not multi-process safe. That gives two persistence mechanisms for controller settings, and profile edits made from
  the in-game menu (which open `ControllerConfigActivity` in the main process) are not seen by the running `:runtime`
  process. (Low, S)
- Bypass contract: no `CONTROLLER_BINDINGS` extra at all (`K/GameRunner.kt:105-110`). Documented and consistent.

### 1.6 Saves

`SaveLocationStore` (`K/SaveLocationStore.kt:17-149`) handles the shared root, per-engine overrides and legacy migration.
`SaveFolders` (`:163-197`) decides which engines get a host-named per-game folder. See finding S-1.

### 1.7 Crash handling

`CrashWatch` writes a session note, installs an uncaught-exception hook, and classifies the exit by
`ApplicationExitInfo` (`K/CrashWatch.kt`). `LaunchActivity` stays under the game as the landing screen and offers
retry and report (`ProblemReportActivity` / `ProblemReportFormActivity`).

### 1.8 Dead or vestigial code

- `InstalledPlugin.packageName` "compatibility alias" is unused (`K/PluginRegistry.kt:34-35`). Delete it. (Low, S)
- `signerFingerprints: Set<String>` is always a single element, left over from a multi-signer design
  (`K/PluginRegistry.kt:26,36`; `K/PluginCatalog.kt:52`). Collapse it to one fingerprint. (Low, S)
- `PluginTrustState.DENIED` has no effect beyond its label: the resolver still selects a denied bundle (see R-4).
- Two dev-install activities do one job: `BundleInstallActivity` (main source set, `K/BundleInstallActivity.kt`) and
  `DebugBundleInstallActivity` (debug source set, auto-approves). Keep one, in the debug source set only. (Medium, S)
- `SaveLocationStore.legacyRoot()/migrate` handles the pre-release Documents location. Check whether any install still
  needs it before 1.0. (Low, S)

---

## 2. Plugin trust and security model

### S-1 (High) Published APK is a debug build
- Evidence: `.github/workflows/build.yml:50` runs `assembleDebug`; `:77` copies `app-debug.apk` to
  `enginehost-latest.apk`. `app/build.gradle.kts:36-40` configures signing for the `debug` type only, so no release
  type exists. `release-info.json` currently reports `versionName 0.1.5-dev-148`, commit 4fc2b6e.
- Consequences:
  - `android:debuggable=true`: `adb shell run-as dev.enginehost` can read and rewrite the trust store, origin keys and
    installed bundles, and JDWP can attach.
  - `DebugBundleInstallActivity` is exported with no permission (`app/src/debug/AndroidManifest.xml:4-11`). Any app on
    the device can make Enginehost install **and approve** an official-signed archive from a path it names
    (`app/src/debug/kotlin/.../DebugBundleInstallActivity.kt:16-20`). This overrides a user's DENIED decision, and can
    approve a bundle the user never chose (for example an unstable-stream build).
  - `BundleInstallActivity` "only answers in a debuggable build" (`K/BundleInstallActivity.kt:27-33`), so it always answers.
  - There is no R8 or resource shrinking, and ART runs debuggable apps with reduced optimisation, so engine-host Java/Kotlin code is slower.
- Fix: add a `release` build type signed with the CI key, set `minifyEnabled` with keep rules for `dev.enginehost.api.*`,
  the component factory and the proxies, and publish `assembleRelease`. Keep debug artifacts as CI artifacts only.
  Move `BundleInstallActivity` into the debug source set and delete one of the two installers.
- Size: M.

### S-2 (High) `LAUNCH` is an unauthenticated confused deputy
- Evidence:
  - `LaunchEntryActivity` is exported with no permission (`app/src/main/AndroidManifest.xml:150-161`) and takes any
    `path` (`K/LaunchEntryActivity.kt:21-28`).
  - `EngineConfigReader.resolve` merges caller JSON into missing keys, including nested `options`
    (`K/EngineConfig.kt:96-108,157-170`).
  - `DetectedConfig.write` writes `enginehost.json`, including the caller's inline JSON, into the folder
    (`K/DetectedConfig.kt:22-31,38-56`).
  - The host holds `MANAGE_EXTERNAL_STORAGE` and `INTERNET` (`AndroidManifest.xml:6,10`).
- Impact: a zero-permission app can drop a game folder in Downloads through MediaStore and fire `LAUNCH`. Where the
  matching plugin is already approved, no prompt appears. The game's scripts (Ren'Py Python, RGSS Ruby, MV/MZ JS) then
  run with all-files access and network. Inline `options` can add keys the folder omits; the docs' own examples are
  mkxp-z `customScript` and `rtpPaths` (`docs/engine-bundle-format.md:129-131`). The same path makes Enginehost write a
  config file into any folder the caller can name.
- Fix, in order of preference:
  - Guard `LAUNCH` with a `knownSigner` or signature permission for trusted frontends (droidtop), and show a one-time
    "Allow <caller> to start games?" consent for unknown callers, keyed by `getCallingPackage`/referrer.
  - Never persist caller inline JSON into the folder.
  - Refuse caller-supplied `options` keys that a bundle declares as `path`/`file` type unless the folder config has them.
- Size: M.

### S-3 (High) Key hierarchy: single seed, no rotation, universal developer key
- Evidence:
  - `scripts/derive-official-key.py:29-49` derives the root, every repository key, the APK signing key and the
    developer-debug key from one 32-byte seed.
  - `PluginOriginKeyStore.importCustom` refuses any key change ("a verified rotation is required",
    `K/PluginOriginKeys.kt:33-36`), but no rotation mechanism exists anywhere.
  - Built-in keys cannot be replaced (`:32`), and the root key is a raw resource in the APK.
  - `matches()` accepts the developer-debug key for every origin (`:41-49`). With the same bundle ID, the same origin
    string and a higher version, a dev-key build **replaces** an official build in place (`K/PluginUpdates.kt:24-27`).
    It is labelled "Ultimate" but otherwise has official reach.
  - `scripts/provision-official-keys.sh:5-10` silently mints a **new** master seed under `_work/signing-keys` when none
    is present. Run on a fresh checkout, it produces a root that does not match the pinned
    `official_plugin_root_key.json`. The script also still provisions a `twine` origin that does not exist (`:46-49`).
- Fix:
  - Define a rotation record: a new repository key signed by the old key or the root, carried in `enginehost-public-key.json`,
    and accepted by `importCustom` and `builtIns`.
  - Support more than one root in `official_plugin_root_key.json`.
  - Scope the developer key to a debug-only raw resource (with S-1 this keeps it out of release builds), or make it
    origin-scoped.
  - Make the provision script fail when the seed is missing instead of generating one, and delete the twine block.
  - Document all of this in `docs/plugin-catalog.md`.
- Size: L.

### S-4 (Medium) An approved plugin owns the host
The plugins run in the same UID and share `:runtime`, so an approved plugin can write `shared_prefs/plugin-trust-v1.xml`
(approving anything), `plugin-origin-keys-v1` (adding custom keys), other bundles' directories (it is the owner, so it can
chmod the 0444 files), every game's saves, and the catalog cache. Launch-time re-hashing catches payload edits but not
preference edits. `README.md:132-138` already says `:runtime` "is not a security sandbox", which is honest. Make the
consequence explicit in `docs/plugin-catalog.md` ("approval == full host trust"). Optionally HMAC the trust and origin-key
stores with an Android Keystore key and check them in the main process. That raises the bar only slightly, since
same-UID code can use the key too. (Medium, S docs / M code)

### S-5 (Medium) Unsigned metadata controls what is offered
`plugins/index.json` (droidtop-platforms `main`, raw.githubusercontent.com) and the release envelope's `channel` field are
unsigned (`K/PluginCatalogRefresh.kt:125-184`, `K/PluginCatalog.kt:71`). They cannot inject code, because manifests are
pinned. They can hide newer releases (freeze attack), relabel streams, and set the index's own `generatedAt`. Separately,
a host-side feature of Enginehost depends on a droidtop repository at runtime and at build time (submodule,
`app/build.gradle.kts:64-98`). That is acceptable if deliberate (`app/build.gradle.kts:54-63` cites droidtop SPEC 7e2b),
but it couples two apps the owner wants separate. Fix: carry `channel` inside the signed manifest (or sign the envelope),
and sign the index. (Medium, M)

### S-6 (Low) Report WebView host check
- `ProblemReportFormActivity` enables JavaScript and invites GitHub sign-in inside an in-app WebView.
- The allow-list uses `host.endsWith("github.com")` (`K/ProblemReportFormActivity.kt:45`), so `evilgithub.com` passes.
- Credential entry in an embedded WebView is an anti-pattern.
- Fix: match `== "github.com" || endsWith(".github.com")`, and open sign-in in the system browser or a Custom Tab.
- Size: S.

### S-7 (Low) Other exported surfaces
- `CapabilityProvider` exposes the installed-plugin inventory to any app (`AndroidManifest.xml:77-81`). This is intended,
  but should be documented as public.
- `ConfigEditorActivity` (`CONFIGURE`), `GameScanActivity` (`SCAN`) and `EnginehostSettingsActivity` are exported. They are
  UI-only and need user action, so acceptable.
- The `autoinstallPlugin` extra lets any caller trigger a download and install; approval still gates execution
  (`K/PluginCatalogActivity.kt:253-266`). Acceptable, but undocumented in the README contract.

### S-8 (Low) File modes
`applySignedFileMode` sets world-readable, world-writable and world-executable bits as signed
(`K/EngineBundlePackage.kt:432-436`, `ownerOnly=false`). The app data directory is 0700, so there is no exposure today.
Pass `ownerOnly=true` anyway.

---

## 3. Robustness

### R-1 (High) No launch preflight
- Evidence:
  - `EngineConfigReader.resolve` never checks that `gameFolder` exists. When the folder is missing and inline config is
    passed, it proceeds (`K/EngineConfig.kt:97-106`).
  - `execFile` existence is never checked anywhere.
  - `MainActivity` checks `isDirectory` (`K/MainActivity.kt:256`), but `LAUNCH` does not.
  - The bad-path Ren'Py SIGABRT (JNI pending exception) is therefore reported only after the fact, by `CrashWatch`, as
    "Native crash (SIGABRT)".
  - `SaveLocationStore.saveFolderFor` uses `require(...)` (`K/SaveLocationStore.kt:41-46,74-77`) and is called inside
    `GameRunner.plan` (`K/GameRunner.kt:95`) with no catch. An unwritable root, a removed SD card, or a missing all-files
    grant on a fresh install launched from droidtop throws an uncaught exception in `LaunchActivity.onCreate`, which
    crashes the main process.
  - No native-path-access check on the `LAUNCH` path. Only UI entry points call `StorageFolder.hasNativePathAccess`
    (`K/MainActivity.kt:124`, `K/ConfigEditorActivity.kt:740`).
- Fix: a `LaunchPreflight` step in `GameRunner.plan` that checks, in order:
  1. The all-files grant; if missing, detour to the grant screen.
  2. The folder exists and is readable.
  3. `execFile` exists.
  4. The save folder is creatable; catch the failure and turn it into a `Plan.Failure`.
  Each failure becomes a `Plan.Failure` or `Detour` with a sentence.
- Size: S.

### R-2 (Medium) Bundled-activity runtime process is never ended by the host
- Evidence:
  - Only `RuntimeActivity.onDestroy` kills `:runtime` (`K/RuntimeActivity.kt:147-150`). `RuntimeInputInstaller.onActivityDestroyed`
    is a no-op (`K/RuntimeInputTap.kt:192`), and `HostMenu.quit` only calls `finish()` (`K/HostMenu.kt:163-166`).
  - A bundled plugin that does not `System.exit` itself leaves `:runtime` alive. `LaunchActivity.classifyExit` then polls
    `CrashWatch.pending` for up to 6 s before finishing (`K/LaunchActivity.kt:241-257`).
  - The next launch waits 6 s and then "launches anyway" into the live process (`K/LaunchActivity.kt:269-277`), which is
    the linker double-load the comment at `:100-106` warns about.
  - The same timeout path exists for restarts.
- Fix:
  - In `RuntimeInputInstaller.onActivityDestroyed`, when the Activity is finishing, is the bundle entry Activity and is
    not changing configurations: disarm CrashWatch, then `killProcess(myPid())`.
  - In `launchWhenRuntimeGone`, kill the stale `:runtime` process at timeout (as `cancel()` already does, `:191-199`)
    instead of launching into it.
  - `CrashWatch` is also never disarmed for a normal bundled exit.
- Size: S.

### R-3 (Medium) Activity recreation inside a live engine process
- Evidence: `RuntimeActivity` and `BundledActivityProxy` declare `configChanges="orientation|screenSize|keyboardHidden"`
  only (`AndroidManifest.xml:126,134`).
- Impact: attaching a pad or keyboard (`keyboard|navigation`), a dark-mode or `uiMode` change, a density change or an
  external display recreates the Activity. The plugin-api path then calls `loadPlugin` again in the same process
  (`K/RuntimeActivity.kt:45-96`), and the bundled path re-instantiates the entry class.
- Fix: declare the full set (`keyboard|keyboardHidden|navigation|orientation|screenSize|smallestScreenSize|screenLayout|uiMode|density|fontScale|layoutDirection|colorMode`)
  as emulator hosts do, and treat a non-finishing `onDestroy` as fatal with a message.
- Size: S.

### R-4 (Medium) Resolution ignores trust; stale pending launches
- Evidence:
  - `PluginResolver.resolve` ranks by version and specificity only (`K/PluginRegistry.kt:51-70`). An unapproved or
    DENIED community bundle with a higher `pluginVersion`, or a narrower span, beats an approved one.
    `GameRunner.plan` then detours to the trust screen (`K/GameRunner.kt:80-86`) even though an approved plugin could run the game.
  - `PendingPluginLaunchStore` is one global slot with no expiry (`K/PendingPluginLaunch.kt:8-38`), and
    `consumeFor` returns a pending entry whose `bundleId` is null for **any** bundle (`:26-31`).
  - A launch that detoured to the catalog and was abandoned (`K/GameRunner.kt:71`) is therefore relaunched days later
    when the user approves any plugin (`K/PluginTrustActivity.kt:101-105`).
- Fix:
  - Filter DENIED bundles out of resolution, and prefer APPROVED among equals, or resolve among approved first.
  - Give the pending slot a timestamp and TTL, and clear it when the catalog or trust screen finishes without a result.
- Size: S.

### R-5 (Medium) Main-process death loses `RunningGame`
`RunningGame.folder` is a process-static (`K/LaunchActivity.kt:338-347`) and is not restored from saved state
(`:63-70`). After the main process is killed while `:runtime` lives, re-launching the same game from droidtop takes
the `CLEAR_TASK` path and ends the running game instead of bringing it back. This contradicts `README.md:29-31`.
Fix: persist the running folder and `:runtime` pid in the CrashWatch note, and consult it (plus the liveness of that
pid) in `LaunchActivity.start`. (Medium, S)

### R-6 (Medium) Bundled-path verification failures surface as "runtime died"
`EnginehostComponentFactory.instantiateActivity` throws from `require(...)`/`verify` before `CrashWatch.arm`
(`K/EnginehostComponentFactory.kt:31-38`). The process dies with no `EXTRA_ERROR`, and the user sees the generic
`launch_runtime_died` message. Fix: verify in the main process during planning (off the UI thread), or catch in the
factory and return a tiny error Activity that sets `EXTRA_ERROR`. (Medium, S)

### R-7 (Medium) Auto-installed updates delete a possibly running bundle and silently demote a working game to a trust prompt
- The update pass runs in the main process from `MainActivity.onResume` (`K/PluginUpdateCheck.kt:113-124`).
  `EngineBundleInstaller.install` deletes the previous build directory (`K/EngineBundlePackage.kt:209-212`) even if
  `:runtime` is using it (lazily opened assets, resource APK, unloaded `.so` files).
- The replacement is unapproved by design, so the next launch of a game that worked goes to the trust screen.
- Fix:
  - Skip or defer installs while `:runtime` is alive.
  - Keep the previous approved build until the new one is approved (mark it "update pending approval"), or surface an
    explicit "approve update" notice on the home screen.
- Size: M.

### R-8 (Low) `RuntimeActivity` checks `apiVersion` after running plugin code
`check(verifiedManifest.apiVersion == API_VERSION)` comes after `instance.onCreate(...)` (`K/RuntimeActivity.kt:74-83`).
The resolver already filters by apiVersion, but move the check before class loading. (Low, S)

### R-9 (Low) i18n and hard-coded strings
The patch dialog and failure sentences in `RuntimeActivity`, and the error text in `EnginehostComponentFactory`, are
hard-coded English (`K/RuntimeActivity.kt:172-191,50-66`; 33 literal sentences across the three launch-path files).
Move them to `strings.xml`. (Low, S)

---

## 4. Performance

### P-1 (Medium) Full payload re-hash on every launch, on the `:runtime` main thread
`InstalledBundleVerifier.verify` hashes every file (`K/EngineBundlePackage.kt:350-355`). It is called from
`instantiateActivity` (the main thread of `:runtime`, `K/EnginehostComponentFactory.kt:35`) and from
`RuntimeActivity.onCreate` (`K/RuntimeActivity.kt:71`). Current bundle sizes (compressed): Godot 183 MB, KiriKiri
111 MB, Ren'Py 83 MB, so the unpacked payloads run to several hundred MB. That is seconds of SHA-256 and I/O before the
first frame on every launch, and a real ANR risk. The files sit in private storage the host made read-only, and the
threat this defends against (a same-UID attacker) can defeat it anyway (S-4).
Fix: verify fully at install; at launch, check a signed per-bundle summary, such as file size plus mtime/inode snapshot,
plus hashes of dex/`.so`/APK only. Alternatively, run the full check off-thread in the main process during planning,
while the launch screen is showing. (Medium, M)

### P-2 (Medium) Filesystem scans on the UI thread
- `GameRunner.plan` runs on the UI thread (`K/LaunchActivity.kt:86`).
  - For Godot it calls `FileGameTree.scan` over the whole game tree on every launch (`K/EngineConfig.kt:136-148`,
    `K/EngineRegistry.kt:189-205`, unbounded).
  - For folders without a config it runs full `EngineDetector.detect` (`K/DetectedConfig.kt:23`).
- `RuntimeActivity.onCreate` repeats the resolve, and with it the scan.
- `MainActivity.computeStatus` repeats the scan per Godot row on every `onResume` and on every search keystroke
  (`K/MainActivity.kt:35-39,207-229`). It runs off-thread, but a new thread is spawned per keystroke.
- Given the known FUSE hang on exFAT-illegal names, a UI-thread scan can freeze the launch screen.
- Fix:
  - Run `plan()` on a background executor, with the launch screen showing "Starting".
  - Cache the detected spine requirement by writing it into `enginehost.json` at detection time (as the doc's
    "detection writes the requirement" section already decides) instead of re-scanning on every resolve.
  - Debounce the library search.
- Size: M.

### P-3 (Medium) Repeated ECDSA work
- `PluginOriginKeyStore.builtIns()` re-reads and re-parses `default_plugin_keys.json` and verifies 11 root certificates
  on **every** `get`/`matches`/`isBuiltIn` call. `developerDebug()` does the same for its key
  (`K/PluginOriginKeys.kt:41-78`).
- `PluginCatalogCache.load` calls `matches` and a signature verify per cached manifest (`K/PluginCatalog.kt:316-321`).
- `PluginCatalogActivity.renderReleases` runs `cache.loadAll` on the UI thread on every `render()`
  (`K/PluginCatalogActivity.kt:195`). With 12 origins and multiple releases, that is hundreds of ECDSA verifications per
  render.
- `PluginTrustActivity` calls `isOfficial`/`isDeveloperDebug` per card.
- Fix: memoise verified built-ins and the developer key per process (they are immutable resources). Cache the parsed
  catalog in memory after its first verification. Load off the UI thread.
- Size: S.

### P-4 (Low) Install pipeline
- The archive is hashed up to three times (cache check, digest check, and `sha256(archive)` inside install;
  `K/PluginInstaller.kt:88,137`, `K/EngineBundlePackage.kt:162`).
- XZ decoding is single-threaded.
- The UI shows only "Installing" with no progress during a multi-second extraction.
- Fix: pass the known digest in, and report per-file progress from `extractVerified`.
- Size: S.

### P-5 (Low) Startup
Application start is light: the staging sweep and legacy Ren'Py cleanup, main process only
(`K/EnginehostApplication.kt:18-26`). No issue.

---

## 5. Docs vs code drift

| # | Doc says | Code/reality | Fix |
|---|----------|--------------|-----|
| D-1 | README lists `enginehost-twine-plugin` (`README.md:226`) | No such repo. Twine ships as `dev.enginehost.twine.2_12.v1` from `enginehost-html-plugin`. The html origin is a default origin but missing from the README list | Replace with html (serves html, twine, flash_air and rpgmaker MV/MZ capabilities per `docs/engine-bundle-format.md:25-30`) |
| D-2 | README plugin list | `enginehost-nscripter-plugin` exists and publishes (`dev.enginehost.nscripter.onsyuri.v1`, unstable), but is in neither `DEFAULT_ORIGINS` nor `default_plugin_keys.json`. It can only be added as a TOFU "community" origin | Certify its key, add it to defaults and the README |
| D-3 | README: "not a self-contained catalog or game-library application" (`:10-11`), "deliberately minimal ... for plugin testing" (`:119-121`) | Code has a full library with search, bulk scan, settings and a catalog. The reversal ("The UI is first class", 43ae417, 2026-09-01) exists **only on stale `main`** and was never merged | Cherry-pick 43ae417's README change onto mainline |
| D-4 | README `## Status` (`:229`) | Empty heading | Delete it, or fill it from code |
| D-5 | engine-bundle-format.md:342 `runtimeTransport: activity` | Code constant is `android-activity` (`K/PluginRegistry.kt:174`) | Fix the spelling |
| D-6 | `docs/plugin-release.schema.json` sets `additionalProperties:false` with no `channel` | Every live envelope carries `channel`, and code reads it (`K/PluginCatalog.kt:71`), so the envelopes fail their own schema | Add `channel` (enum stable/testing/unstable) |
| D-7 | plugin-catalog.md:121 "at most once a day (on app open), by listing the published releases" | Frequency enum OFF/DAILY/WEEKLY/MONTHLY (`K/PluginUpdateCheck.kt:17-22`), index-first refresh, plus engine registry and app-release probes | Rewrite the paragraph |
| D-8 | plugin-catalog.md:155 "Installing a newer wrapper build does not silently replace another bundle" | A newer build of the same bundle ID replaces in place and deletes the old one (`K/EngineBundlePackage.kt:179-212`), and auto-install does it unattended | Rewrite to match (and see R-7) |
| D-9 | plugin-catalog.md:164 "Approved = bundle ID and signing-key identity" | The binding also includes the archive digest (`K/PluginTrustStore.kt:52-53`) | Add the digest |
| D-10 | engine-bundle-format.md:79-89 ABI rule | Not enforced by host or scripts; see Q-1 | Enforce it |
| D-11 | Manifest comment: `LAUNCH` "takes one extra (path)" (`AndroidManifest.xml:137-148`); README contract lists path and config | The code also takes `autoinstallPlugin` (`K/LaunchActivity.kt:302`) | Document it in the README contract |
| D-12 | `K/BundleInstallActivity.kt:20` "Only a debuggable build answers" | The shipped build is debuggable, so it always answers | See S-1 |
| D-13 | engine-bundle-format.md `hostType` "decided 2026-09-11" | Commit 5fc3f4f's subject reads as if v1 manifests "gain hostType", but it is doc-only; no code reads `hostType` | Mark it clearly as not implemented, or implement it |
| D-14 | `scripts/provision-official-keys.sh` | Provisions twine and generates seeds in `_work` (S-3) | Fix or delete the script |

Decisions not recorded where the code lives:
- The save policy ("only system locations are redefined") appears nowhere in enginehost docs, and the code does the opposite (Q-2).
- The one-game rule's timeout behaviour, and "approval == full trust", are not written down.

---

## 6. Tests and CI

### T-1 (Medium) Coverage gaps in the security-critical code
There are 138 JVM unit tests across 22 files (`app/src/test/kotlin/dev/enginehost/`), and **no instrumentation tests**.
Covered: capability resolution, config parsing, detection, controller logic, catalog index and streams, PE icons, and the
resource-table package id.

**Not covered:**
- `EngineBundleInstaller.extractVerified`. `EngineBundleInstallerTest` tests only the staging sweep (2 tests). Tar
  ordering, link rejection, path traversal, size, mode or digest mismatch, and unsigned or duplicate entries are all
  untested.
- `InstalledBundleVerifier`.
- `PluginOriginKeyStore`: TOFU, the refusal to change keys, official-issuer verification, and developer-key acceptance.
- `PluginTrustStore`.
- `GameRunner.plan` detours.
- `LaunchActivity` one-game and restart logic.
- `CrashWatch` classification.
- `SaveLocationStore.migrate`.
- The Python tooling (`build-engine-bundle.py`, `verify-engine-bundle.py`, `promote-plugin-release.py`).

Fix: build a small test-bundle factory in the tests (signing with a throwaway P-256 key), then add negative tests for
every `require` in `extractVerified`, key-store tests, and one Robolectric test for `plan()`. A cross-check between
`verify-engine-bundle.py` and the Kotlin verifier on the same fixtures would pin down the format. (Medium, M)

### T-2 CI health
- Only `Android build` runs on the mainline. It takes about 2.5-3 min per run.
- In the last 60 runs, 8 failed (13%). All failures were real: a Kotlin syntax error in `ea38bac`, a
  `LayoutVariantsTest` failure in `4e95444`, and others. Every failure was fixed by the next push.
- The last 23 mainline runs are green; the latest is `35380927475` at 4fc2b6e.
- Secrets (names only): `ENGINEHOST_ANDROID_KEYSTORE_BASE64`, `ENGINEHOST_ANDROID_KEYSTORE_PASSWORD`. The same secret is
  reused as both the store password and the key password (`.github/workflows/build.yml:43-45`). (Low)

### T-3 (High) Release channel mechanics
- `latest` is recreated by `gh release delete latest --cleanup-tag` and then `gh release create latest` with **no
  `--target`** (`.github/workflows/build.yml:94-97`), so the tag lands on the default branch head.
- Verified: `refs/tags/latest` = `743d3d8` (stale `main`), while `release-info.json.commit` = `4fc2b6e` and the APK was
  built from it. The release's source archives and tag are therefore for the wrong code.
- Between the delete and the create, `release-info.json` returns 404, so update checks racing it fail.
- The mainline workflow has **no `concurrency` group**. The fix for that exists only on `main` (8f1a891), so two quick
  pushes race to delete and create `latest`, and the older build can win.
- Fix:
  - Pass `--target ${{ github.sha }}`.
  - Upload with `gh release upload --clobber` to a persistent release (edit the notes) instead of delete and create.
  - Port the concurrency block to the mainline workflow.
  - Or, better, make `codex/engine-bundles` the default branch and retire `main` (see G-1).
- Size: S.

### T-4 (High) Debug build shipped
See S-1. In addition, `versionName` is `0.1.5-dev-N` and there is no release variant. `targetSdk` is 34 against
`compileSdk` 36 (`app/build.gradle.kts:8,19`); raise it with the release variant. (M)

---

## 7. Git and repository hygiene

### G-1 (High) Branches
- `codex/engine-bundles` is the real mainline: 161 commits ahead of the merge base `0921c1c` (2026-08-26). It is what CI
  publishes.
- `main` is GitHub's **default branch** and is stale and diverged, with 6 unique commits:
  - `43ae417`: the "UI is first class" README decision.
  - Five commits `8f1a891 6d97281 ac809b4 b6e308b 743d3d8`, all titled "Cancel superseded CI runs instead of racing them".
    They add concurrency to `build.yml` and to `plugin-{godot,renpy,rpgmaker,web}.yml`. The plugin workflows still living
    in the host repo on `main` contradict `README.md:204-206`.
- Local branch `codex/catalog-streams` is fully merged (0 unique commits) and can be deleted.
- Fix:
  - Merge `43ae417` and the `build.yml` concurrency change into the mainline. Drop the plugin workflows.
  - Make the mainline the default branch, ideally renamed to `main` after moving the old `main` to `archive/main-2026-09`.
  - Delete `codex/catalog-streams`.
  - Branch names prefixed `codex/` carry a tool name; rename them.
- Size: S.

### G-2 (High) AI-attributed commit (forbidden by owner)
- `fd8c1b935e07ed36f0248ab8d197208f199b1ddd`, author `Codex <codex@openai.com>`, "Remove the remaining unrelated tool
  reference", on `codex/engine-bundles` (mainline) and `codex/catalog-streams`.
- No `Co-Authored-By`, "Generated with" or similar trailers were found in any of the 182 commits across all refs.
- Fix: rewrite that single commit's author (a history rewrite on a published branch, so the owner decides), or at minimum
  add a `.mailmap` entry mapping it to the owner.
- Size: S.

### G-3 (Low) History quality
Messages are descriptive, sentence-style and single-purpose. The worst case is the five identical subjects on `main`.
There are no WIP or fixup commits. The largest tracked files are all legitimate: `gamecontrollerdb-android.txt` (76 KB),
`gradle-wrapper.jar`, the Gantari font, and `ControllerBindings.kt` (41 KB). No blobs over 500 KB exist in history, and
nothing is tracked that should not be. `.gitignore` covers `_work/` (where the key script writes seeds).

### G-4 (Low) Stale sibling repositories
`enginehost-plugin-renpy`, `enginehost-plugin-buriko-legacy`, `enginehost-flash-air-wrapper-legacy` and
`enginehost-kirikiri-wrapper-legacy` are unarchived. Archive them so nobody adds them as custom origins.

---

## 8. Quality and policy findings

### Q-1 (High) ABI rule unenforced; testing stream violates it now
- Evidence: `EngineBundleManifestReader.parse` (`K/EngineBundlePackage.kt:63-140`) and `scripts/build-engine-bundle.py`
  and `verify-engine-bundle.py` perform no ABI checks.
- Live envelopes, decoded 2026-09-24. Every **testing**-stream build below ships `lib/arm64-v8a` only:
  - `dev.enginehost.kirikiri.v1` (testing)
  - `dev.enginehost.catsystem2.cst.v1` (testing)
  - `dev.enginehost.cmvs.ps2-ps3.v1` (testing)
  - `dev.enginehost.rpgmaker.easyrpg.v1` (testing)
- Their unstable builds carry both ABIs, so users on "testing" get arm64-only bundles.
- Fix:
  - Verifier and builder: reject any bundle that has `lib/` without both `arm64-v8a` and `x86_64`, unless the manifest
    `notes` carry an explicit waiver.
  - Host: refuse to install a bundle without a directory for any `Build.SUPPORTED_ABIS` entry, with a sentence instead of
    a later `UnsatisfiedLinkError`.
  - Re-promote fixed builds to testing.
- Size: S.

### Q-2 (High) Save policy conflict in the host contract
- Evidence:
  - `SaveFolders.NAMED_BY_THE_HOST` = html, flash_air, kirikiri2, buriko, catsystem2, cmvs, plus every `rpgmaker`
    context (`K/SaveLocationStore.kt:165-176`).
  - `EngineConfigReader.withDefaultSaveFolder` forces a host-named per-game folder under the host save root for all of
    them (`K/EngineConfig.kt:117-121`).
  - `README.md:73-81` states that these engines' saves go under the engine's save root.
- Conflict: the owner's policy is that Enginehost changes no save logic, only what system locations mean, and engines
  that save in their game folder keep doing so. RGSS (XP/VX/VX Ace), RPG_RT 2000/2003, KiriKiri, CMVS and Buriko save
  beside the game on desktop; MV/MZ in NW.js local mode writes `save/` in the game folder. Only true system locations
  (browser localStorage for html/Twine/Flash, the OS user-data dirs used by Ren'Py and Godot) should be remapped.
- The host passes `EXTRA_SAVE_PATH` for every game, so whether a plugin actually redirects depends on the plugin. Audit
  each plugin against this.
- Fix:
  - Reduce `NAMED_BY_THE_HOST` to engines whose native save location is a system location.
  - Stop defaulting `saveFolder` for game-folder savers.
  - Record the policy in `docs/engine-bundle-format.md` (a "Saves" section) and the README.
  - For affected plugins, stop passing save redirection.
- Size: M, because plugins are involved.

### Q-3 Linux-container assumption
None found. There are no references to proot, Wine, Box64 or containers in host code; the only "Linux" mentions are
Godot export-name parsing. Pass.

---

## 9. UX surface (from code)

| Screen | Entry | What the user can do | Half-built / issues |
|---|---|---|---|
| `MainActivity` | launcher | Library of remembered folders with status chips; search when there are more than 8 games; pick-and-run; create config; scan folder; controller; settings; plugins; long-press menu (launch / edit config / report / remove); update notice | Main-thread `isDirectory` per row; a scan thread per keystroke (P-2). README still calls it a test harness (D-3) |
| `ConfigEditorActivity` | home, `dev.enginehost.CONFIGURE`, launch detour | Pick folder; engine/context/runtime requirements/plugin-build/exec-file pickers; declared-option editors (string/number/boolean/choice/path/file); save; test run; browse all plugins | 828 lines, the largest file, and candidate for splitting |
| `GameScanActivity` | home, `dev.enginehost.SCAN` | Choose root; scan; add all / add one; cancel | none |
| `PluginCatalogActivity` | home, launch detour | Stream picker; refresh; newest build per plugin with older builds on tap; install; install from file; sources panel (add or remove a custom origin); installed plugins | No explicit "update" affordance on cards despite the home notice routing here. The stale auto-refresh fires in `render()` |
| `PluginTrustActivity` | after install, launch detour, catalog | Approve / deny / uninstall; details (origin, signer, badge Official / Community / "Ultimate") | "Deny" has no effect on resolution (R-4). The "Ultimate" label for developer builds is jargon |
| `EnginehostSettingsActivity` | home, `CONFIGURE_SAVES` / `CONFIGURE_SETTINGS` | Save root plus per-engine overrides and migration; browser start folder; update frequency; stream; unmetered only; auto-install plugins; app version and update check/install | Auto-install has consequences the UI does not explain (R-7) |
| `ControllerConfigActivity` | home, host menu | Per-scope action capture, unbind, reset; bypass toggle; host-menu hotkey; open profile | none |
| `ControllerProfileActivity` | controller screen | Per-device capture wizard; clear; apply in bypass | Stored in SharedPreferences, not seen live by `:runtime` (1.5) |
| `LaunchActivity` | every launch | Icon and title; cancel / retry / report | Can sit for 6 s after a clean bundled exit (R-2) |
| `HostMenu` (dialog in `:runtime`) | Select+Start | Resume; controller; save location; legend; quit | Quit does not end a bundled runtime (R-2) |
| `RuntimeActivity` patch dialog | plugin throws `EnginePatchRequiredException` | Pick patch file or zip; relaunch | Hard-coded English; relaunch via `startActivity(intent)` from inside `:runtime` bypasses `LaunchActivity`'s one-game bookkeeping |
| `ProblemReportActivity` / `ProblemReportFormActivity` | launch failure, long-press | Prefilled issue fields; copy; open the GitHub form in a WebView or the browser | S-6 |
| `BundleInstallActivity`, `DebugBundleInstallActivity` | adb | Developer installs | Two mechanisms, both live in the shipped build (S-1) |

Half-built items overall:
- `hostType` and v2 bundles are doc-only.
- Restart is unsupported on the android-activity transport (documented).
- The compiled-in Spine module is documented as a temporary second mechanism.
- DENIED is cosmetic.
- No update UI exists in the catalog.

---

## 10. Suggested order of work

1. S: T-3 (release target and concurrency), G-1 (default branch), D-1..D-14 doc fixes, Q-1 host-side ABI refusal, R-1 preflight, R-2, R-3, R-4.
2. M: S-1 release build (removes debuggable and the exported dev installers), S-2 `LAUNCH` caller gating, P-1, P-2 and P-3 perf, T-1 installer and key-store tests, R-7.
3. L: S-3 key rotation, Q-2 save-policy correction across host and plugins (decide first and record it in docs).
