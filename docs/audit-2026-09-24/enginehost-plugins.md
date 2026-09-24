# Enginehost engine-plugin repositories: full audit (2026-09-24)

Read-only audit. Sources: the org's repositories (`gh repo list droidtop`), fresh bare clones in
`/root/scratch-audit/repos/` (not the working clones), every current release asset downloaded to
`/root/scratch-audit/rel/<tag>/` and listed from its signed manifest and `tar -tvJf`, `gh run list`
(last 60 runs per repo), the host at `/root/enginehost` (`codex/engine-bundles` bbdb4da), and the
user's local game library. Coordination files were read for context only; where they
disagree with code or release assets, code/assets win and the disagreement is listed (section 4.14).

Changes that happened while the audit ran: `plugin/8.0` was created on the Ren'Py repo (0d210683f;
its first CI run **failed**, see 4.3), and `godot-v44-v1-testing` and `godot-v46-v1-testing` were
published by workflow_dispatch at 04:17 UTC today.

---

## 1. Top findings by severity

| # | sev | finding |
|---|---|---|
| F1 | HIGH | **RGSS (mkxp-z), MV/MZ and CatSystem2 plugins still use the old shared action ids** (`up`, `confirm`, `cancel`, ...). Since host commit 0636224 (2026-09-10, "The controller map speaks each engine's own vocabulary") the host sends `rgss_*`, `mvmz_*` and `cs2_*` ids (`ControllerBindings.kt`). mkxp-z's `keyForAction` returns `KEYCODE_UNKNOWN` for every one of them; `RpgMakerWebPlugin.onControllerEvent` and `CatSystem2Plugin.onControllerEvent` fall through to `default: return false`. Every pad proof for these three (dq-catsystem2-28/29, the user's 09-09 RGSS pad statement) predates the rename. Controller input through the map is dead on any current host for these three engines. |
| F2 | HIGH | **Every Ren'Py testing bundle carries the "packaging template runs instead of the game" loader bug.** All nine testing releases are from 2026-09-05/06; the loader fix landed on 7.4/7.5/7.6/8.1 on 09-17 and on 7.7/7.8/8.2-8.5 only today. On every Ren'Py line, testing is currently worse than unstable. |
| F3 | HIGH | **Testing channels break the two-ABI rule**: `kirikiri-v1-testing` (build 83), `cmvs-ps2-ps3-v1-testing` (45), `catsystem2-cst-v1-testing` (42), `rpgmaker-easyrpg-v1-testing` (53) are arm64-v8a only. Their unstable builds are fixed (x86_64 present), but nobody re-promoted them. |
| F4 | HIGH | **mkxp-z/RGSS has no testing release at all.** DECISIONS (2026-09-04, 09-05 20:11), the rpgmaker BRIEF and ROSTER all say "rgss on testing". Only `rpgmaker-rgss-v1-unstable` (build 158, 2026-09-11) exists. |
| F5 | MED | **Licence texts missing from payloads**: Buriko (the GPL-2.0 and SDL zlib texts are not in the bundle, only a 3-paragraph `LICENSES.md`); KiriKiri (payload carries only `LICENSES.md`; upstream's BSD-style `LICENSE` requires the notice in the documentation accompanying a binary, and the manifest declares `GPL-2.0-or-later`, which does not match upstream's licence text). |
| F6 | MED | **Save-policy gaps** (DECISIONS 2026-09-17): CatSystem2 writes saves to the host folder, and its in-code rationale quotes the superseded "never the game folder" rule (the line was never re-reviewed under the new policy); KiriKiri maps the SYSTEM locations `System.personalPath` / `appDataPath` to the game folder (`TVPGetAppPath()`) instead of redirecting or disabling them; EasyRPG keeps its config in Android app-private storage (`getFilesDir()/enginehost/config`); Godot's 32-bit ABIs are stock Maven runtimes without the `user://` redirect patch; Buriko has no save support yet. |
| F7 | MED | **Godot lines do not pin release tags to the built commit.** "Pin Godot release tags to their builds" (5fed3f932, Codex) is only on `plugin-core`; every Godot release, including today's v44/v46 testing, has `target_commitish: master` (upstream Godot's master). Every other repository's lines carry the pin. |
| F8 | MED | **Bypass engines ignore remaps**: Ren'Py, Godot and EasyRPG read no `CONTROLLER_BINDINGS` / `onControllerEvent` at all, so when a person turns bypass off and remaps, nothing changes. Buriko reads no controller map at all, and it is not a bypass engine. |
| F9 | MED | **CI red rates**: KiriKiri 50% since 09-10 (11 of 22), mkxp-z 50% (2 of 4), EasyRPG 25%; Godot 28% over its last 60 runs (09-17..09-19 line creation). Ren'Py, CMVS, CatSystem2 and Buriko are at 0-6%. |
| F10 | LOW | **AI attribution in commit authorship**: 33 commits authored `Codex <codex@openai.com>` across 11 repositories, most on live plugin lines (hash list in section 6). None of our commits carries a `Co-Authored-By: Claude` or "Generated with" trailer; the only such trailers are in upstream history (ruffle, renpy-build). |

