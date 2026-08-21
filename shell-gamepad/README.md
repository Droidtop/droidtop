# shell-gamepad

Optional, toggleable gamepad-console-style launcher shell — a UX layer some
users will want and others definitely won't, so it must stay an alternative
to `shell-default`, never the required path. Reads from the exact same
`dev.droidtop.library.Library` as `shell-default`.

## Design references

- **Playnite** — fullscreen controller-navigable mode, plugin-per-source
  library aggregation. `library-core` already follows this model.
- **AOSP Leanback** — D-pad-navigable UI toolkit; not a fork target, no
  library-scanning logic (that's `library-core`'s job).
- **[Titanius Launcher](https://github.com/dsolonenko/titanius-launcher)**
  (MIT) — the closest *conceptual* match to this module: a controller-
  first, launcher-not-emulator frontend that reads EmulationStation-format
  `gamelist.xml` (the same canonical format droidtop adopted, docs/SPEC.md
  §7b) and hands off to external emulators rather than emulating itself.
  Not a fork target as-is — it's Flutter/Dart, a different runtime than
  this module's native Kotlin/Compose stack, which the rest of droidtop is
  built on top-to-bottom (Wine/Box64, DroidSpaces, wlroots at the native
  layer) — but its scope/architecture is worth studying closely, and a
  genuine contribution-upstream target if droidtop ever needs
  EmulationStation-format import logic Titanius already has working.
- **[Lemuroid](https://github.com/Swordfish90/Lemuroid)** (GPL-3.0) /
  **[Emulair](https://github.com/EmulairEmulator/Emulair-Android)**
  (a Compose rewrite based on Lemuroid) — real, actively-maintained
  Kotlin+Compose Android projects, tech-stack-compatible with this module
  unlike Titanius. Different product shape from droidtop's (both *do*
  emulation themselves via libretro cores, where droidtop deliberately
  doesn't — see docs/SPEC.md §7d), so not a direct fork target for the
  shell as a whole, but their non-emulation-specific library/browsing UI
  code is worth a closer look before writing more of this module from
  scratch.
- ES-DE Android — see the "UI Inspiration" credit in the root README for
  additional references this shell's design draws from.

## Status

**Real, not a stub**: a full-screen, D-pad-navigable library shell
(`GamepadShell.kt`) reading from the same `Library` as `:shell-default`.
Card focus/navigation uses Compose's own default D-pad key handling (every
`focusable()` node responds to DPAD key events without custom code); the
controller's face button (A / cross) is handled explicitly since it's a
distinct keycode from DPAD_CENTER on most Android gamepad mappings.

**Known gap, not silent**: analog left-stick-as-navigation isn't
implemented. A physical D-pad/hat press arrives as an ordinary `KeyEvent`
(handled above), but a thumbstick reports raw `MotionEvent` axis data that
Compose doesn't translate into focus movement on its own — doing that well
needs deadzone/repeat-rate tuning against a real controller, which wasn't
available while writing this.
