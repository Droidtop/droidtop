# Termux terminal-emulator and terminal-view

Upstream: https://github.com/termux/termux-app, tag `v0.118.3`, commit
`5b657c6adf4304e5198951ce815fe0205dcac29c` (2025-05-23), pinned as the plain submodule `vendor/termux-app`. droidtop
patches nothing in it, so it is upstream's own repository, not a fork (Droidtop/tracker#921).

## What is here

droidtop builds two of its library modules, and of each only `src/main`, unchanged:

- `terminal-emulator/src/main`: the VT parser and screen buffer (`com.termux.terminal`), `TerminalSession`, and its
  JNI pseudo-terminal (`jni/termux.c`, `JNI.java`: `/dev/ptmx`, fork, `setsid`, the slave as the controlling
  terminal, `TIOCSWINSZ`).
- `terminal-view/src/main`: `TerminalView` (`com.termux.view`), which draws a session and takes the soft keyboard's
  and a hardware keyboard's input.

Not built: upstream's unit tests, its `build.gradle` files (they read Termux's root project and publish to Maven) and
the rest of the Termux app.

droidtop's own files, here: `*/build.gradle.kts`, which point at the submodule's `src/main`, and
`*/droidtop/AndroidManifest.xml` (the namespace moves from the manifest to the build file, which this Android Gradle
plugin requires), and this README. droidtop hooks these modules from its own code (`app/.../terminal/`); it does not
edit them.

## Licence

Upstream's `LICENSE.md` (in the submodule): termux-app is GPLv3-only, except the `terminal-view` and
`terminal-emulator` libraries, which carry Terminal Emulator for Android code and are released under the Apache
License 2.0. The source files carry no licence headers of their own (checked file by file at this commit); the
exception in that file is their licence, the Apache License 2.0. `WcWidth.java` follows
https://github.com/jquast/wcwidth (MIT), as its own comment says. Apache-2.0 code may be combined into droidtop's
GPL-3.0 whole; the copyright stays with Termux's and Terminal Emulator for Android's authors.
