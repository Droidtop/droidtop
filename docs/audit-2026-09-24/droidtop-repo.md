# droidtop: repository and delivery audit, 2026-09-24

Scope: git repo, build, CI, releases, docs, related org repos, secrets. This does not cover the app's runtime design.
Authoritative clone: `/root/droidtop` (remote `github`, main at `83975530` by the end of the audit; the audit mostly read `0e7ddde5`).
Method: read-only. One unintended action: a single `git fetch -q github` in `/root/droidtop`. It changed nothing, because the remote-tracking refs were already current.

Severity: H/M/L. Size: S (<1h), M (hours to a day), L (multi-day).

---

## Top findings (ranked)

| # | Sev | Finding | Size |
|---|-----|---------|------|
| 1 | H | 19 commits on `main` carry `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`, which the owner forbids | S guard / M rewrite |
| 2 | H | The release keystore and a plaintext password file sit side by side, world-readable (644), on two machines (WSL and the Windows NTFS mirror). No documented offline backup. | S |
| 3 | H | `main` has no branch protection and no rulesets. Any push goes straight to users as a signed APK through the `latest` channel. | S |
| 4 | H | The `testing` and `stable` channels exist in the app but have never been published. When they are, "promotion" rebuilds the current main instead of promoting a tested artifact. Switching from Unstable to a lower versionCode is impossible because Android refuses downgrades. | M |
| 5 | M | CI has no Gradle dependency or build cache. Every run is a cold ~16 min Gradle pass, and android-checks repeats the same cold compile for lint (~15 min) | S |
| 6 | M | The two workflows share ~130 lines of copy-pasted setup. Actions are pinned to Node-20 majors (deprecation warnings already appear). | S |
| 7 | M | Build failure rate is 13/60 (22%) and most failures are compile errors. The "no local builds" policy turns CI into the compiler, which makes fix-up commits churn | M |
| 8 | M | README is badly stale ("Pre-implementation... TODO() stubs"). It lists deleted modules and vendors and omits real ones | S |
| 9 | M | docs/SPEC.md is 5,925 lines / 358 KB, has no TOC, and its sections are out of order (4a-4c before 4, two "6c", 7h/7j/7k/7m after 12). Its §10/§10a/§11 are stale, and it carries status narrative | L |
| 10 | M | `gamenative-tux` upstream sync has failed 10/10 days (merge conflicts). The fork is 62 commits behind upstream. | M |
| 11 | M | versionCode is `github.run_number` of `android-build.yml`. Renaming or recreating that workflow resets it to 1, which breaks updates for every install | S |
| 12 | M | Dependencies are outdated or mixed: Compose BOM 2024.09.00, activity-compose 1.9.2 next to activity 1.13.0-alpha01, lifecycle 2.8.4 next to 2.9.1, media3 1.4.1, and several alphas. A SNAPSHOT dependency (javasteam) makes builds non-reproducible | M |
| 13 | M | The APK is 148 MB (release) / 167 MB (debug). The bulk is dex (~52 MB, no R8), 6+ Box64 versions (33 MB), FEXCore (20 MB), two themes (~20 MB) and x86_64 payloads | L |
| 14 | M | The `latest` publish deletes and then recreates the release, so it is not atomic. A failed create leaves no `latest` | S |
| 15 | L | 25 stale remote branches, all already merged. A droidtop worktree lives inside `/mnt/g/dev/enginehost/.codex-wt-scan` | S |
| 16 | L | The Windows mirror `G:\dev\and-pc` is ~300 commits behind, has a stale submodule set, and holds junk files | S |
| 17 | L | Dead or stale files: `pc-helper/` (day-1 scaffold, never built, conflicts with windowcast), `build-scripts/ci-env.sh` (Gradle 8.9, not used by CI), four tracked `*.png~`, "round N" comments in build.gradle.kts | S |
| 18 | L | `droidtop-platforms` and `droidtop-theme-patches` have no LICENSE. `theme-patches` is an empty scaffold | S |
| 19 | L | The Sentry/Bugsink SDK ships with an empty DSN, so it adds weight and reports nothing | S |

