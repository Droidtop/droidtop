# input-seat

One logical input seat, fed from every physical source this device might
have: touch, gamepad-as-pointer, the second-screen trackpad/keyboard, or a
lapdock's physical peripherals. Normalizes all of it and hands events to
`host-bridge` for injection into the primary container's compositor as a
single virtual pointer + keyboard.

Reference implementations worth porting logic from (not forking outright):

- **Moonlight Android** (`AbsoluteTouchContext` / `RelativeTouchContext`) —
  the touch-to-cursor interaction model. Primary screen = absolute position,
  second-screen trackpad = relative deltas, same split Moonlight already
  uses for direct-touch vs. trackpad-style control.
- **KDE Connect Android**'s Remote Input plugin — reference for keyboard
  event forwarding.

## Known gap being addressed here, not inherited

Winlator/GameNative have historically weak native/Bluetooth mouse pointer
capture (see Winlator issue #1555). Don't assume any of their input code is
a safe starting point for the trackpad path — budget real design/testing
time here.

## Status

Stub — depends on `host-bridge`'s native input-injection path landing first.