More MED/LOW items are in sections 3-5: stale `plugin-core` branches in every repo; a stray
`twine-2_12-v1-unstable` release from HTML `plugin-core`; the PLATFORMS reindex step exists only on
Ren'Py `plugin-core`; the `channel` input defaults to `stable` with no pre-1.0 guard in every workflow;
Godot 4.5 lacks the Java/native version guard every other Godot line has; `plugin/4.5-embedded-pack`
is a dead arm64-only branch with the v45 bundle id; three legacy repositories are empty.

---

## 2. Engine readiness table

Channel ages are relative to 2026-09-24. "Build" is the third `pluginVersion` component in the signed
manifest. ABIs are read from the downloaded bundles, not from build scripts (a64 = arm64-v8a,
v7a = armeabi-v7a, x64 = x86_64). No stable release exists anywhere, which is by design: nothing goes to
stable before 1.0.

| engine / line | channel now (build, age) | ABIs (unstable / testing) | last device evidence found | blocker to testing | blocker to stable |
|---|---|---|---|---|---|
| Ren'Py 7.3 | unstable 248 (0d) | a64,v7a,x64 / - | none; dq-renpy-20 (rig, Maid Mansion) running | rig pass on Maid Mansion / My New Family | 1.0 |
| Ren'Py 7.4 | unstable 239 (7d); testing 219 (18d) | a64,v7a,x64 / same | console dq-017 MIST (09-05) | testing lacks loader fix: re-promote | 1.0; A10 SIGABRT on a bad game path |
| Ren'Py 7.5 | unstable 240 (7d); testing 218 (19d) | same / same | console dq-005, dq-renpy-05 FreshWomen (09-05/06, keyboard proof partial) | re-promote with loader fix | 1.0 |
| Ren'Py 7.6 | unstable 241 (7d); testing 222 (18d) | same / same | console dq-coordinator-05-06 ToBeAKing (09-06; that game is no longer in the library) | re-promote; re-prove on AHouseInTheRift | 1.0 |
| Ren'Py 7.7 | unstable 249 (0d); testing 223 (18d) | same / same | console dq-coordinator-08 Price-Of-Power (09-06; not in the library now) | **no 7.7 game in the library** | same |
| Ren'Py 7.8 | unstable 250 (0d) | same / - | console: Adreno SIGSEGV (09-06, dq-coordinator-09/10); rig: placeholder bug (09-24) | dq-renpy-20 (a 7.8 game); console Adreno crash still open | Adreno crash |
| Ren'Py 8.0 | **no release**; plugin/8.0 created today, CI failed | - | none | CI: `enginehost/template/.android.json` missing (the 7.x lines' dotfile fix is not applied) | - |
| Ren'Py 8.1 | unstable 237 (7d); testing 220 (18d) | same / same | console dq-renpy-02 ARTEMIS (09-05); rig DivineDawn found the placeholder bug (09-17) | re-promote | 1.0 |
| Ren'Py 8.2 | unstable 251 (0d); testing 214 (19d) | same / same | console dq-006 Ripples (09-05) | re-promote after dq-renpy-20 | 1.0 |
| Ren'Py 8.3 | unstable 252 (0d); testing 215 (19d) | same / same | console dq-renpy-06 Radiant, full save/quit/load (09-06) | re-promote after dq-renpy-20 | 1.0 |
| Ren'Py 8.4 | unstable 253 (0d); testing 221 (18d) | same / same | console dq-renpy-01 30YearOldVirgin (09-05; the card copy was broken) | re-promote; the only 8.4 game was a broken copy | second 8.4 game |
| Ren'Py 8.5 | unstable 254 (0d); testing 224 (18d) | same / same | console dq-coordinator-08 WorldsCrossingAcademy (09-06) | re-promote after dq-renpy-20 | 1.0 |
| Godot 4.0 | unstable 115 (5d) | a64,v7a,x86,x64 | none (no library game) | no 4.0 game | - |
| Godot 4.1 | unstable 116 (5d) | same | none | no 4.1 game | - |
| Godot 4.2 | unstable 117 (5d) | same | none | no 4.2 game | - |
| Godot 4.3 | unstable 118 (5d) | same | rig: pack-only fixture (09-18) | no 4.3 game | - |
| Godot 4.4 | testing 122 (0d, today); unstable 119 (5d) | same / same | rig: ACM2 (09-18) | done today | REC needs a .NET line; tags not pinned (F7) |
| Godot 4.5 | testing 57 (19d); unstable 120 (5d) | same / same | console GE 0.13 (09-05/06); dq-godot-30 pending | testing predates the user:// redirect, restart and platform presentation: re-promote after dq-godot-30 | missing version guard (3.2) |
| Godot 4.6 | testing 123 (0d, today); unstable 114 (5d) | same / same | rig: GE 0.14 (09-18 night) | done today | - |
| Godot 4.7 | unstable 121 (5d) | same | none | no 4.7 game | - |
| Godot 3.x / .NET | no line | - | - | user's decision (another_girl_in_the_wall is 3.6; REC is 4.4.1 .NET) | - |
| KiriKiri | unstable 114 (9d); testing 83 (18d) | a64,x86,x64 / **a64 only** | console dq-kirikiri-11 Noble Works (09-09, FAIL title navigation mode b); x86_64 never run (dq-kirikiri-20 pending) | x86_64 run; title navigation | E-mote no-op; CI 50% red |
| CMVS (ps2/ps3) | unstable 70 (7d); testing 45 (18d) | a64,x64 / **a64 only** | console dq-cmvs-08 ChronoClock (09-06) | re-promote with x86_64 | .cmv movies; save/load proof |
| CatSystem2 | unstable 48 (8d); testing 42 (17d) | a64,x64 / **a64 only** | console dq-catsystem2-29 Labyrinth (09-09) | re-promote; **controller ids (F1)** | save/load never run; the user said to stop testing Labyrinth on the console |
| Buriko (OpenBGI) | unstable 78 (8d) | a64,x64 | console dq-buriko-02 (the one BGI test game) (09-06, black frame) | first real frame | saves, controller, a second BGI game |
| NScripter (onsyuri) | unstable 2 (7d) | a64,x64 | none | **no NScripter game in the library** | - |
| EasyRPG (2000/2003) | unstable 59 (7d); testing 53 (18d) | a64,x64 / **a64 only** | console dq-rpgmaker-06 TestGame 2000/2003 PLAY pass (09-06) | re-promote with x86_64; dq-rpgmaker-30 | no real 2000/2003 game in the library |
| RGSS (mkxp-z) XP/VX/VX Ace | unstable 158 (13d); **no testing** | a64,v7a,x64 | console MGQ Paradox (09-04/05); user's pad statement 09-09 (before the id rename) | **controller ids (F1)**; dq-rpgmaker-30 | one VX Ace game only; no XP or VX game |
| MV/MZ (web) | unstable 6 (8d); testing 3 (20d) | none (no native code) | console Happy NEET (09-04) | **controller ids (F1)** | four more MV/MZ games untested |
| HTML (Twine/SugarCube) | unstable 37 (8d); testing 32 (20d) | none | console, 4 games (09-04) | - | - |
| Flash/AIR (Ruffle 0.4.1) | unstable 31 (8d); testing 28 (20d) | none | console Bunnycop (09-04) | - | plain SWF untested (no game); ANEs are stubs |