---

## 1. Git hygiene

**Size.** The pack is 215 MB, `.git/objects` is 254 MB, and `.git/modules` takes 1.9 GB (submodule clones). GitHub reports 224,619 KB. Across history, the largest blobs are:
- `library-core/src/main/assets/libretro-db.sqlite` (12.8 MB)
- decaffe theme SVG/PNG (up to 3.6 MB each)
- the removed `art-book-next-es-de` theme artwork, still in history at ~0.9 MB per system PNG

No APK, AAB, keystore or `.so` file was ever committed. The only jars in history are `gradle-wrapper.jar`, `input-keyboard/libs/voiceimeutils.jar` and `runtime-windows/src/main/lib/perfsdk-v1.0.0.jar`.
- L: history keeps the deleted theme images. A rewrite is not worth it unless clone size starts to hurt.

**Tracked files that should not be (L, S).** Theme editor backups are tracked:
- `shell-gamepad/src/main/assets/themes/decaffe-es-de/assets/{Empty.png~, badges/control.png~, images/pxR.png~ (2.5 MB), images/pxl.png~}`

The APK already excludes them through `ignoreAssetsPattern "!*~"`, but they still bloat the repo. The theme is meant to be an "unmodified copy", which argues for keeping them. That is a judgement call.

**.gitignore (L, S).** It covers build/, .gradle/, .kotlin/, local.properties, *.apk/*.aab, .signing/, jniLibs, assets/bin and shell-default/prebuilts. Missing entries:
- `.tmp-apk/` (it exists in both clones, and in the mirror it holds a 166 MB APK plus screenshots)
- `*~`, `*.orig`
- `scripts/` (untracked in the mirror; either track it or ignore it)

**Branches (L, S).**
- All 25 remote branches on `github` are fully merged into main and can be deleted:
  - emufix-verify, pc57-verify, wip/portrait-verify, updates-and-releases, update-schedule, launch-title, verify-wine-seam, review/doc-corrections
  - feature/* ×15
  - fix/game-root-tiering
- Of the 17 local branches, all but `codex/application-scoped-rescan` are merged. That branch is 1 commit ahead (2026-09-14) and checked out as a worktree at `/mnt/g/dev/enginehost/.codex-wt-scan`, which is a droidtop worktree inside the enginehost directory.
- The WSL clone's `origin` remote points at the stale Windows mirror, which made the clone show `main...origin/main [ahead 577]`. That is noise.
- Fix: delete the merged branches on `github`, prune the local ones, land or drop the codex branch and remove its worktree, and drop or re-point the `origin` remote.

**Submodules.** There are 9, all pinned to commits: gamenative (the gamenative-tux fork), droidspaces, wlroots, sway, go-containerregistry, wayland-protocols, wayland, libffi, droidtop-platforms.
- sway `1.11-rc2-160` and wlroots `0.20.0-rc4-134` are untagged master commits. That is fine for protocol XML, but no person-readable doc records the versions.
- L, S: `.gitmodules` still points gamenative at `https://github.com/bi0shacker001/gamenative-tux.git`. The repo now lives at `Droidtop/gamenative-tux`, and only a GitHub redirect keeps the old URL working.
- L, S: `.gitmodules` has no `shallow = true`, although the README says "Submodules are shallow (`--depth 1`)".
- M, S: `droidtop-platforms` is pinned 17 commits behind its head. Those are daily index commits, and the app refreshes at runtime from raw.githubusercontent. Still, nothing ever bumps the pin, so the seed data bundled in the APK ages silently. Fix: a scheduled workflow or Dependabot `gitsubmodule` updates.
- The vendor-deps cache key hashes `.git/modules/vendor/*/HEAD`, which correctly invalidates the cache when a submodule is bumped.

**Windows mirror `G:\dev\and-pc` (L, S).** It is at `97df1362` (2026-09-01): 374 commits against 676 on github/main, so ~300 behind. Problems:
- Stale submodule list: it still has `vendor/lemuroid` and `vendor/winlator-upstream`, and 9 submodules show as modified.
- Untracked `vendor/mbedtls`, `vendor/moonlight-common-c`, `scripts/` and `.tmp-apk/` (with a 166 MB APK).
- `build/` and `local.properties`.
- A detached `.claude/worktrees/xenodochial-brahmagupta-9258e7` worktree and a `claude/...` branch.
- Two junk files at the root, from a quoting accident: one literally named `"` and one named `\357\200\242` (U+F022, Windows' mangled `"`).
- A second copy of `.signing/` with the keystore (see §8).

Fix: re-sync the mirror from `github` (and delete the junk) or retire it, so no one reads 3-week-old code as current.

## 2. Commit history quality (last 300 on main, 2026-09-01..09-24)

- Messages are consistently good: imperative, descriptive subjects averaging 60 characters. 266/300 have explanatory bodies. No reverts, no WIP/fixup/squash commits, no "round N" subjects. 22 merge commits.
- Author identity is slightly inconsistent: 298 commits use `bi0shacker001 <bioshacker001@gmail.com>`, 1 uses `jon <...>` and 1 uses the noreply address. L.
- **H: LLM attribution.** 19 commits carry the trailer `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`, and all of them are reachable from `main`:
  - `e2bf21cc` 2026-09-11 Extend the vendored-tree lint baseline to shell-default's AOSP sub-libraries
  - `27d866c4` 2026-08-21 Repo cleanup: remove dead manifest inputs, relocate unbuilt reference code
  - `08b35876` 2026-08-21 Fix the Sentry auto-init crash for real -- it was in the wrong manifest
  - `5fb58266` 2026-08-21 Enable Gradle parallel execution and build caching
  - `37ecc31b` 2026-08-21 Fix app crash-looping at startup: disable Sentry's manifest auto-init
  - `077cd820` 2026-08-21 Wire crash reporting to droidtop's own Bugsink instance via Sentry SDK
  - `894c9d62` 2026-08-21 Fix real on-device crash: requestFocus() on an unattached FocusRequester
  - `478ae422` 2026-08-21 Wire real dual-screen presentation; add Kirikiri detection and multi-path launch model
  - `c6991939` 2026-08-21 Add detail screen, filter chips, JoiPlay wiring; scrub external attributions
  - `dde38183` 2026-08-21 Add a persistent controller button-hint footer to GamepadShell
  - `4f7e7423` 2026-08-21 Add DualScreenCoordinator; restructure GamepadShell into Games/Apps/Settings
  - `0f928483` 2026-08-21 Add architecture facts from decompiling Daijishou/iiSU release APKs
  - `711ad06c` 2026-08-21 Document UI/launch-mechanism research from Daijishou/iiSU under §7
  - `9f93aa93` 2026-08-21 Add ContainerRuntime.exec, prefer-native-Linux-depot selection, JoiPlay
  - `052a173d` 2026-08-21 Populate the image catalog live via crane, research-back the seed list
  - `fb0e5f04` 2026-08-21 Add real JVM unit tests for the image catalog's JSON parsing
  - `f8a43093` 2026-08-21 Wire the image catalog into ContainerRuntime.createPrimary/createSibling
  - `23df816e` 2026-08-21 Spec and scaffold the recommended-image catalog
  - `db057da3` 2026-08-21 Wire DesktopSessionService to real container orchestration

  There are no "Generated with" or robot-emoji footers. The only "generated with" hit, in `369ba6ce`, is prose about BuildConfig. There is no attribution text in the tree.

  Removing the trailers means rewriting main from `db057da3^` onward (~600+ commits), then force-pushing and re-cloning or resetting every checkout. That is disruptive and it is the owner's call; the alternative is to accept them as history. Either way, add a guard: a `commit-msg` hook plus a CI step that fails on `Co-Authored-By:.*(anthropic|claude|openai)`. `e2bf21cc` (09-11) shows the rule was still being broken three weeks in.
- **Churn hotspots** (files touched in the last 300 commits): docs/SPEC.md 81, shell-gamepad GamepadShell.kt 50, EsDeThemeRenderer.kt 43, GameEngineDetector.kt 24, OnboardingActivity.kt 24, LibraryEntry.kt 20, android-build.yml 19, AppSettingsCatalogs.kt 17.
- **M: compile-fix churn.** Many one-line follow-ups fix a compile error the previous commit introduced:
  - `3014bd87` "nativeKeyEvent is KeyEvent's own property, not an import"
  - `480925e3` "Name Room's Transaction annotation the way this file already does"
  - `146f6413` "Fix GameRecordTest: File(d) doesn't exist..."
  - `48f92721` "Fix CI compile error: ImageShader takes no filterQuality here"

  This is the direct cost of CI being the only compiler (see §3). Options: a fast compile-only CI job that fails in a few minutes, or letting agents run a local compile-only check that never produces an installable artifact. That is a policy question for the owner.

## 3. CI

Two workflows, both on push to main, pull_request and workflow_dispatch. `paths-ignore` covers docs/**, README.md, NOTICE.md and LICENSE. Concurrency is per ref with cancel-in-progress.

**android-build.yml.** Steps: checkout with recursive submodules, depth 1 → XML comment dash check → JDK 17+21 → Go 1.25 → apt deps → SDK/NDK cache (1.07 GB entry) → vendor-deps cache (10 MB) → keystore decode → `./gradlew :app:assembleRelease :app:assembleDebug` → upload artifact → class-load API gate (dex scan) → publish channel release.
- Last 60 runs (09-11..09-22), all push/main: **31 success, 13 failure, 16 cancelled**.
- Successful runs take 9.4 / 13.9 / 28.1 min (min / median / max).
- Recent step timing: Gradle 925-990 s (was ~700 s before the debug APK was added back), checkout 53-60 s, apt 12 s, SDK cache restore 12 s.
- Failing steps: mostly the Gradle build (compile errors). The older lint and unit-test failures date from before those steps moved to the checks workflow. One failure was a bash syntax error in the publish step (`35792508591`, a half-rewritten notes line, fixed by `20b1c566`).

**android-checks.yml.** Same setup, then `:app:lintDebug` (NewApi/InlinedApi only, checkDependencies) plus unit tests for 8 modules, plus report uploads.
- Last 30 runs: **20 success, 5 failure, 5 cancelled**. Median success is 17.5 min: lint 892 s (a full compile), tests 39 s.

Findings:
- **M, S: no Gradle cache.** Neither workflow uses `gradle/actions/setup-gradle` or caches `~/.gradle`. `org.gradle.caching=true` in gradle.properties does nothing on fresh runners. So every run re-downloads dependencies and recompiles ~40 modules, and the checks run compiles the whole thing a second time in parallel. Fix: add `gradle/actions/setup-gradle@v4` to both workflows (dependency cache plus configuration/build cache, written only from main). Expect warm runs to cut the ~16 min Gradle step substantially.
- **M, S: duplicated setup.** About 130 lines (JDK, Go, apt, SDK install, vendor-deps build and their long comments) are copied between the two files. Fix: a local composite action `.github/actions/setup-droidtop`.
- **L, S: installs that are only needed on a cache miss.** Go 1.25 and the apt toolchain are installed every run but only needed on a vendor-deps cache miss. Gate both on `steps.deps-cache.outputs.cache-hit != 'true'` (move the cache lookup earlier).
- **L, M: heavy checkout.** Recursive checkout of all 9 submodules takes 55-73 s. gamenative alone is ~800 MB of worktree, including `lsfg-vk-android/thirdparty/pe-parse/tests/assets/corkami-poc-dataset` (93 MB of test PE files) and `app/src/legacy/assets` (465 MB). A blob filter or sparse checkout would help.
- **L, S: action versions.** actions/checkout@v4, cache@v4, setup-java@v4 and upload-artifact@v4 are Node-20 majors, and runners already warn that Node 20 is deprecated (seen in the gamenative-tux logs). Bump them.
- **M, S: the publish is not atomic.** `gh release delete` followed by `gh release create` leaves a window with no release, and update checks during the window fail silently. If the create fails after the delete (an asset upload error, the API), `latest` stays missing until the next green push. Fix: create or upload into a new release, then retarget, or `gh release upload --clobber` plus `gh release edit --notes`.
- **L, S: wrong comment.** The step comment says "The repo is private", but `Droidtop/droidtop` is PUBLIC.
- **The `pull_request` triggers are unused.** No PR runs appear in the sample, because everything is pushed straight to main. That ties to H3.
- **What a failure blocks:**
  - A red android-build blocks that commit's APK and channel publish.
  - A red android-checks blocks nothing, by design.
  - A failing class-load gate still uploads the workflow artifact but skips the publish.
  - Nothing gates merging.

## 4. Build configuration

- Versions: Gradle 9.3.1 (wrapper, sha256 validated), AGP 8.13.1, Kotlin 2.2.21, KSP 2.3.3, Java 17 target (21 needed by shell-default toolchains), NDK 27.0.12077973.
- SDK levels: compileSdk 36, **targetSdk 34**, minSdk 26.
- **M, M: dependency drift.**
  - `composeBom = "2024.09.00"` is two years old, while the same catalog pins `material3Version = "1.4.0"` and `compose = "1.9.3"`.
  - `androidx-activity-compose` is hardcoded at 1.9.2 while `activity = "1.13.0-alpha01"`.
  - lifecycle is hardcoded at 2.8.4 in app/build.gradle.kts while the catalog's `lifecycle = "2.9.1"`.
  - media3 is 1.4.1, okhttp 4.12.0, room 2.7.2.
  - Pre-release pins: material 1.14.0-alpha09, activity 1.13.0-alpha01, tracing 2.0.0-alpha01, collection-ktx 1.6.0-beta01.
  - Three version sources: the `libs` catalog, the `gn` catalog from vendor/gamenative, and inline strings.
  - Repositories include JitPack and the Sonatype snapshots repo, for `javasteam = "1.8.0.1-26-SNAPSHOT"`. A snapshot means the same commit can build different bytes on different days.

  Fix: move the inline versions into the catalog, bump the Compose BOM, pin javasteam to a fixed artifact, and add Gradle dependency locking or verification.
- L: targetSdk 34 is fine for GitHub sideloading but blocks Play if that ever matters.
- **Build types.** `debug` and `release`. Release has `isMinifyEnabled = false` (known and documented). `useLegacyPackaging = true` is deliberate, for LD_PRELOAD of extracted libs. versionName is `0.1.0-dev-$run`.
- **Signing.** An env-driven signingConfig reads SIGNING_KEYSTORE_PATH, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS and SIGNING_KEY_PASSWORD. The keystore comes from the `SIGNING_KEYSTORE_BASE64` secret. Those four names are the only repo secrets. Without secrets, builds fall back to the debug key. Sound.
- **Lint.** `app/lint-baseline.xml` has 120 issues: 103 in shell-default/wm_shared, 10 in vendor/gamenative, 3 in shared, 3 in msdllib. The policy is well reasoned. L, M: only NewApi and InlinedApi are checked, so droidtop's own modules get no correctness or security lint. Fix: a separate, non-blocking full lint on the droidtop-written modules.
- **Tests.** 8 modules have JVM tests. Weak spots: input-keyboard (~18.4k LOC) has 1 test file and runtime-windows has 3. shell-default carries 479 upstream test files that are never compiled or run.
- **APK size (M, L).** `latest` assets: `droidtop-latest.apk` 147,961,403 B and `droidtop-latest-debug.apk` 166,660,148 B. Composition, measured on the newest local APK (`/root/apk537/droidtop-debug-apk/app-debug.apk`, 166 MB, 2026-09-16), in compressed MB:

  | Component | Size |
  |---|---|
  | dex | 51.9 (raw 145) |
  | `assets/box86_64` (box64 0.3.4/0.3.6/0.3.8/0.3.8-bionic/0.4.0-bionic/0.4.2-bionic..., ~4 MB each) | 33.1 |
  | `assets/fexcore` | 19.6 |
  | decaffe theme | 15.0 |
  | `assets/bin` (crane arm64 4.8 + crane x86_64 5.3 + droidspaces) | 10.5 |
  | resources.arsc | 7.9 |
  | lib/arm64-v8a | 6.8 |
  | wowbox64 | 5.3 |
  | slate theme | 4.6 |
  | libretro-db.sqlite | 3.9 |
  | lib/x86_64 | 2.5 |

  Fixes, in order of payoff:
  1. R8 (L).
  2. Bundle only the Box64/FEX versions the runtime actually defaults to, and download the rest on demand (M).
  3. Per-ABI APKs, or drop x86_64 from the release APK (S-M).
  4. Compress or fetch libretro-db (S).
- **L, S: stale bits.**
  - `build-scripts/ci-env.sh` still exports Gradle 8.9 and `/opt` paths and claims CI sources it. Nothing references it.
  - gradle.properties has a commented-out `android.ndkVersion`.
  - Comments in `build.gradle.kts` say "round 6 of the full-gamenative compile" and "Rounds 9/13", which conflicts with the no-round-labels rule.
- Configuration cache is off, which is documented as deliberate.

## 5. Releases and update channels

- Releases on GitHub:
  - `latest`: published 2026-09-22T23:30Z from `0e7ddde5`, versionCode 568.
  - `build-toolchains`: a pre-release holding musl cross toolchains of 108 MB and 115 MB, dated 2026-08-20.
  - **No `testing` and no `stable` release exists.**
- `release-info.json` on latest has `formatVersion 1, versionCode 568, versionName "0.1.0-dev-568", apkName, apkSha256, debugApkName, debugApkSha256, commit`. That matches what `AppSelfUpdate.fetch()` requires: formatVersion == 1, a 64-hex digest, and a fallback to the release APK when there is no debug key.
- The updater is `app/src/main/kotlin/dev/droidtop/app/update/AppSelfUpdate.kt` plus `UpdateNow.kt`. It fetches `https://github.com/Droidtop/droidtop/releases/download/<tag>/release-info.json`, compares versionCode, downloads, checks the SHA-256 and commits a PackageInstaller session (silent on API 31+ when it is eligible). Frequency can be Off, Daily, Weekly or Monthly. There is a channel choice and a debug toggle.

