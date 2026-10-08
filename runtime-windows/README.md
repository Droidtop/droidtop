# runtime-windows

droidtop's Windows runtime (docs/SPEC.md 5b, 9): Wine run as the app's own
uid through `/system/bin/linker64` against an ImageFs root in app storage,
no root and no container.

- `src/main/java/com/winlator/` is Winlator's runtime as GameNative ships it
  (GPL-3.0; Winlator itself LGPL-2.1), lifted from droidtop's fork
  Droidtop/gamenative-tux with its package and headers unchanged. The few
  edits are marked `droidtop:`.
- `src/main/kotlin/dev/droidtop/runtime/windows/` holds droidtop's own code
  (`WineEngine`, `WineXSession`, `WineGameActivity`, `WineOptions`,
  `PrefixSettings`, `PcLibrary`, ...) and, in its sub-packages, the
  GameNative-authored files the runtime uses (`utils`, `data`, `ui`), lifted
  the same way.
- `native/` builds the x86_64 half of the native libraries; arm64 uses
  GameNative's prebuilt set.

Wine, Proton, DXVK, VKD3D, FEXCore, Box64 and the drivers are not part of
this module: they are other projects' builds, downloaded on demand
(`WineComponents`).