---

## 3. Per-repository findings

### 3.1 enginehost-renpy-plugin (fork of renpy/renpy): LIVE, keep

- Purpose: Ren'Py engine lines, one branch per minor (`plugin/7.3` .. `plugin/8.5`, plus `plugin/8.0` new
  today). Each is an upstream release plus our changeset (`enginehost/`, `renpy/{bootstrap,loader,main,savelocation}.py`,
  the workflow). It builds through the official RAPT/SDK zips from renpy.org, not through our renpy-build or rapt forks.
- Branches: 11 live lines, `plugin/8.0` (new), `plugin-core` (last 09-11), stale `plugin/renpy8` and
  `plugin/renpy8.5.3` (09-01, no bundle metadata, superseded by plugin/8.5), `archive/plugin-core-2026-09-03`,
  `codex/plugin-core-restored-8.3`, and about 25 upstream branches (`history/*`, `work/andy_kl/*`, feature branches).
- CI: 40 ok / 0 fail / 1 cancelled in the window; median 3 min, max 22 min. `sync-upstream.yml` green daily.
  plugin/8.0's first run failed today (`FileNotFoundError: enginehost/template/.android.json`).
- Releases: unstable on all 11 lines; testing on 9 (not 7.3 or 7.8); every testing release is 18-19 days old.
- ABIs: every bundle has a64+v7a+x64. 7.3 was fixed in build 248 (c8c5d6493), so the rig's 09-17
  arm64-only finding is closed on unstable.