Gaps:
- **H, M: channel semantics are broken by construction.**
  - Promoting to testing or stable (workflow_dispatch) **rebuilds current main** with a fresh run_number. It does not promote the artifact that was tested, so "stable" is an untested binary with a higher versionCode than the `latest` build it came from.
  - A device on Unstable that switches to Stable will normally see a *lower* versionCode. The updater says "already current", and Android would refuse the downgrade anyway.
  - The settings UI offers two channels that 404 today.

  Fix: a promote workflow that copies an existing run's APKs and release-info.json into `testing`/`stable` unchanged. Also document that moving to a slower channel only takes effect once that channel overtakes the installed build, or design versionCode so the channels interleave sensibly.
- **M, S: versionCode source.** It is `github.run_number` of this workflow file. Renaming or recreating `android-build.yml` resets it to 1, below every install, and updates stop silently. Fix: a floor (`max(run_number + OFFSET, …)`) or derive it from `git rev-list --count`.
- M: publishing is non-atomic (see §3).
- L: the release notes say "see docs/SPEC.md for current status", but SPEC is not a status ledger by the owner's rule. The README still describes only "the rolling latest release".
- L: the digest in release-info comes from the same GitHub release as the APK. It detects corruption, not compromise; signing-key continuity is the real protection. State that in SPEC 10b.
- L: debug and release share applicationId and versionCode, so flipping the debug toggle does nothing until the next build.

