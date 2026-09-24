# Working on droidtop

droidtop is an Android frontend for handhelds (tested hardware: Retroid Pocket 5): a gaming shell
themed with real ES-DE themes, a standard launcher, and a desktop mode, over one library of games
from many sources. Engine games run in the separate Enginehost app, which to droidtop is just
another emulator. Read `docs/SPEC.md` (use its headings) before changing behaviour.

## Rules (the owner's, not suggestions)

- **Mainline is `main`.** Work on a branch and open a pull request against `main`.
- **Builds are CI.** `.github/workflows/android-build.yml` builds the release and debug APKs;
  `android-checks.yml` runs lint and unit tests. A change is done when both are green on the PR.
  Do not claim something works because it compiles: say what was and was not verified.
- **No AI attribution anywhere.** No `Co-Authored-By`, no "Generated with", in commits or PRs.
  `.claude/settings.json` turns it off; `commit-hygiene.yml` fails any commit that carries it.
- **Commit messages** are plain prose saying why, citing evidence (file:line, a log line, a rig run).
- **Decisions go into `docs/SPEC.md`** in the same change. State comes from code, not from notes.
- **One mechanism per job.** Consolidate duplicates; delete the dead code you replace.
- **Non-root first.** Root is used only by the rooted desktop container stack
  (`runtime-linux-root`); handheld and launcher features never need it.
- **Never** build or publish container images (primary and sibling containers are off-the-shelf
  OCI images); never put remote-streaming code in droidtop (that is the separate `windowcast`
  project); never copy, move or import a user's game files.
- **Performance matters on a handheld.** No file, database or image work on the main thread; no
  per-game disk lookups in list rendering; nothing that grows with the square of the library.
- **XML:** never write ` -- ` inside an XML comment (it breaks the manifest merger).
- **Vendored trees** under `vendor/` are upstream code: hook or extend them, do not rewrite them.

## What you cannot do from a cloud session

You cannot reach the test device. When a change needs checking on a device, say so in the PR
description under a heading **Needs a rig check**, with exact steps (what to open, what to press,
what to look for). The coordinator runs it on the test rig and reports back on the PR.