- Save policy: compliant on all lines. `main.py` sets `savedir = <ENGINEHOST_SAVE_PATH>/<config.save_directory>`,
  which redirects the system location; `savelocation.py` keeps `game/saves` as a second read/write location.
  The behaviour is the same on all 11 lines; the diffs differ only in log wording and upstream structure.
- Controller: the plugin reads no controller map (bypass engine, see F8).
- Workflow drift across lines: five different `android-plugin.yml` blobs (7.3 alone; 7.4-7.8 + 8.4; 8.1;
  8.2/8.3; 8.5). `apply_android_integration.py` has three variants (7.x + 8.4; 8.1; 8.2/8.3/8.5).
  8.1/8.2/8.3/8.5 carry `enginehost/android/EngineHostRunActivity.java` and 7.x and 8.4 do not;
  7.x and 8.4 carry `enginehost-origin.json` and 8.1/8.2/8.3/8.5 do not. Structurally, 8.4 is a 7.x-generation line.
- Licence: the payload has only `LICENSES.md`, which says the notices live inside the embedded RAPT APK
  (plausible, not verified). The wrapper is declared MIT, the most permissive option.
- Test games: the user's library has games on every line except 7.7 (titles kept out of the public repo; the coordinator has the list).
- Open functional gaps: the 7.8 Adreno SIGSEGV (console); A10, where a bad game path SIGABRTs through a JNI
  pending exception instead of giving a startup error; the `plugin/8.0` build.

### 3.2 enginehost-godot-plugin (fork of godotengine/godot): LIVE, keep

- Branches: lines `plugin/4.0` .. `plugin/4.7.1` (one per minor, DECISIONS 2026-09-17), `plugin/4.5-embedded-pack`
  (dead, see below), `plugin-core` (09-08), archives, and upstream `1.0`..`4.7` and `master`.
- CI: 38 ok / 15 fail / 5 cancelled over the last 60 runs, all since 09-17 during line creation; median 7 min, max 25.
  The upstream `runner.yml` is disabled manually.
- Releases: unstable v40-v47; testing v44 and v46 (both today) and v45 (09-05, build 57).
- ABIs: every line has 4 ABIs, but **only arm64-v8a and x86_64 are built from our tree**. armeabi-v7a and x86
  are the stock Maven AAR runtime, so on a 32-bit device there is no `user://` redirect (it is a native
  `os_android.cpp` patch), no platform presentation, and no spine, even though 4.2-4.6 declare `spine-godot`
  for the whole bundle.
