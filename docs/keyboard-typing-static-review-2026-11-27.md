# Shared keyboard typing UI: bounded source review

2026-11-27. No local build, app/rig interaction or device verification. Compilation/unit checks are CI only. This review traces the shared `input-keyboard` module used by Droidtop and mirrored by Hacker Keyboard Reborn; it does not edit the standalone mirror.

## Verified regression and fix

`PadKeys.onKeyDown` rejected every event when `KeyboardSwitcher.inputView.isShown` was false, before reaching its `ToolsDeck.panelOpen`/`onTools` branch. `ToolsDeck.toggle` calls `PanelMotion.hide(keyboard, INVISIBLE)` while its clipboard, emoji, macro or mode panel stays visible. `KeyboardMotion.kt` confirms that happens immediately with reduced motion or after the fade otherwise. Thus pad panel navigation stopped once the animation ended, despite explicit panel targets and Back handling existing.

The ownership gate now accepts either the shown grid or a shown deck with an open panel. A hidden whole deck/IME remains excluded, so it cannot take input from the game/app. `PadSurfaceVisibilityTest` uses attached Android Views under Robolectric to exercise visible grid → invisible grid/visible panel → hidden deck, including the no-panel case. This verifies the Android visibility policy, not the full IME/gamepad pipeline.

## Mapped and traced paths

| Surface | Source evidence | Review limit |
| --- | --- | --- |
| Handheld full/split/one-handed grids | `KeyboardForm.choose`, `handheld`, `layout`; `Keyboard.applyForm` updates key coordinates/widths/gaps; `KeyboardModes.formFor` applies the chosen orientation's form | Physical DPI fallback, touch hit areas, labels, thumb reach and rounding need device inspection. No second layout system was added. |
| Touch and language changes | `LatinKeyboardView.onTouchEvent` processes the space-bar language gesture; `onLongPress` invokes the keyboard switch action; base view retains key touch/long-press dispatch | Gesture conflicts, multi-touch, edge behavior, and actual language switching were not runtime tested. |
| Tools and modes | `ToolsDeck.buildStrip` has named switch/clipboard/emoji/macros/mode/incognito controls in a horizontal scroller; panels use the grid's height; modes scroll | Horizontal discovery, small-screen layout and touch/accessibility need rig checks. The pad regression above is distinct from those design questions. |
| Pad typing and tool navigation | `PadKeys` filters gamepad sources; spatial key focus; named face-key mapping; `onTools`/`onCandidates`, `ToolsDeck.padTargets` and clipboard menu/back behavior | The visibility gate was deeply traced and fixed. Other traversal geometry, repeat timing and all panel content were not exhaustively verified. |
| Settings/languages and keyboard switching | `LatinIME` options keys/settings launcher, switch-next/picker fallback; tool-strip switch and space long-press; `LatinIMESettings` simple/Pro tree and `InputLanguageSelection` | Settings content is reachable in source; that does not prove discoverability or a complete Japanese/Chinese workflow. Setup page is reviewed separately. |
| Companion display and focus | `SecondScreenKeyboard` captures active editor connection in IME input lifecycle, reports no-target conditions and reference-counts secondary keyboard suppression; hosted key events use the existing hardware-key model | This is deliberately different from the composing IME: no autocorrect/candidate-strip equivalence is claimed. Focus, show/hide, target recovery and switching screens need device evidence. |

## Request coverage versus regressions

Tracker #903/#772 contain a historical overhaul inventory. Current source already includes split/one-handed/floating/thumb/number-pad modes, simple/Pro settings, explicit pad tooling, clipboard vault, emoji/media panels, candidates/language engine seams and reduced-motion handling. Their presence does not establish task completion, and the old inventory is not proof those features are absent.

The initial pass established the pad ownership regression. The bounded accessibility follow-up below establishes a second source gap in Slab's individual key semantics. Remaining request coverage includes end-to-end Japanese/Chinese engine setup/candidates, layout/preset comprehension, narrow/large-text tool discovery, the legacy IME grid's accessibility, full touch/pad parity, dual-screen focus and external-editor recovery. Those remain unreviewed or runtime-unverified requirements.

## Slab key accessibility follow-up

The old `SlabView` painted individual keys and handled their touch hits, but exposed only the whole-view Keyboard description. It now uses AndroidX [ExploreByTouchHelper](https://developer.android.com/reference/androidx/customview/widget/ExploreByTouchHelper) to expose actual key bounds, full labels, button actions and checked modifier state. Action identities remain stable across layouts; removed letters cannot activate a symbol at their old coordinates. Shift offers a named Caps-lock action without the timed double press. The helper's default host focusability is explicitly reset: virtual accessibility focus does not replace the destination editor's real input focus. An already announced key follows pad movement as well as touch exploration.

Source consumers are `ui-shared/.../TextEntry.kt:SlabKeys`, `app/.../CompanionKeyboard.kt:CompanionDockedSlab` and `app/.../SecondScreenInput.kt:slab`. These hosts do not explicitly hide the native slab's descendants. The standalone Hacker Keyboard Reborn scaffold needs its normal later source/catalog synchronization; this branch does not claim that mirror is updated.

Authored Robolectric node-provider tests cover every live layout's identity uniqueness, activation/editor focus, modifiers, removed nodes, resizing and pad cursor alignment. They run only in CI; this report does not claim TalkBack or physical geometry verification. The separate `LatinKeyboardBaseView` legacy IME grid remains a plain custom View without a per-key virtual node or hover adapter in the reviewed source, so this change does not complete that grid's accessibility.

## Needs a rig check

With pad input, open each clipboard/emoji/macros/modes panel and wait until the keys finish fading. Move/activate controls and press Back; confirm events do not reach the editor/game. Repeat with reduced motion enabled. Hide the whole IME and verify gamepad input returns to the app. Also test full/split/one-handed forms, touch space-language swipe, long-press keyboard switch, and companion no-editor recovery before claiming those flows usable.