## 6. Documentation

- **README.md (M, S): largely wrong.**
  - "Status: Pre-implementation... `TODO()` stubs, not a working build yet" is false.
  - The Layout section lists the deleted `runtime-remote-stream/`, and omits `display/`, `shell-desktop/`, `input-keyboard/` and `reference/`.
  - The vendored table lists `moonlight-common-c` and `mbedtls`, which are not submodules. It omits `droidtop-platforms` and names utkarshdalal/GameNative instead of the Droidtop/gamenative-tux fork.
  - "Building the native dependencies" mentions `:runtime-remote-stream`.
  - "Bundled theme" covers only DEcaffe, although Slate also ships. NOTICE.md lists both.
  - The DEcaffe URL says Weestuarty, NOTICE says `github.com/DEcaffe/decaffe-es-de`. They disagree.
  - "Submodules are shallow" is false.
  - "Updating a device build now" covers only `latest`.
- **docs/SPEC.md (M, L).** 5,925 lines, 358 KB, touched by 81 of the last 300 commits. Problems:
  - No TOC.
  - Numbering is out of order: 4a, 4b and 4c come before §4 and 4d after it. There are two different `6c` sections (second-screen input at l.1719, clipboard at l.1847). 6a comes after 6c. 7e2b comes after 7e4. 7h, 7m, 7j and 7k sit after §12.
  - 34 headings carry "(directed YYYY-MM-DD)" and 108 dates appear in total. Subsections like "What the code actually does today", "The blocker that makes all of it inert right now", "Dead weight to remove" and "Order of work" are status and plan content, which conflicts with the "SPEC carries no status ledgers" rule.
  - §10 "Suggested build order" is still the day-1 prototype plan ("runtime-common interfaces (already scaffolded)").
  - §10a says Gradle 8.9 (the wrapper is 9.3.1) and "uploading a debug APK artifact" (it is now release plus debug), and describes local builds, which contradicts the CI-only policy.
  - §11 "Open risks" dates from the scaffold.
  - §9 and settings.gradle.kts say shell-gamepad is "~8,000 lines". It is ~18,300 Kotlin/Java lines.
  - It still mentions JoiPlay 22 times, lemuroid 5 and runtime-remote-stream 4. These need a pass against the current no-JoiPlay-in-launch-loop rule.

  Fix: a clean hierarchical renumbering with a generated TOC, possibly split per area with SPEC.md as the index. Move dated narrative out, and refresh §9, §10, §10a and §11.