- Save policy: compliant on 64-bit (user:// goes to `<host save>/<project user dir>`, 1bdd0c1a4, present on all 9 lines).
- Restart support (`onGodotRestartRequested` / `onNewGodotInstanceRequested` plus `restartArguments`) is present on all lines.
- Cross-line gaps:
  - Tag pinning is missing on every line (F7).
  - `plugin/4.5-embedded-pack` diverged on 09-18. It lacks the last two commits of 4.5, still carries the
    reverted "4.5 serves every 4.x game" widening, and has the **same bundleId `dev.enginehost.godot.v45.v1`**,
    an arm64-only matrix and no publish job. Recommend archiving it.
  - 4.5 lacks the "Java library and the native engine are the same Godot version" CI guard that 4.0-4.4,
    4.6 and 4.7.1 all have. The versions match today, so the gap is latent.
- Controller: the plugin reads no map (bypass engine, F8).
- Licence: `LICENSE.txt`, `COPYRIGHT.txt` and `LICENSE-spine-runtimes.txt` are in the payload. Good.
- Test games: 4.4 (ACM2; REC is also 4.4.1 but it is a .NET export); 4.5 (Goodbye Eternity 0.13, whose
  folder `enginehost.json` points at the Linux 0.13 binary); 4.6 (GE 0.14 Windows); 4.0-4.3 and 4.7: none;
  3.6: another_girl_in_the_wall (no line).

### 3.3 enginehost-kirikiri-plugin (fork of Kirikiroid2Yuri): LIVE, keep

- Branches: a single line, `plugin/stable` (its name collides with the channel word). The default branch is
  `yuri` (upstream); also `master`, `plugin-core` (09-08), `promotion/build-77` (leftover) and archives.
  The working clone has an unpushed but already-merged `codex/kirikiri-x86-64-openssl-config` and a
  `plugin/2.31` tracking branch whose upstream is gone.
- CI: 41 ok / 16 fail; **since 09-10: 11 ok / 11 fail** (the x86/x86_64 dependency port); median 4 min, max 18.
  The upstream `build_android.yml` is still active.
- Releases: unstable build 114 (09-15) has arm64-v8a, **x86** and x86_64; testing build 83 is arm64 only.
  PLAN-2026-09-24 A7 ("ships NO x86_64") is stale: the unstable bundle already carries it; only testing lacks it.
- Save: `savedata/` beside the game (fine). `personalPath` and `appDataPath` return the game folder (F6).
- Controller: reads `CONTROLLER_BINDINGS` with the shared ids, which matches the host's common set.
  Title-screen pad navigation still fails (dq-kirikiri-11).
- Licence: see F5. Upstream `LICENSE` is a BSD-3-style Japanese licence; the manifest says GPL-2.0-or-later;
  the payload carries only `LICENSES.md` (plus the Noto font licence inside the APK, per its notes).
  Nothing declares the licence of our own wrapper code.
- Test games: Noble Works, Café Stella, Fate/stay night Ultimate, My Girlfriend is the President (4).

### 3.4 enginehost-cmvs-plugin (own engine, MIT): LIVE, keep

- Branches: `plugin/2.0-3.0`, `main`, `plugin-core` (09-08), `promotion/build-43` (leftover).
- CI: 56 ok / 2 fail / 2 cancelled; 1 failure since 09-10; median 2 min.
- Releases: unstable 70 (a64+x64); testing 45 (a64 only, F3).
- Save: `save/` inside the game folder, as cmvs32.exe does (1af89ee). Compliant.
- Controller: the full `cmvs_*` id set, matching the host table. Compliant.
- Licence: our code is MIT; `LICENSE` and `THIRD_PARTY.md` are in the payload. Good.
- Test games: ChronoClock only (in `unknown/`). Movie playback (.cmv) is still open.

### 3.5 enginehost-catsystem2-plugin (own engine, MIT): LIVE, keep

- Branches: `plugin/0.1` (live), `main` (engine build workflow), `plugin-core` (09-09), and `plugin/2.0`
  (stale since 09-04, no `enginehost/bundle-metadata.json`, a pre-reorganisation copy: archive it).
- CI: 58 ok / 0 fail / 2 cancelled; median 1 min.
- Releases: unstable 48 (a64+x64); testing 42 (a64 only).
- Save: writes to the **host folder**, and the code comment cites the superseded rule (F6). Controller: common ids (**F1**).
- Test games: the library now has 4 CatSystem2 titles (Fruit, Eden, Labyrinth, Princess Evangile), so
  TEST-MATRIX.md's "need 3" row is stale.

### 3.6 enginehost-buriko-plugin (fork of Cytlan/openbgi, GPL-2.0): LIVE (unstable only), keep

- Branches: `plugin/0.0.1`, `main`, `plugin-core` (09-08), archive, `codex/plugin-core-restored-0.0.1`.
  In the working clone, local `plugin-core` is 77 ahead and 4 behind origin (unpushed divergence), and
  `src/build_number.h` has an uncommitted change.
- CI: 52 ok / 0 fail / 8 cancelled; median 2 min. Releases: unstable 78 only (a64+x64).
- Save: none implemented. Controller: **no controller code at all** (F8). Licence: see F5.
- Test games: one BGI game only; a second BGI title exists in the library as a candidate.

### 3.7 enginehost-nscripter-plugin (copy of OnscripterYuri, GPL-2.0; not a GitHub fork): LIVE, keep

- Branches: only `main`, so here the plugin line is `main` (every other repo uses `plugin/*`). 7 of the commits are ours.
- CI: 2 of 2 green. Six upstream workflows (`build_{android,darwin,linux,web,win,win_msvc}.yml`) are still
  active. They have not fired, but they are dead weight. The workflow has no missing-bundle guard.
- Release: unstable build 2 (a64+x64), with `COPYING.onscripter`, `LICENSES.md` and `THIRD_PARTY.md` in the payload.
- Save: no `--save-dir`, so saves go beside the game (compliant). Controller: common ids (matches the host).
- Licence stance: "everything added here is part of that work under the same terms" (GPL-2+). Under the
  most-permissive rule, new wrapper files could be MIT. The same point applies to EasyRPG and mkxp-z,
  whose LICENSE files say "Enginehost's own changes ... are distributed under the same terms".
- The last entry in nscripter LOG.md still says "no bundle exists and none can" (key missing). That is outdated.
- Test games: **none in the library.**

### 3.8 enginehost-rpgmaker-mkxp-z-plugin (fork of mkxp-z, GPL-2.0): LIVE, keep

- Branches: `plugin/rgss-v1`, `dev` (upstream), `plugin-core` (09-06), `archive/vxace-rgss3-ruby31-2026-09-04`,
  `codex/plugin-core-restored-rgss-v1`, and upstream-era branches `release`, `autobuild` and
  `symphony-of-war` (2023, by Struma; upstream history, not ours).
- CI: 33 ok / 17 fail / 10 cancelled over the window (back to 09-01); 2 of 4 failed since 09-10; median 19 min, max 30.
- Release: unstable 158 only (**no testing**, F4). ABIs a64, v7a, x64.
- Save: RGSS saves beside the game (compliant per DECISIONS 2026-09-17).
- Controller: **F1**. It reads the map, but with the old ids, so every action becomes `KEYCODE_UNKNOWN`.
- Test games: MGQ Paradox (VX Ace) only; no XP or VX game.

### 3.9 enginehost-rpgmaker-easyrpg-plugin (fork of EasyRPG/Player, GPL-3.0): LIVE, keep

- Branches: `plugin/0.8.1.1`, `master`, upstream `0-x-y-stable`, `plugin-core` (09-09), archive, a codex
  restored branch. The upstream linter and stable-compilation workflows are disabled manually.
- CI: 30 ok / 9 fail / 3 cancelled; 1 of 4 failed since 09-10; median 14 min, max 28.
- Releases: unstable 59 (a64+x64); testing 53 (a64 only).
- Save: saves beside the game unless the `savePath` option says otherwise (compliant). The config goes to
  Android app-private storage (F6, minor).
- Controller: bypass engine; it reads no map (F8).
- Test games: only the EasyRPG TestGame 2000/2003 fixtures (in /root/re/testgames); no real 2000/2003 game.

### 3.10 enginehost-rpgmaker-mv-mz-plugin (own wrapper, MIT): LIVE, keep

- Branches: only `main`. CI 21 ok / 2 fail, about 1 min. Releases: unstable 6, testing 3. No native code.
- Save: browser storage bridged to the host save folder (compliant: browser storage is a system location).
- Controller: **F1** (common ids; the host sends `mvmz_*`).
- Test games: 5 (Happy NEET, JK, Cute Niece and luna_space_prison on MV; Magical Princess Lily on MZ).

### 3.11 enginehost-html-plugin (own wrapper, MIT): LIVE, keep

- Branches: `plugin/stable` (live), `main`, `plugin-core`. **`plugin-core` builds a different bundle,
  `dev.enginehost.twine.2_12.v1` (engine `twine`, pluginVersion 1.0.0), and it published
  `twine-2_12-v1-unstable` on 09-08** from Codex commit e1aca94. That bundle's `source.revision` names
  `plugin/2.0-2.12`, a branch that no longer exists. Recommend deleting that release and resetting plugin-core.
- CI: 28 ok / 7 fail / 2 cancelled; 2 of 2 green since 09-10. Releases: unstable 37, testing 32. No native code.
- Save: localStorage bridge to the host folder (compliant). Controller: common ids (matches the host).
- Test games: 4 (College Daze, Inheritance, Love and Corruption, Routes of Life).

### 3.12 enginehost-flash-air-plugin (fork of ruffle-rs/ruffle): LIVE, keep

- Branches: `plugin/ruffle-0.4.1`, `master` (upstream), `plugin-core`, and about 15 upstream dependabot,
  copilot and revert branches. Upstream workflows are still enabled: `release.yml` (daily, skipped),
  Crowdin (daily, skipped) and `test_extension_dockerfile.yml`, which **runs and succeeds** weekly and
  costs 15 min of our Actions time each time.
- CI: the plugin workflow is at 12 ok / 2 fail. Releases: unstable 31, testing 28. No native code.
- Save: SharedObjects go to the host folder (compliant). Controller: common ids.
- Licence: LICENSE.md, LICENSE_APACHE and LICENSE_MIT are in the payload. Good.
- Test games: Bunnycop (AIR) only; no plain SWF.

### 3.13 Legacy and related repositories

| repo | state | recommendation |
|---|---|---|
| enginehost-flash-air-wrapper-legacy | **empty** (size 0, created 08-29, no branches) | archive or delete |
| enginehost-plugin-buriko-legacy | **empty** (size 0, created 08-28) | archive or delete |
| enginehost-plugin-renpy | **empty** (size 0, created 08-28) | archive or delete |
| enginehost-kirikiri-wrapper-legacy | 12 commits, last 08-26; its description says it is superseded. It used to be bi0shacker001/enginehost-plugin-kirikiri, and the `/root/enginehost-plugin-kirikiri` clone still points at the old name (6 behind) | archive |
| enginehost-reports | live: issue templates and a weekly triage workflow (3 of 3 green); 1 open "Weekly triage" issue | keep |
| renpy-build (fork) | synced 08-27; no plugin workflow uses it (they download the official RAPT/SDK zips) | archive, or keep only as reference |
| rapt (fork) | 2021; the description already says "Archived:" but the repo is not archived on GitHub; unused | archive |
| python-for-android (fork) | 2021 (last real commit 2016); same situation | archive |
| VXAceTranslator (fork) | one commit of ours (08-31, "cross-platform UI foundation and mod stacks"); no plugin, host doc or brief references it | not an engine plugin; the owner decides |

---

## 4. Cross-repo and cross-line consistency problems

1. **Controller action ids** (F1): the host renamed them on 09-10. CMVS followed (it was written against
   the new table). KiriKiri, Flash, HTML and NScripter legitimately use the common set. RGSS, MV/MZ and
   CatSystem2 were never updated.
2. **Ren'Py loader fix**: it is now semantically present on all 11 lines (in two textual variants, because
   7.x structures `loader.py` differently), but only on unstable. No testing bundle has it (F2).
