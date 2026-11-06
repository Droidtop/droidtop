# Termux terminal-emulator and terminal-view

Upstream: https://github.com/termux/termux-app, tag `v0.118.3`, commit
`5b657c6adf4304e5198951ce815fe0205dcac29c` (2025-05-23). Fetched with
`git clone --depth 1 --branch v0.118.3` on 2026-11-06.

## What is here

Only the two library modules, and of each only `src/main`, unchanged:

- `terminal-emulator/src/main`: the VT parser and screen buffer (`com.termux.terminal`), `TerminalSession`, and its
  JNI pseudo-terminal (`jni/termux.c`, `JNI.java`: `/dev/ptmx`, fork, `setsid`, the slave as the controlling
  terminal, `TIOCSWINSZ`).
- `terminal-view/src/main`: `TerminalView` (`com.termux.view`), which draws a session and takes the soft keyboard's
  and a hardware keyboard's input.

Not taken: upstream's unit tests, its `build.gradle` files (they read Termux's root project and publish to Maven) and
the rest of the Termux app.

droidtop's own files beside them: `*/build.gradle.kts` and `*/droidtop/AndroidManifest.xml` (the namespace moves
from the manifest to the build file, which this Android Gradle plugin requires), this README and `LICENSE`.
droidtop hooks these modules from its own code (`app/.../terminal/`); it does not edit them.

## Licence

`UPSTREAM-LICENSE.md` is upstream's `LICENSE.md` as it came: termux-app is GPLv3-only, except the `terminal-view` and
`terminal-emulator` libraries, which carry Terminal Emulator for Android code and are released under the Apache
License 2.0. The source files carry no licence headers of their own (checked file by file at this commit); the
exception in that file is their licence. `LICENSE` is the Apache License 2.0 text. `WcWidth.java` follows
https://github.com/jquast/wcwidth (MIT), as its own comment says. Apache-2.0 code may be combined into droidtop's
GPL-3.0 whole; the copyright stays with Termux's and Terminal Emulator for Android's authors.