- Module READMEs exist for 12 modules. `display/` and `input-keyboard/` have none, and `app/README.md` is 8 lines.
- **Missing for a new contributor (M, M):**
  - CONTRIBUTING.md: how a build is obtained given the CI-only rule, the branch/PR flow, the (good) commit-message conventions, the XML double-dash rule, how to bump a submodule, and how to install a channel build.
  - CODEOWNERS.
  - A changelog or per-build release notes.
  - Issue templates (issues are enabled, 0 open).
  - A note on who holds the signing key.

## 7. Related org repos

| Repo | What | Health | How droidtop consumes it |
|---|---|---|---|
| droidtop-platforms | engines/platforms/players/BIOS database tree plus generators | Healthy: daily "Plugins index" commits, CI (generated-files, plugins-index) 10/10 green. **No LICENSE** | Submodule `c74eba36`, 17 commits behind head. Copied into assets by library-core/build.gradle.kts, refreshed at runtime from `raw.githubusercontent.com/droidtop/droidtop-platforms/main`. Sensible, but the pin is never auto-bumped |
| gamenative-tux (fork of utkarshdalal/GameNative) | Wine/Box64 runtime, compiled whole into :runtime-windows | **"Sync upstream" failed 10/10 days (09-14..09-23)**: modify/delete conflicts on 3 upstream workflows plus content conflicts in PluviaApp.kt, EpicAppScreen.kt, SettingsGroupInterface.kt. 29 ahead / 62 behind upstream. push-build-check last green 09-11. 1.37 GB | Pinned at `fac0a168`, its current head (good). URL still points at `bi0shacker001/` |
| windowcast | streaming protocol + SDK (THE streaming system) | Last commit 09-02, CI 5/5 green, GPL-3.0 | Not vendored. Referenced only in comments and SPEC §7a |
| droidtop-theme-patches | per-system theme overlay fragments | Scaffold only: 3 commits on 08-28, including a revert of AI-generated content. No CI, **no LICENSE** | Not consumed by code, only mentioned in NOTICE.md |
| proton-wine-tux | GameNative's proton-wine fork | Last push 08-31 | Not referenced by droidtop |
| enginehost + ~15 plugin repos | separate app | Out of scope | Runtime intent integration only |