3. **Ren'Py dotfile project-settings fix** ("Build the Python 2 lines: ... dotfile project settings") exists
   on the 7.x lines. `plugin/8.0`'s first build died on exactly that (`.android.json`).
4. **Release-tag pinning** (Codex, 09-06..09-09) is on every line of every repo except all nine Godot lines (F7).
5. **The setup-android "withdrawn tools package" fix** (09-16 sweep) is present on every workflow that uses
   setup-android (all Godot lines, buriko, catsystem2, cmvs, flash-air, html, mv-mz, nscripter). OK.
6. **The "missing bundle is a failure" guard** is present on Ren'Py, Godot (except embedded-pack), KiriKiri,
   mkxp-z, buriko, catsystem2 and cmvs. flash-air, html and mv-mz have a weaker single check.
   **nscripter has none.**
7. **PLATFORMS reindex dispatch**: written on Ren'Py `plugin-core` (20296b03, 09-11) as "the template the
   other plugin repositories copy". It was copied to **no** line in any repo, including Ren'Py's own lines.
8. **`plugin-core` is stale everywhere** (last commits 09-06..09-11) while the lines moved on (the
   setup-android fix, the save policy, ABI work, restart support). In practice it is no longer the source of
   the wrapper changeset. The `codex/plugin-core-restored-*` replacements have waited for approval since about 09-09.
