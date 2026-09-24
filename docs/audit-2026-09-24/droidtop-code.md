# droidtop: code and architecture audit (2026-09-24)

Scope: `/root/droidtop` at `main` (HEAD `0e7ddde5`), read-only. Vendored trees under `vendor/` are judged only by how droidtop uses them. The two forks compiled as droidtop modules, `:shell-default` (Murine Launcher, from AOSP Launcher3) and `:input-keyboard` (Hacker's Keyboard), are judged mainly on droidtop's own additions to them.

Judged against: one mechanism per job; delete dead code; decisions recorded in SPEC; state comes from code; root only for the rooted desktop container stack; no custom container images; streaming belongs to windowcast; droidtop never copies a user's game files; performance on a Retroid Pocket 5.

Severity: **High** = the user feels it, or a standing rule is broken. **Med** = a real defect or real waste with limited reach. **Low** = hygiene. Size: **S** under a day, **M** a few days, **L** a week or more.

---

## 0. Top findings, by severity

| # | Sev | Finding | Size |
|---|---|---|---|
| P1 | High | The themed gamelist runs a synchronous filesystem stat walk for every game in the list, on the main thread, each time the list changes. During a scan that is every 250 ms. | M |
| P2 | High | The slow background rebuild runs a full ROM rescan (including the per-ROM media stat walk) 5 s after every start and then every 30 min. On a first run it walks the whole library a second time while the first walk is still going. | M |
| P3 | High | ROM detection runs up to about 60 media stat calls per ROM, at walk time and again each time cached rows are loaded. That is about a million FUSE stats for an 18k-file folder. | M |
| P4 | High | ES-DE theme discovery and per-system parsing run inside Compose `remember`, on the main thread, once per carousel system. Theme discovery re-runs asset/directory listing on every call, even when the parse is cached. | M |
| C1 | High | The slow pass and a rescan/root change write the index concurrently from diverged in-memory slices. A removed root's games come back. A newly added root's records and index rows get deleted. | M |
| C2 | High | The slow pass publishes into a state flow no screen observes, so its findings only appear after a process restart. SPEC says the opposite. | S |
| F1 | High | Non-root Desktop mode does nothing: `ProotRuntime` is seven `TODO()`s. | L |
| F2 | High | Launcher mode cannot show or launch library games. SPEC says it can. Nothing in `:shell-default` reaches `GameLaunchActivity`. | M |
| R1 | High | Root is used outside the desktop container stack: `su am force-stop` on the ROM launch path, and root-based GameNative migration. | S |
| P5 | Med | `NativeAppProvider` renders and PNG-encodes (quality 100) every app icon on every Apps scan, on Launcher3's shared `MODEL_EXECUTOR`. | S |
| P6 | Med | Opening the index reads one JSON record file per game. So does every index save of a changed part (read, decode, encode, re-read to compare). | M |
| P7 | Med | Each gamepad key press recomposes the whole `GamepadShell` root. The system carousel recomposes every animation frame. | S |
| P8 | Med | The Desktop host-bridge blits frames with a CPU per-pixel swizzle in an unthrottled screencopy loop that has no damage tracking. | M |
| C3 | Med | `GameLaunchActivity` and the Desktop start menu launch through `scanAll()`, a full unindexed walk of every provider. The start-menu launch coroutine is cancelled by its own dismiss, and an exception there crashes the app. | S |
| C4 | Med | Settings actions run `runBlocking` Room writes on the main thread. `DesktopSessionService.onDestroy` runs `runBlocking` with a root container stop on the main thread. | S |
| D1 | Med | Three stores of ROM entries (`RomDatabase.rom_entries`, the index DB, per-game JSON), and four scan entry points per provider. | M |
| S1 | Med | SPEC module map (§9) and several sections are materially wrong: noroot, `shell-desktop`, line counts, dependencies, Hilt. §3 and §5b contradict each other on proot. | S |

---

## 1. Module map

Main-source LoC counts non-vendor Kotlin/Java only. Test LoC is listed separately.

| Module | Main LoC | Test LoC | Purpose (from code) | Depended on by | Verdict |
|---|---|---|---|---|---|
| `:app` | 8,515 | 240 | Activities (Main, Onboarding, Companion, Containers, PC store hosts, GameLaunch), `LibraryCore`, `DesktopSessionService`, `AppSettingsCatalogs`, self-update | nobody (the APK) | OK. `OnboardingActivity` (1,773) and `AppSettingsCatalogs` (1,700) are very large. |
| `:host-bridge` | 407 (+1,240 C++) | 118 | Wayland client over JNI: screencopy to Surface, virtual input, clipboard | app, input-seat (api), shell-desktop | OK. Native blit is slow (P8). |
| `:runtime-common` | 9,061 | 3,737 | Container contracts, **plus the whole ES-DE theme engine (`dev.droidtop.library.theme.*`, about 6k LoC) and the settings catalogs (`dev.droidtop.library.settings.*`)** | everything | Misnamed and misdescribed. SPEC §9 calls it "shared types … no deps". It is the theme engine and settings model, in `dev.droidtop.library.*` packages. |
| `:runtime-windows` | 2,908 | 258 | gamenative (Winlator) Wine launch, PC store library, GameNative migration | app | OK. It also depends on library-core. |
| `:runtime-linux-root` | 818 | 0 | DroidSpaces runtime, crane rootfs puller | app | Untested. Desktop-only (correct). |
| `:runtime-linux-noroot` | **52** | 0 | `ProotRuntime`, 7× `TODO()` | app | **Near-empty stub** (F1). |
| `:input-seat` | 1,499 | 714 | Trackpad gestures, desktop input routing | app, shell-desktop | OK. Well tested. |
| `:input-keyboard` | 18,100 | 261 | Hacker's Keyboard fork (IME, second-screen keyboard) | app | Fork. SPEC count (about 17,600) is roughly right. |
| `:library-core` | 16,471 | 5,234 | Library, providers, index, records, scrapers, ROM detection, launch resolution | app, runtime-windows, shell-gamepad, shell-desktop | Core. It **depends on `:shell-default` and `:IconLoader`** (`library-core/build.gradle.kts:102,109`) for `NativeAppProvider`. SPEC §9 does not show this edge. |
| `:display` | 154 | 0 | `SecondaryDisplayActivity` (SECONDARY_HOME) | app | Near-empty. SPEC says secondary-display behaviour lives here "in one place", but most of it is in `:app` (see D4). |
| `:shell-default` | 411 droidtop-own, of 135,042 compiled | 0 run in CI | Murine Launcher fork, plus droidtop's `dev.droidtop.shell.standard.*` (BackButtonMenu, OnboardingGate, HomeRolePrefs, CrashReporting, AlternativeLauncherActivity) | app, library-core | Carries 18 MB / 1,086 tracked files of `upstream-unused-reference/`, plus the upstream author's `fix_segmented.py`. `tests/` (306 files) never runs. |
| `:IconLoader` … `:systemUIPluginCore` (15 modules) | vendor-ish | – | Launcher3 support libraries under `shell-default/` | shell-default, library-core (IconLoader) | Upstream. Fine. |
| `:shell-gamepad` | 16,963 | 1,374 | Gaming shell and ES-DE renderer (`EsDeThemeRenderer` 4,244, `GamepadShell` 3,170, `EsDeSystemListView` 1,839) | app | The perf hot spot (P1, P4, P7). SPEC says "~8,000 lines". |
| `:shell-desktop` | 520 | 0 | Desktop Compose chrome: **taskbar, start menu, system tray**, viewport | app | SPEC §9 says it is "NOT the taskbar/app launcher (that's container-side)". The code is exactly a taskbar and start menu (`DesktopShell.kt:301,477`). |
| `pc-helper/` (Go, 280 LoC, not Gradle) | – | – | Sunshine REST client, Steam install trigger | nothing | Scaffold from the initial commit (`f7ff640c`). SPEC §7a says "needs reconsideration". Streaming-adjacent; belongs with windowcast or should go (D6). |

Dependency oddities:
- **Inverted edge `library-core → shell-default`.** The core library cannot be built or tested without the 135k-line launcher fork. That is also why the launcher cannot use the library (see F2): the dependency points the wrong way.
- **`runtime-windows → library-core`.** Tolerable, but it makes the Wine runtime a consumer of the library rather than the other way round.

---

## 2. Duplicated mechanisms and dead code

### D1 (Med, M): three stores of ROM entries, four scan entry points
- ROM entries are persisted three times:
  - `RomDatabase.rom_entries` + `scan_metadata` (`consoles/RomDatabase.kt:45,184`)
  - the library index `games` table (`LibraryIndexDatabase.kt:135`)
  - one JSON record per game (`GameRecord.kt:161`)
- One walk writes all three. The provider writes the record (`ConsoleRomProvider.kt` `writeRomRecords`) and the Room cache (`clearSystemFolder`/`insertEntries`). `RoomLibraryIndexStore.save` then re-reads and re-puts the same records (`RoomLibraryIndexStore.kt:93-103`).
- SPEC 7g keeps `RomDatabase` as "what makes ITS walk fast", but the index now does that job.
- The provider interface has four scan paths:
  - `scan()`, used only by `Library.scanAll/scanKinds`
  - `scanProgressive()`
  - `rescanProgressive()`
  - `slowRebuildProgressive()`
- On top of those, `ConsoleRomProvider.rescan()` (`:429`) is dead, and its own comment says it is kept "for any real future caller".
- `Library.scanAll/scanKinds` (`LibraryEntry.kt:715-740`) is a second, unindexed library API. It is still used by `GameLaunchActivity.kt:44` and `DesktopShell.kt:484` (see C3).
- **Fix:**
  - Drop the `rom_entries`/`scan_metadata` cache; the index plus records replace it.
  - Delete `scan()`, `scanAll`, `scanKinds` and `ConsoleRomProvider.rescan()`.
  - Resolve single entries from the index (`backgroundScanState` or a `Library.entry(id)` backed by the index).

### D2 (Med, S): user data inside a destructive cache DB
- `game_metadata` (scrapes and user edits) and `collections`/`collection_members` share `droidtop-rom-cache.db` with pure caches.
- That database has `.fallbackToDestructiveMigration(dropAllTables = true)` and `exportSchema = false` (`RomDatabase.kt:488-520`). One missed migration silently deletes the user's collections and metadata edits.
- SPEC 7g says the per-game record is the truth for metadata. In code it is not: metadata lives here, and favourites and play history live in `PlayHistoryDatabase`.
- **Fix:** move user-owned tables into their own non-destructive DB, or into the records. Export schemas.

### D3 (Low, S): two writers for the same Gaming prefs
- `GamingPrefs` (`GamepadShell.kt:902-950`) declares setters that nothing calls: `setDefaultSection`, `setShowHints`, `setAppsGridColumns`.
- The settings catalog (`GamingSettingsCatalog.kt:539-555`) writes the same keys directly under its own copies of the key strings.
- Also uncalled: `ThemePrefs.setControllerFamily/setTransitionsSetting` (`ThemePrefs.kt:138,145`) and `SecondScreenInput.setRole` (`SecondScreenInput.kt:59`).
- **Fix:** delete the dead setters and share the key constants.

### D4 (Med, M): secondary display logic split across three modules and two role models
- SPEC says `:display` owns secondary-display behaviour "in one place". In code it is split:
  - `:display`: 154 LoC
  - `:app`: `CompanionActivity`, `Companion*`, `SecondScreenPresentation`, `SecondaryDisplayRegistrations`, `DisplayRolePrefs`, and about 400 lines of relocation logic in `MainActivity`
  - `:runtime-common`: `DualScreen`, `DualScreenOrchestration`
  - `:library-core`: `LaunchDisplay`
- There are two persisted answers to "which screen is the shell on":
  - `DisplayRolePrefs.ShellTarget` in the launcher prefs file
  - `PrefsDualScreenAssignmentStore`, a separate `dual_screen_assignment` prefs file (`DualScreen.kt:29`)
- `MainActivity` consults both.
- **Fix:** pick one role model and move the orchestration into `:display`.

### D5 (Low, S): two `ThemePrefs` objects
- `shell-gamepad/.../theme/ThemePrefs.kt` wraps `runtime-common/.../theme/ThemePrefs.kt` only to add a Compose `version` counter.
- Its doc comment places the wrapped object in `:library-core`, which is wrong.
- **Fix:** acceptable as-is. Correct the comment, or expose a `StateFlow` from the library object.

### D6 (Med, S): streaming residue
- `pc-helper/` is the Sunshine-era scaffold (SPEC §7a: "needs reconsideration").
- The Windows mirror `G:\dev\and-pc` still has untracked `vendor/moonlight-common-c` and `vendor/mbedtls` (git status at session start).
- **Fix:** move `pc-helper` to windowcast or delete it, and clean the mirror.

### Dead code (verified: no caller in main sources)

| Item | Where | Size |
|---|---|---|
| `MediaAppBrowserClient` + `KnownMediaApps` (whole presence client; nothing constructs it) | `library-core/.../presence/MediaAppBrowserClient.kt` (200 LoC) | S: delete, or wire into the companion |
| `GameGrouping.suggestions` (O(n²) difflib pairs, never called) | `GameVersions.kt:181` | S |
| `ConsoleRomProvider.rescan()` | `ConsoleRomProvider.kt:429` | S |
| `MenuScreen` | `GamingMenu.kt:356` | S |
| `AppSelfUpdate.newerSeenVersionName`, `SystemStatus.volumePanelIntent`, `SteamAccess.installedDirFor`, `EngineVersionDetector.rgssGenerationContext`, `EsDeFolderStructure.alreadyStructured`, DAO `deletePartsForProvider`/`deleteProvider`/`allParts`, `findByCRC`/`findBySerial`, `getCollectionMemberIds` | various | S |
| `gameGroupKey` (test-only helper in main) | `GamepadShell.kt:1602` | S |
| Crash reporting with an empty DSN (`BUGSINK_DSN = ""`). SentryAndroid initialises on every start and reports nowhere. | `shell-default/src/dev/droidtop/shell/standard/CrashReporting.kt:27-31` | S |
| `shell-default/upstream-unused-reference/` (18 MB, 1,086 files, not compiled), `shell-default/fix_segmented.py` (hardcoded `C:\Users\Alex\...` path) | `shell-default/` | S |
| Stale doc comments that contradict the body. Example: the `ConsoleRomProvider` class doc says a system with no players "is skipped during scan", but the body (`:559-572`) says the opposite. | `ConsoleRomProvider.kt:233-235` | S |

---

## 3. Performance risks, ranked by likely user impact

### P1 (High, M): per-item filesystem stat walk on the main thread, in the gamelist
- `EsDeSystemListView.kt:302-306`: `remember(items, …) { items.map { item.copy(logoPath = item.esDePrimaryImage(...)) } }` runs during composition.
- `esDePrimaryImage` calls `EsDeArtwork.resolveImageTypes` (`EsDeArtwork.kt:242-261`). That loops declared types × media folders × 2 media roots × 4 extensions, calling `File.isFile` each time.
- A miss on the implicit `marquee` type alone is 8 stats per game. `image` expands to 4 folders, which is 32 stats per game.
- It runs over **every** game in the list, not just the visible ones. "All games" is the whole library.
- It re-runs whenever `items` changes. During any walk that is every publish (`PUBLISH_INTERVAL_MS = 250`, `LibraryEntry.kt:1204`).
- On the SD card through FUSE, each stat costs tens to hundreds of µs, so thousands of games stall the UI for seconds. This matches the reported "massive performance issues".
- ES-DE itself resolves on demand for visible entries (`onDemandTextureLoad`), as the comment at `:186` says.
- **Fix:**
  - Resolve per visible item in `produceState` on `Dispatchers.IO`, with an LRU keyed by (locator, types).
  - Better: at walk time, list each `downloaded_media/<system>/<type>` directory once into a name set and store what exists in the index row. That makes resolution a set lookup with no stats.

### P2 (High, M): the slow pass fully rescans ROMs on every start and every 30 min, and doubles the first walk
- `Library.startSlowRebuildOnce` (`LibraryEntry.kt:776-796`) starts 5 s after the first ordinary scan (`SLOW_REBUILD_START_DELAY_MS`, `:1207`), then repeats every 30 min (`:1210`) for the life of the process.
- For every matching provider it walks regardless of index state (`:904`, `!slow`).
- `ConsoleRomProvider` does not override `slowRebuildProgressive`, so it falls through to `rescanProgressive()` (`LibraryEntry.kt:578`). That re-walks every system folder and bypasses its own `scan_metadata` cache (`ConsoleRomProvider.kt:341-349`), including serial/ISO header reads and the per-ROM media stats from P3.
- `NativeAppProvider` is walked every round too, which brings P5 with it.
- **First run:** the ordinary walk (no index yet) and, 5 s later, the slow pass both walk every provider concurrently. The library is read twice while the user first looks at it.
- The mtime shortcut for engine folders only checks the top-level folder's mtime (`GameEngineDetector.kt:1055-1058`). That changes only when direct children are added or removed, so deeper additions are missed.
- **Fix:**
  - Don't start the slow pass while any ordinary walk is running, and never on a run with no index.
  - Give `ConsoleRomProvider` a real per-system mtime check: store each system folder's mtime, or a cheap count+mtime signature.
  - Exclude non-indexed providers from the slow pass.
  - Consider running the slow pass only while the device is idle or charging, or when the user returns to the shell, rather than on a timer.

### P3 (High, M): up to ~60 stats per ROM at scan time and at cache load
- For every ROM file, `ConsoleRomProvider.kt:638-640` calls:
  - `EsDeArtwork.resolve`: 7 media types × 2 roots × 4 extensions = up to 56 stats
  - `resolveManual`
  - `resolveVideo`: 2 roots × 3 extensions
- `RomEntity.toLibraryEntry()` (`:981-983`) repeats `resolve` and `resolveVideo` each time cached rows are loaded ("Media resolves LIVE").
- With the rig's 18k-file j2me folder, one walk is about a million FUSE stat calls. P2 makes that happen every 30 minutes.
- The per-file `async` (`:628`) creates one coroutine per file (18k), all doing blocking IO.
- **Fix:** read each system's media folders once per walk into sets (`listFiles` per `<system>/<type>`), then resolve from memory. Remove the live re-resolution at cache load and invalidate on media-folder mtime instead. Use a bounded worker pool, not one coroutine per file.

### P4 (High, M): theme parsing and discovery during composition, on the main thread
- `GamepadShell.kt:2229-2234`: building the system-carousel `items` calls `ThemeAssets.systemLogoPath(context, id)` for **every system group**.
- Each call runs `loadActiveTheme` (`ThemeAssets.kt:346`). On a cold cache that is a full XML parse of the theme for that system, with includes, on the UI thread: N systems means N parses before the first frame.
- Even on a warm cache, `loadActiveTheme` first calls `resolveActiveTheme` → `discoverThemes` (`ThemeAssets.kt:140,82-101`) **before** checking the cache. That is `assets.list` of the themes root, `assets.list` per bundled theme, and `listFiles` plus `isFile` over user themes. It is repeated per group per rebuild.
- The same happens for the focused system (`:2208`) and the gamelist (`:1727`, `:1524`), each keyed on focus changes.
- `systemThemeCache` and `capabilitiesCache` are plain `mutableMapOf` (`:255,:304`), yet the code's own comment says callers arrive concurrently (`:521-527`). That is a data race on a `HashMap`.
- **Fix:**
  - Cache the discovered theme list and the active descriptor, invalidated by `ThemePrefs` listeners.
  - Pre-parse the active theme for all present systems on a background dispatcher once the library groups are known, and expose the result as state.
  - Make both caches `ConcurrentHashMap`, or guard them.

### P5 (Med, S): every Apps scan re-encodes every icon as PNG
- `NativeAppProvider.scan()` (`NativeAppProvider.kt:41-80,152-160`) runs on each Gaming start (the provider is not indexed) and in every slow-pass round.
- For every launchable activity it renders the icon drawable to a new ARGB bitmap, then `compress(PNG, 100)` rewrites `cacheDir/app_icons/<pkg>.png`.
- This runs on Launcher3's single `MODEL_EXECUTOR` (`:60`), which the Standard launcher's model also uses.
- All bitmaps are held at once.
- Rewriting the same path also defeats Coil's path-keyed caches.
- **Fix:** write an icon only when the package's `lastUpdateTime` changed, keep bitmaps out of the list, and use a separate executor.

### P6 (Med, M): record-file IO multiplies with library size
- `RoomLibraryIndexStore.load()` hydrates every row by reading and decoding its JSON record (`RoomLibraryIndexStore.kt:60`). A start with an index still does N file reads. SPEC 7g records this cost as accepted, but it is a large part of cold start on a big library.
- `save()` for each changed segment does, per entry:
  - `records.get(entry.id)` (read + decode, `:100`)
  - `records.put` (encode, then read the existing file again to compare, `GameRecord.kt:197`)
- The provider already did the same put moments earlier.
- `recordPathFor` computes SHA-256 and formats 32 bytes with `"%02x".format` (`GameRecord.kt:238`), twice per entry per save (record path and index row).
- **Fix:**
  - Store what lists need in the index row, which SPEC 7g already specifies, and hydrate records lazily only for detail and launch.
  - Pass launch facts through the segment instead of re-reading them.
  - Compare a content hash kept in memory instead of re-reading the file.
  - Hex-encode with a lookup table.

### P7 (Med, S): recomposition hot spots in the Gaming shell
- `lastInputMs` is a snapshot state read in `GamepadShell`'s root scope (`GamepadShell.kt:295,303`) and written on **every key event** (`:546,556,742`). The whole 3,170-line root recomposes per press, about 20/s while a direction is held.
- **Fix:** keep the idle timer outside snapshot state, for example a `Channel` or an `AtomicLong` plus `LaunchedEffect(Unit)` with `snapshotFlow`/`delay`.
- The carousel reads `camOffset.value` in composition (`EsDeSystemListView.kt:596`). Every scroll animation frame then recomposes the carousel, recomputes `layoutEsDeCarousel` and recomposes each visible item. The code's own comment at `GamepadShell.kt:2224` says so.
- **Fix:** read the offset in the layout or draw phase (`Modifier.layout`/`graphicsLayer { }` lambdas).
- `val byGroup = entries.groupBy { it.gameGroup() }` (`GamepadShell.kt:1683`) is not remembered and runs on every recomposition of the games screen.
- `PcSurface` runs `LibraryGrouping.group(entries)` (regex-heavy `GameNaming.derive` per entry) on the main thread, on every publish (`PcSurface.kt:117`).
- There is no Compose stability config. `LibraryEntry` and `EsDeThemeElement` live in non-Compose modules. Strong skipping (Kotlin 2.2) limits the damage, but each new list instance still recomposes the whole tree.

### P8 (Med, M): the desktop frame path is a CPU copy loop
- `host-bridge/native/src/wayland_client.cpp:255-289`: every frame is swizzled per pixel into an `ANativeWindow` buffer on the CPU.
- `ANativeWindow_setBuffersGeometry` is called every frame.
- `frame_ready` immediately requests the next capture (`:313-316`). That is a tight loop with no `copy_with_damage` and no vsync pacing, so an idle desktop still costs a full-frame copy continuously.
- **Fix:**
  - Use `copy_with_damage` and pace to Choreographer.
  - Set the window format to the BGRA HAL format so there is no swizzle, or upload to a GL texture.
  - Set geometry once per size change.

### Smaller performance items (Low)
- The detail screens stat-walk media in composition: `GamepadShell.kt:969-978` and `PcGameDetail.kt:130-138`, up to 56 stats each via `EsDeArtwork.allMedia`.
- `ThemedImage`/`ThemedVideo` call `File(...).exists()` in composition for every themed element on every recomposition (`EsDeThemeRenderer.kt:723,778,799,807,2329-2339`).
- `ScanLog.write` opens, appends and closes a file under a global lock for every folder, and for every unreadable record (`ScanProgress.kt:254-266`). This serialises scan threads.
- A new ExoPlayer is built per selection change for video elements (`EsDeThemeRenderer.kt:1770`), with no ES-DE-style `videoDelay` debounce while scrolling.
- `GamelistOptionsMenu.consoleFoldersFor` lists root directories from a Main-scoped coroutine (`GamelistOptionsMenu.kt:190-195`).

---

## 4. Correctness and robustness

### C1 (High, M): concurrent index writers resurrect removed roots and delete new ones
- `Library.libraryProgressive` keeps a private in-memory `slices` copy per flow and saves the **whole** merged slice after every step (`LibraryEntry.kt:926-930`).
- The slow pass is a separate, never-restarted flow (`:776-796`) that loaded its slices at the start of its round.
- `RoomLibraryIndexStore.save` diffs against a shared `lastWritten` (`RoomLibraryIndexStore.kt:68-116`):
  - Any segment in `lastWritten` that the incoming slice lacks is treated as a removed root, and its **records and rows are deleted** (`:78-83`).
  - Any segment the incoming slice has that differs is rewritten.
- So:
  - **User removes a root while a slow round runs.** `keepOnlyRoots` drops it (`:982-1000`). The slow pass's next save still carries the old segments, which count as "changed", so the records and rows are recreated. The removed games come back at the next start.
  - **User adds a root (onboarding, or Settings) while a slow round runs.** The rescan writes the new root's segments. The slow pass's next save lacks them, so it **deletes those records and rows**. They survive in memory until restart, then vanish until another walk.
- The first-run case is likely: onboarding adds roots within minutes of the first scan.
- Nothing serialises saves per provider, and `lastWritten` is read-modify-write without a lock.
- **Fix:**
  - One writer per provider: a per-provider `Mutex` around merge and save.
  - Merge each step into the current stored slice, not a flow-private copy.
  - Cancel and restart the slow round on `keepOnlyRoots`, rescan or root change.
  - Save only the step's own segment, not the whole slice.

### C2 (High, S): the slow pass's results are invisible until restart
- The slow pass publishes to `backgroundScanStates[LibraryEntryKind.entries.toSet()]` (`LibraryEntry.kt:781,787`).
- The shell only observes `GAME_KINDS` and `APP_KINDS` (`GamepadShell.kt:191-192`, defined `:1271-1285`). No one observes the all-kinds key, so a new game found in the background shows up only at the next process start, from the index.
- Its publish still pays for `withLibraryFacts` and list building every 250 ms.
- SPEC 7g ("Publishes into the SAME `backgroundScanState` a normal scan of [kinds] would") and the code comment state the opposite.
- **Fix:** publish into every registered state whose kind set intersects the provider's kinds, or have the shell observe one all-kinds state and filter.

### C3 (Med, S): launch paths that do a full walk, and one that can crash
- `GameLaunchActivity` (exported, `GameLaunchActivity.kt:43-53`) resolves an id with `library.scanAll()`. That walks every provider without the index (the ROM provider's `scan()` walks all uncached folders plus P3's stats), just to find one entry.
- On failure it reports "No game with id", which is wrong when the walk itself failed (`runCatching{}.getOrNull()`).
- It also calls `Toast.makeText(this, …)` on a finished Activity.
- `DesktopShell` start menu (`DesktopShell.kt:483-512`):
  - Runs `library.scanAll()` every time the menu opens.
  - `scope.launch { library.launch(entry) }` followed by `onDismiss()` removes the composable that owns `scope`. The launch is cancelled at its first suspension (`withContext(Dispatchers.IO)`).
  - If it does throw (for example `NoEmulatorInstalled`), nothing catches it, so the app crashes.
- The companion-surface launch (`MainActivity.kt:206-211`) only logs failures, so the user sees nothing.
- **Fix:** add `Library.entry(id)` backed by the index and records. Launch from a process scope, and route errors to UI state.

### C4 (Med, S): blocking the main thread
- `AppSettingsCatalogs.kt:1567,1630,1648,1665,1682,1709`: `runBlocking { ConsoleSystemsDatabase…upsert/delete/restoreDefaults }` inside `ActionItem.run`/`onChange`. These run on the main thread from `SettingsCatalogView.kt:199,234,404` and the Preference renderer.
- `DesktopSessionService.onDestroy` (`DesktopSessionService.kt:95-100`): `runBlocking { runtime.stop(container) }` on the main thread. That is an `su` process plus droidspaces stop, which can take seconds, so there is an ANR risk.
- `AppSettingsCatalogs.platformsScreen` groups call DAO methods directly (`:1548-1550`). Check that the caller dispatches them.
- **Fix:** use `AsyncActionItem` (it already exists) for DB writes. In `onDestroy`, hand the stop to a process-lifetime scope or a short-lived worker.

### C5 (Med, S): force-stop via `su` on the launch path
- `ConsoleRomProvider.kt:824,947-953`: when a player preset sets `killPackageProcesses`, the launch runs `Runtime.exec("su -c am force-stop …").waitFor()` on the IO thread.
- On a rooted device this pops a root prompt in the middle of a launch and blocks it until answered. On an unrooted device it silently does nothing.
- Package names are gated to installed packages, so shell injection is not practical. It is still the rule break in R1.

### Other robustness items
- **Low:** `LaunchDisplay.launchContext` is a global var set around `provider.launch` (`LibraryEntry.kt:1080-1085`). Two concurrent launches (shell and companion) can cross-attribute launch-screen memory.
- **Low:** `NativeAppProvider.launch` returns silently when `getLaunchIntentForPackage` is null (`NativeAppProvider.kt:163-167`), and `Library.launch` then records a play for a launch that never happened.
- **Low:** `FileGameRecordStore.put` uses a fixed `<name>.tmp` path (`GameRecord.kt:199`). Two concurrent writers of the same id (the slow pass and a rescan, see C1) can interleave into one tmp file.
- **Low:** `DesktopSessionService` reports `ProotRuntime`'s `NotImplementedError` as "Couldn't create the primary container: An operation is not implemented…" (`:144-152`). It should say plainly that Desktop needs root today.
- **Low:** `ContainerRuntimeFactory.select` runs `su id` for every call (`ContainerRuntimeFactory.kt:30-33`). On a rooted device that can prompt again each time the container screen opens.

---

## 5. SPEC vs code drift

| # | Sev | SPEC says | Code says | Fix |
|---|---|---|---|---|
| S1 | High | §9: `runtime-linux-noroot → proot-based, ported from … DefaultProotContainerBackend`. §3 (`SPEC.md:366-377`) likewise. | 52 LoC, 7× `TODO()`. §5b (`:1399-1417`) and §7g "Dead weight" (`:4407-4413`) already say that port is impossible (no arm64 proot). §3 and §9 contradict §5b. | Rewrite §3's table and §9's line. |
| S2 | High | §2c Launcher mode: "the place a game or a container app appears as an ordinary icon"; "With Gaming off, games still launch — from the launcher" (`:263-289`). | No code in `:shell-default` references the library, `GameLaunchActivity` or `LAUNCH_GAME`, and no shortcuts are published (grep across `shell-default/src`, `app`, `library-core`). The only entry is `adb am start`. | Build it (F2), or mark it unbuilt. |
| S3 | High | 7g impl decisions: the slow pass "Publishes into the SAME backgroundScanState a normal scan of [kinds] would". | It publishes to an all-kinds key nobody observes (C2). | Fix the code. |
| S4 | Med | §9: `shell-desktop` is "NOT the taskbar/app launcher (that's container-side)". | `DesktopShell` is a Compose taskbar, start menu and tray (`DesktopShell.kt:112,301,356,477`). | Decide which one; if the container owns the taskbar, delete droidtop's copy (one mechanism). |
| S5 | Med | §9: `runtime-common → shared types; no deps`. `library-core → depends on runtime-common`. | runtime-common holds the ES-DE engine and settings model. library-core also depends on shell-default and IconLoader. | Update the map, or move the theme engine into its own module. |
| S6 | Med | §9: shell-gamepad "~8,000 lines", "best-developed module". | 16,963 main LoC. Line counts are ledgers that go stale; the "state from code" rule says don't keep them. | Remove the counts. |
| S7 | Med | §9 "Increment 2, still open: Hilt bootstrap in `:app`". | `@HiltAndroidApp` on `DroidtopApplication.kt:41`, and `@AndroidEntryPoint` on `PcStoreActivity.kt:54`. Done. | Remove the stale "open" note. |
| S8 | Med | §2c/§7b/§11: root desktop-only (`:4544` "Root remains desktop-only"). | `GameNativeMigration` (root copy of another app's data, reachable from settings: `AppSettingsCatalogs.kt:915-938`) and the `su` force-stop (C5) are Gaming-side root uses. §5b lists `GameNativeMigration` as "safe by construction". The force-stop is undocumented. | Either record an explicit exception in SPEC, or remove both (R1). |
| S9 | Med | 7g first subsection: the index is "one JSON file each under `files/library-index/`". | Superseded by the Room index and records in the next subsection. The text says "replaces the storage half", but the old paragraph still reads as current. | Delete the superseded storage text. |
| S10 | Med | 7g: records hold "what is shown (title, sort names, metadata, media)"; lists never read records. | Scraped metadata lives in `RomDatabase.game_metadata`, favourites and history in `PlayHistoryDatabase`. The index load reads every record (P6). | Align. |
| S11 | Low | §7e: "`FocusCompanion`… Not built this session", "`PresencePanel`… Not built this session". `MediaAppBrowserClient` is "the one client". | A companion exists (`CompanionActivity`, rail, idle rotation). `MediaAppBrowserClient` is unused. "This session" phrasing appears 7 times. | Rewrite §7e from the code. |
| S12 | Low | §7a `pc-helper` "Needs reconsideration". | Untouched since the initial scaffold. | Decide (D6). |
| S13 | Low | "Order of work" lists in 7g (`:4422-4436`) still say "Delete the dead streaming module", which the same section says is done. | – | Remove the task lists (SPEC should not be a ledger). |
| S14 | Low | §2c Rule 1: "a disabled mode runs no code… no warm-up thread". | The slow rebuild loop, once started, runs for the life of the process even after Gaming is turned off (`LibraryEntry.kt:776-796`). Sentry initialises in every mode. | Tie the slow pass to Gaming being on. |

Code the SPEC does not describe:
- `GamingPrefs` and the catalog as dual writers.
- The `DisplayRolePrefs` plus `DualScreenAssignmentStore` dual model.
- The per-frame desktop blit design.
- The inverted `library-core → shell-default` edge (only in a build-file comment).

---

## 6. Tests

CI runs unit tests for `:app :library-core :runtime-common :shell-gamepad :input-seat :runtime-windows :input-keyboard :host-bridge` (`.github/workflows/android-checks.yml:244`). There are no instrumented tests.

| Module | Unit test files | Notes |
|---|---|---|
| library-core | 32 (5,234 LoC) | Good coverage of naming, grouping, engine detection, `Library` merge logic (with a fake index), runner availability. |
| runtime-common | 31 (3,737) | ES-DE layout maths well covered. **No test for `EsDeThemeParser` (841 LoC) or `ThemeAssets` (622).** |
| shell-gamepad | 16 (1,374) | Navigation, tiles, contrast, a few renderer helpers. No test of the list/image resolution or recomposition behaviour. |
| input-seat | 5 (714) | Good. |
| runtime-windows | 3 (258) | `WineLaunchPlan`, `SafeDelete`, a migration schema check. |
| app | 3 (240) | Onboarding plan, update-now. |
| host-bridge | 1 (118) | Clipboard sync. |
| input-keyboard | 1 | – |
| runtime-linux-root, runtime-linux-noroot, display, shell-desktop | **0** | – |
| shell-default | 306 upstream test files, **never run** | – |

The important untested code, in priority order:
1. `RoomLibraryIndexStore` save/diff/delete and its concurrency (C1). This is the code that deletes records.
2. `FileGameRecordStore` concurrency and the write-if-changed path.
3. `ConsoleRomProvider` walk, cache and records (including `RomEntity.toLibraryEntry`) and `RomScanWalk` on a fake tree.
4. `Library` slow pass: which state it publishes to (C2), and overlap with a rescan.
5. `EsDeThemeParser` includes/variables/variants, and the `ThemeAssets` cache keys.
6. `DroidSpacesRuntime`, `CraneRootfsPuller`, `RootfsDelete`. These run destructive root commands; after the wipe incident they deserve tests over a fake `RootProcess`.
7. `RomDatabase` migrations, which hold user data (D2). `exportSchema = false` makes Room migration tests impossible.
8. `AppSelfUpdate` digest verification (only `UpdateNow` is tested).
9. Performance regression tests. Add a JVM benchmark or test that counts `File` stats per item for `esDePrimaryImage` and ROM detection.

---

## 7. Non-root feature completeness per mode

### Launcher (Standard) mode
- **Can:**
  - Everything Murine/Launcher3 does: home, drawer, widgets, icon packs, hidden apps.
  - Choose it as HOME (`HomeRolePrefs`), with the second-screen launcher handoff.
  - Open Gaming/Desktop/settings via long-press of back (`BackButtonMenu`).
  - Run onboarding.
- **Cannot:**
  - See library games as icons, pin a game, or launch a game from the launcher (S2/F2). The shared launch entry point exists but nothing in the launcher calls it.
  - Container apps as icons: no.
- **F2 fix (M):** publish pinned or dynamic shortcuts per library entry that target `GameLaunchActivity`. That needs C3's index-backed lookup first, or each tap does a full walk.

### Gaming mode (the most complete)
- **Can:**
  - Browse ROM, engine, PC and app libraries in real ES-DE themes. DEcaffe is bundled; themes can be downloaded.
  - Launch ROMs through installed emulators (am-start presets from the players DB, FileProvider URIs).
  - Launch engine games through enginehost/Kirikiroid2.
  - Launch Windows games through gamenative's bionic Wine path (`WineGameActivity`, no root).
  - Sign in to Steam/GOG/Epic/Amazon and use the store screens (vendored UI).
  - Scrape (ScreenScraper, TheGamesDB, IGDB, libretro thumbnails).
  - Use collections, favourites, the metadata editor, the missing-game fold, the Quick Menu, the screensaver, the companion on a second screen, and the second-screen keyboard.
- **Cannot, or degraded:**
  - Native Linux games (`LINUX_CONTAINER` strategy) without root. They need a desktop session.
  - GameNative migration without root (root-only by design, see R1).
  - Emulator force-stop presets without root (silently skipped).
  - Performance on large libraries is the main user-facing defect (P1-P7).

### Desktop mode
- **Can:** show the Compose taskbar, start menu and tray, and launch library entries from the start menu (with the C3 bugs).
- **Cannot (non-root):** anything the mode exists for:
  - No container, compositor, terminal, Linux/Windows windows or clipboard bridge.
  - The container manager lists nothing (`ProotRuntime.listContainers` returns empty).
  - `DesktopSessionService` fails with a `NotImplementedError` message.
- **F1 (High, L):** implement a non-root Linux backend or state plainly in the UI that Desktop requires root. SPEC §5b already records that the gamenative proot port does not exist for arm64.

---

## 8. Security and privacy smells (droidtop's own code)

| Sev | Finding | Evidence | Fix |
|---|---|---|---|
| High (rule) | **R1: root outside the desktop stack.** `su -c am force-stop <pkg>` on ROM launches, and root staging of `/data/data/app.gamenative` into droidtop. Both break "root is desktop-only". The migration also reads another app's private data, including its Steam session DataStore. | `ConsoleRomProvider.kt:947-953`; `GameNativeMigration.kt:180-237`, reachable from `AppSettingsCatalogs.kt:915-938` | Remove the force-stop, or gate it behind Desktop/root with explicit consent. Record the migration exception in SPEC or remove it. |
| Med | `GameLaunchActivity` is exported with no permission. Any app can start any library entry by id (ids are file paths, so guessable), and each intent triggers a full `scanAll()` walk (C3). That is a cheap way to burn IO/battery. | `AndroidManifest.xml:163-176` | Require a signature permission or make it non-exported, and use the index lookup. |
| Low | `OnboardingActivity`, `ConsoleSystemsActivity` and `MainActivity` are exported without need. `ConsoleSystemsActivity`'s comment says it is started only by explicit component from inside the app. `MainActivity` accepts mode/rescan/section extras from any caller. | `AndroidManifest.xml:140-181` | Set `exported="false"` where only droidtop starts them. |
| Low | FileProvider `root-path "."` covers the whole filesystem, including droidtop's own `/data/data`. Grants are read-only and only for library ROM paths today, so this is safe only while that stays true. | `app/src/main/res/xml/file_paths.xml`; `AmStartCommandToIntentConverter.kt:178,249-269` | Restrict to storage volume roots (`external-path`, plus explicit SD-card mounts). |
| Low | ScreenScraper dev credentials are shipped XOR-scrambled. This is documented and accepted. User ScreenScraper/IGDB/TGDB secrets sit in plain `SharedPreferences` (`allowBackup=false` mitigates). | `ScreenScraperDevCredentials.kt`; `ScreenScraperPrefs.kt:22-24`; `ScraperPrefs.kt:16` | Consider `EncryptedSharedPreferences` for user secrets. |
| Low | `scan.log` goes to `getExternalFilesDir` and records full paths of the user's library (`ScanProgress.kt:237`). Other apps cannot read it on API 30+, but adb/USB users can. | – | Acceptable. Note it in privacy docs. |
| OK | Self-update: HTTPS from GitHub releases, SHA-256 checked against the published digest before a PackageInstaller commit (`AppSelfUpdate.kt:267-290`). The `UpdateNowReceiver` is guarded by the `DUMP` permission. No cleartext HTTP, no `MODE_WORLD_*`, no WebView JS bridges in droidtop's code. | – | – |

---

## 9. Suggested order of work

1. **P1 + P3 together (M):** add a per-walk media presence set, store the presence or resolved paths in the index row, and do no per-item stats anywhere in composition or cache load. This is the single biggest perceived-speed win.
2. **C1 + C2 + P2 (M):** one writer per provider, restart the slow pass on root changes, publish into observed states, don't overlap walks, and give the ROM provider a real mtime skip.
3. **P4 + P7 (M):** move the theme model off the main thread and cache discovery; fix `lastInputMs` and the carousel offset read.
4. **C3/C4 (S):** index-backed `Library.entry(id)`; remove `scanAll`/`scan()`; move main-thread `runBlocking` into async actions.
5. **R1 (S):** remove or gate the Gaming-side root uses, and record the decision in SPEC.
6. **D1/D2 (M):** retire `rom_entries`/`scan_metadata`; move user tables to a non-destructive store.
7. **S1-S14 (S):** SPEC cleanup (module map, proot contradiction, Launcher claims, ledgers).
8. **F2 (M), then F1 (L):** launcher game shortcuts; then a non-root desktop answer.
9. **Dead code (S):** `MediaAppBrowserClient`, `suggestions`, `rescan()`, the setters, `upstream-unused-reference/`, `fix_segmented.py`, `pc-helper/`, the inert Sentry.
