# Keyboard UI static review, 2026-11-27

Source review only. No local build, device, live UI or accessibility service test was run. CI is the compilation/unit verification route; narrow-screen, large-font, pad and screen-reader behavior still need a rig check.

## Ownership and current evidence

- Droidtop `input-keyboard` is the shared source, a normal module rather than a submodule. `KeyboardPanel.kt` hosts the same keyboard for the companion display; `LatinIME` is the system IME. Do not equate their focus/window ownership.
- Public `mytop-project/hacker-keyboard-reborn` main `4d320705a4930a09b62ba51e964fd51f3803940c` adds `app/LauncherActivity.kt`, which explicitly opens shared `Main`. Its `keyboard/UPSTREAM` pins Droidtop `5cbdf7dbcb9ffc231338c0e01da8d3e6ffa70b54`. README requires shared fixes in Droidtop first, then `scripts/sync-keyboard.sh`; the mirror was not edited here.
- `mytop-project/mytop-keyboard` is a separate scaffold, not the source consumed by either inspected app. Tracker #344's old body predates the standalone repository; owner comments confirm embedded and standalone, and GPL-3.0. No published standalone release was listed in this audit.
- Standalone Build run 45295829621 failed downloading Robolectric's Maven runtime dependency, after compilation. The same head also has a successful earlier build. This failure is not evidence of a source regression.

## Verified setup defects and this batch

`Main.java` entered `res/layout/main.xml`: fixed-width-by-content table rows held paired buttons and an editor; the long instructions were below the table in a non-scrolling root. A narrow or short screen could hide controls/instructions. The replacement is a scroll view and full-width single-column actions with at least 48dp height. The test editor previously contained real text `Test`; it now has a hint and starts empty.

`Main.java`'s dictionary action searched the original author's Play Store publisher, while `LatinIMESettings` already owns `DictionaryRow.open`, file picking and import feedback. The named Dictionaries action now routes into that existing manager through settings. Only a fresh settings launch opens the dialog automatically; recreation does not open a second copy. The English instructions describe the current route.

`KeyboardSetupTest` checks constrained-width layout/scroll overflow, an empty editor with a hint, and the explicit dictionary destination. These tests cannot prove on-device rendering or focus usability.

## Counterevidence and remaining review

Tracker #772/#903 are broad historical inventories, not proof every item is still broken. Current source already has `KeyboardProMode` with a simple settings allowlist, preserved Pro options, presets and reset confirmation; `ClipboardVault`/`ClipboardHistoryStore`; and dedicated clipboard, theme and tool-panel tests. Do not duplicate those mechanisms. Clipboard cryptography/concurrency, glide scheduling, Japanese input engines, dual-screen focus, tools navigation, localization coverage and all settings subtrees have not been deeply traced in this pass.

The companion's nonfocusable tool views may protect the active editor; they are not evidence by themselves that pad navigation is absent. Runtime input/focus inspection is still required. The legacy setup prose and some translated More instructions remain candidates for a broader content/localization pass; this batch changes the shared English route description only. Standalone mirror synchronization and its own CI must follow the Droidtop landing.
