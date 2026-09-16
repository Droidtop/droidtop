# BlueStacks Android 9 (API 28), 2026-09-16 -- remaining-bugs pass

Rig: BlueStacks 127.0.0.1:5555, 1920x1080, games root `/mnt/windows/BstSharedFolder`
(the user's whole library). Driven by adb only.

- `01`-`03` fresh-install onboarding on build 531 after `pm clear`: welcome, home-screen
  choice, and the storage rationale (API 28 uses the ordinary permission prompt, not
  MANAGE_ALL_FILES -- the minSdk fix holds).
- `04-onboarding-step7of7-bug.png` -- the defect: pressing Allow jumped the progress from
  "Step 4 of 8" to "Step 7 of 7" without changing the screen.
- `05-onboarding-finished-no-games-bug.png` -- what it cost: the next press landed on
  "You're set up" with the games-folder, theme and default-mode steps never shown, so a
  fresh install finished with no games root.
- `06`/`07` the same flow on build 533 with the fix: "Step 5 of 8 -- Game folders", and the
  typed-path root being added.
- `10-pc-surface-537.png` -- the PC surface on build 537: one list, no `.STFOLDER`,
  no `EA`/`EPIC`/`GAMEPASS`/`UBISOFT`/`BATTLE.NET`/`ROMS` wrapper entries, and the games
  that were hidden inside them (SimCity) present. 171 games, 151 with a detected engine.
- `11-pc-detail-537.png` -- an engine game's detail opening into enginehost without a crash.
- `12-system-view-537.png` -- the system view: PC and All Games, no store card.