Fixes:
- M, M: resolve the gamenative-tux upstream conflicts, or disable the daily sync until someone owns it. Right now it fails every day and nobody acts on it.
- L, S: add LICENSE files to droidtop-platforms (the data derives from ES-DE, Batocera and libretro, so the license must be compatible) and to theme-patches.
- L, S: update the gamenative URL in .gitmodules.

## 8. Security

- **History scan: clean.** Across all refs I grepped for AWS/GitHub/Google/Slack token patterns, PEM private keys, and `password|secret|token|api_key = "..."` literals. Nothing found. No keystore, .jks, .p12, .pem, .env or local.properties file was ever committed.
- **ScreenScraper credentials (L).** `library-core/src/main/kotlin/dev/droidtop/library/scraper/ScreenScraperDevCredentials.kt` holds droidtop's ScreenScraper devid and devpassword, XOR-scrambled against a key in the same file. This copies ES-DE and is documented as anti-grep obfuscation, not secrecy. Every revision in history (72446589, 4cb4964c, 1af361eb, 9664b70b) uses int arrays, never plain literals. Accepted risk. Rotating the credentials needs a release.
- `DebugCredentials.kt` (retired in 017aae08) never contained values.
- **H, S: signing key at rest.** `/root/droidtop/.signing/` holds `droidtop-release.jks` and `droidtop-release.secrets`, which contains STORE_PASS, KEY_PASS and ALIAS in plaintext. Both are mode 644. The directory also holds unrelated build scripts and a 49 KB `deploy.log`. An identical copy sits on Windows NTFS at `G:\dev\and-pc\.signing\`.

  Both copies are gitignored and were never committed. But the passwords sit next to the key, there are two unmanaged copies, and I found no documented offline backup. This one key is what every user install is pinned to, so losing it forces every user to uninstall. Fix:
  1. Keep one encrypted copy, for example GPG-encrypted like `/root/.gnupg/enginehost-signing`, with mode 600.
  2. Keep an offline backup.
  3. Delete the Windows copy.
  4. Move the helper scripts out of `.signing/`.
- **H, S: no branch protection on main.** A mistaken or compromised push publishes a signed APK to every Unstable device in ~16 min. The build workflow requests `contents: write` at workflow level, although the repo default is read. Fix:
  - Protect main (at minimum block force-push and deletion, and ideally require android-build).
  - Scope `contents: write` to a separate publish job.
  - Optionally put that job behind an environment.
- Fork PRs are safe: `pull_request` (not `_target`) gets no secrets, and the keystore step no-ops without them.
- **L, S: empty crash-reporting DSN.** The Sentry SDK is linked with an empty `BUGSINK_DSN` (`shell-default/src/dev/droidtop/shell/standard/CrashReporting.kt:27`). It reports nothing and ships `libsentry.so` for both ABIs. Either set the DSN or drop the SDK.