9. **Action versions drift**: checkout and setup-java are `@v5` on KiriKiri, EasyRPG, mkxp-z and Ren'Py and
   `@v4` elsewhere. Push triggers: catsystem2 and cmvs omit `plugin-core`; nscripter and mv-mz trigger on `main`.
10. **The `channel` dispatch input defaults to `stable`** on every workflow, and no job refuses stable for a
    0.x `pluginVersion`. One mis-click publishes a stable release, which contradicts "nothing to stable before 1.0".
11. **Godot Java/native version guard** is missing on 4.5 only (3.2).
12. **Licence declaration of our own code** differs between repos: MIT for the Ren'Py and Godot wrappers and
    for our own engines; "same terms as upstream" for NScripter, EasyRPG and mkxp-z; undeclared for KiriKiri
    and Buriko. Under the most-permissive rule, new wrapper files in GPL forks can be MIT.
13. **Leftover branches**: `promotion/build-43` (cmvs), `promotion/build-77` (kirikiri), `plugin/renpy8` and
    `plugin/renpy8.5.3` (renpy), `plugin/2.0` (catsystem2), `plugin/4.5-embedded-pack` (godot).
14. **Coordination claims that the assets contradict**: "rgss on testing" (no such release); "KiriKiri ships
    no x86_64" (unstable does); TEST-MATRIX's "CatSystem2 need 3" (the library has 4); nscripter LOG's
    "no bundle can exist" (build 2 is published).

---

## 5. CI health summary (last 60 plugin-workflow runs per repo)

