# shell-gamepad

Optional, toggleable gamepad-console-style launcher shell — a UX layer some
users will want and others definitely won't, so it must stay an alternative
to `shell-default`, never the required path. Reads from the exact same
`dev.droidtop.library.Library` as `shell-default`.

## Design references (UX only, not fork targets)

- **Playnite** — fullscreen controller-navigable mode, plugin-per-source
  library aggregation. `library-core` already follows this model.
- **AOSP Leanback** / **FLauncher** (Flutter) — best available OSS bases for
  the D-pad-navigable shell chrome itself; neither has library-scanning
  logic, which is fine since that's `library-core`'s job, not this module's.
- Daijishō, Beacon, ES-DE Android — closed-source or paid; useful only as
  interaction-pattern references, not forkable.

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