| repo | window from | ok | fail | cancelled | fail rate | fail rate since 09-10 | median / max ok duration |
|---|---|---|---|---|---|---|---|
| renpy | 09-05 | 40 | 0 | 1 | 0% | 0% (8.0 failed today, after the window) | 3 / 22 min |
| godot | 09-17 | 38 | 15 | 5 | 28% | 28% | 7 / 25 min |
| kirikiri | 09-04 | 41 | 16 | 3 | 28% | **50%** | 4 / 18 min |
| cmvs | 09-01 | 56 | 2 | 2 | 3% | 6% | 2 / 9 min |
| catsystem2 | 09-04 | 58 | 0 | 2 | 0% | 0% | 1 / 8 min |
| buriko | 09-03 | 52 | 0 | 8 | 0% | 0% | 2 / 8 min |
| nscripter | 09-17 | 2 | 0 | 0 | 0% | 0% | 4 min |
| easyrpg | 08-29 | 30 | 9 | 3 | 23% | 25% | 14 / 28 min |
| mkxp-z | 09-01 | 33 | 17 | 10 | 34% | **50%** | 19 / 30 min |
| mv-mz | 08-28 | 21 | 2 | 0 | 9% | 0% | 1 / 8 min |
| html | 08-29 | 28 | 7 | 2 | 20% | 0% | 2 min |
| flash-air | 09-03 | 12 | 2 | 0 | 14% | 0% | 2 / 8 min |

Most repos have been idle since 09-16/17 (the CI sweep). Ren'Py and Godot are the only lines built this week.

---

## 6. AI attribution in commits (the owner forbids it)

No commit of ours carries a `Co-Authored-By` AI trailer, a "Generated with" line or a robot-emoji line
(searched all refs of all 21 repos). The trailers that exist belong to upstream: flash-air 9efe2e3792 (Claude,
ruffle upstream) and 53534613b1 (copilot-swe-agent, upstream); renpy-build a002cf3118, 3b8d8e1c67, 8926706db4
and 297d8ecc71 (Copilot, upstream).

Commits whose **author** is `Codex <codex@openai.com>` (all our history):

- buriko: 454e64b (plugin-core), 47d5e52 (plugin/0.0.1)
- catsystem2: c586c4f (plugin/0.1), 01a2634 (plugin-core, plugin/0.1)
- cmvs: 51dea2c (plugin/2.0-3.0), d76210d (plugin-core)
- flash-air: 2251ecad (plugin/ruffle-0.4.1), ea37225b (plugin-core)
- godot: 5d1b81d89 (plugin/4.7.1), 5fed3f932 (plugin-core)
- html: ec28a2b (plugin/stable), e5cff69 and e1aca94 (plugin-core)
- kirikiri: 8423cd3 (plugin/stable), a93623d (plugin-core)
- renpy: 1566e521 (7.3), a268f6b1 (7.4), 24d45ede (7.5), d16b59c2 (7.6), c8c2d490 (7.7), 52d5641d (7.8),
  99a8a584 (8.1), 8521c09e (8.2), b6132010 (8.3), 38e730d3 (8.4), 45f6ac67 (8.5), 6667d985 (plugin-core)
- easyrpg: b1f7738 and e551a91 (plugin/0.8.1.1), a39aa5d (plugin-core)
- mkxp-z: e39eb4e (plugin/rgss-v1), f9dd735 (plugin-core)
- mv-mz: 8aabf64 (main)

Hygiene: some commits are authored as `root <root@CGSLS-WC-000-01.localdomain>` (the workstation hostname)
in godot (11), mkxp-z (17), cmvs, kirikiri (2), easyrpg and mv-mz. Others use ad-hoc identities
(`catsystem2@localhost`, `cmvs@local`, `rpgmaker owner`, `enginehost automation`, `Droidtop`).

---

## 7. Recommended order of work

1. Port the three plugins to their engine ids (mkxp-z `rgss_*`, mv-mz `mvmz_*`, catsystem2 `cs2_*`), then run
   a pad check on the rig for each. Until that is done, none of the three should stay on testing or be promoted to it.
2. Re-promote Ren'Py testing from today's unstable, line by line, as dq-renpy-20 passes. Publish mkxp-z
   testing after step 1 and dq-rpgmaker-30.
3. Re-promote KiriKiri, CMVS, CatSystem2 and EasyRPG testing from their two-ABI unstable builds (after the
   pending x86_64 rig runs), so that no testing bundle is single-ABI.
4. Add the payload licence texts for Buriko and KiriKiri, and correct KiriKiri's declared licence.
5. Save-policy follow-ups: CatSystem2 (check where the original saves and apply the 09-17 rule), KiriKiri's
   personalPath/appDataPath, EasyRPG's config path, Godot 32-bit (build those ABIs or drop them).
6. Godot: add tag pinning and the 4.5 version guard to the lines, and archive `plugin/4.5-embedded-pack`.
7. Housekeeping: delete the twine release; add a pre-1.0 stable guard to the dispatch input; archive the three
   empty legacy repos, kirikiri-wrapper-legacy, rapt, python-for-android and the leftover branches.
