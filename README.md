# droidtop

droidtop is a launcher and frontend for Android handhelds. It is one app
with three modes: a gaming shell themed with real ES-DE themes, a standard
Android home screen, and a desktop mode for Linux and Windows software. All
three read the same game library, which gathers games from many sources
into one list: Android apps, console ROMs, PC games from the stores and from
loose folders, and engine games (Ren'Py, RPG Maker, KiriKiri and others).
Engine games run through Enginehost, a separate app that droidtop launches
by intent.

The design is written down in [docs/SPEC.md](docs/SPEC.md). It is long, so
use its headings. It records design decisions; this README describes what is
in the repository.

## Modes

droidtop is one app in one process. Each mode can be switched off, and a
switched-off mode runs no code: `ModeStartup` in `:app` starts only the
pieces listed for the enabled modes (`ModePiece` / `ModeGate` in
`:runtime-common`). The design is in SPEC §2c "Modes and what each
contributes".

- **Gaming.** The gamepad-driven gaming shell (`:shell-gamepad`). It renders
  ES-DE themes with droidtop's own ES-DE theme engine (parser in
  `:runtime-common`, renderer in `:shell-gamepad`). It also holds the Quick
  Menu, the PC surface that lists every PC and engine game, the scrapers,
  and the companion screen on a second display. Two themes are bundled:
  DEcaffe, the default on a landscape screen, and Slate, the default on a
  portrait screen. See SPEC §7f and §7i.
- **Launcher.** A standard Android home screen, labelled "Android" in the
  mode switcher. It is a fork of Murine Launcher (itself a fork of AOSP
  Launcher3), built in as `:shell-default`. The mode is on when droidtop
  holds the HOME role.
- **Desktop.** The desktop mode (`:shell-desktop`): a taskbar and start menu
  around the output of the primary Linux container's compositor, together
  with container management. Its container backends are only partly built:
  the rooted DroidSpaces backend runs, and the non-root proot backend is a
  stub (see the module map below). Window streaming is done by the separate
  windowcast project, not by code in this repository (SPEC §7a).

Some things do not belong to any one mode, so they keep running whichever
modes are on: the library, deciding how each game is launched
(`GameLaunchActivity`), settings, the self-updater and crash reporting.
Turning Gaming off does not stop games from launching.

## Where games come from

All sources feed the same `LibraryEntry` model in `:library-core`:

- **Android apps** (`NativeAppProvider`).
- **Console ROMs** (`ConsoleRomProvider`). They are read from ES-DE's
  `<root>/<system>/<rom>` folder layout. Each ROM is launched in an
  installed emulator, chosen from the player database. That database is a
  pinned snapshot of the `vendor/droidtop-platforms` submodule, and it can
  refresh itself from that repository.
- **PC games** (`:runtime-windows`, `PcLibrary` / `PcGameProvider`). Steam,
  GOG, Epic, Amazon and loose game folders are found through the vendored
  GameNative tree. The games run under Wine and Box64.
- **Engine games** (`GameEngineDetector`). The engine is detected from the
  game folder, and the game is launched through Enginehost's
  `dev.enginehost.LAUNCH` intent (`EngineHost.kt`). Kirikiroid2 and EasyRPG
  Player are also launch options for the engines they cover.

Metadata and artwork follow ES-DE's own media layout. Scraper clients live
in `library-core/.../scraper` (ScreenScraper, TheGamesDB, IGDB, Lutris and
libretro thumbnails). SPEC §7 still calls scraping a standing gap.

## Module map

`settings.gradle.kts` is the full list, with a comment on each module.
These are droidtop's own modules:

| Module | What it is |
|---|---|
| `:app` | The application: modes, the shared core, onboarding, settings, the in-app updater and `DesktopSessionService`. It depends on the other modules. |
| `:runtime-common` | Types every other module shares: containers, display outputs, modes, settings catalogs and the ES-DE theme parser. It depends on no other module in this repository. |
| `:library-core` | The library: providers, scanning, the index, launch resolution, the platform and player databases, engine detection, Enginehost integration and scrapers. |
| `:shell-gamepad` | The Gaming shell: ES-DE theme renderer, gamepad navigation, Quick Menu, PC surface and in-shell settings. |
| `:shell-default` | The Launcher shell, forked from Murine Launcher / AOSP Launcher3. Its sub-libraries are included as separate Gradle projects: `:IconLoader`, `:Animation`, `:Shared`, `:WMShared`, `:msdl`, `:flags`, `:HiddenApi`, `:systemUIPluginCore` and the `:SettingsLib-*` modules. |
| `:shell-desktop` | The Desktop shell: taskbar, start menu, system tray, the Terminal entry, and the viewport that shows the compositor output. |
| `:display` | What the secondary display shows in every mode: the one `SECONDARY_HOME` activity, plus a registry the modes use to register their content (SPEC §4c). |
| `:host-bridge` | Native Wayland client plus JNI. It copies frames from the compositor inside the container onto Android surfaces, injects input into the container, and bridges the clipboard. It builds and links; its README says it has not been run against a live compositor. |
| `:input-seat` | One input seat that combines touch, gamepad-as-pointer and the second-screen trackpad, and passes the input to `:host-bridge`. |
| `:input-keyboard` | Hacker's Keyboard, forked in as a real Android IME. It is offered as an optional step in onboarding. |
| `:runtime-windows` | Wine/Box64 compiled from the whole vendored GameNative tree (`vendor/gamenative`). It also provides the PC store library and launching PC games. |
| `:runtime-linux-root` | Rooted Linux containers. It drives the `droidspaces` binary built from `vendor/droidspaces`, and pulls OCI images with `crane`. Root is required. |
| `:runtime-linux-noroot` | The proot container backend for devices without root (`ProotRuntime`), running Termux's proot from `vendor/proot` out of `nativeLibraryDir`. |

These directories are in the repository but are not Gradle modules:

- `build-scripts/` holds `build-vendor-deps.sh`, which cross-compiles
  libffi, libwayland-client, droidspaces and crane for both ABIs. It also
  holds the CI checks: the XML-comment check and the minSdk class-load
  gate.
- `docs/` holds `SPEC.md`.
- `reference/` holds reference screenshots.

## Vendored sources

These are Git submodules under `vendor/`, as listed in `.gitmodules`:

| Path | Upstream | Used for |
|---|---|---|
| `vendor/gamenative` | [bi0shacker001/gamenative-tux](https://github.com/bi0shacker001/gamenative-tux), a fork of [GameNative](https://github.com/utkarshdalal/GameNative) | Everything `:runtime-windows` compiles |
| `vendor/droidspaces` | [ravindu644/Droidspaces-OSS](https://github.com/ravindu644/Droidspaces-OSS) | The rooted container runtime |
| `vendor/sway` | [swaywm/sway](https://github.com/swaywm/sway) | The compositor inside the container |
| `vendor/wlroots` | [wlroots](https://gitlab.freedesktop.org/wlroots/wlroots) | Protocol XML only; the library itself is not built for Android |
| `vendor/wayland`, `vendor/wayland-protocols` | [wayland](https://gitlab.freedesktop.org/wayland/wayland), [wayland-protocols](https://gitlab.freedesktop.org/wayland/wayland-protocols) | libwayland-client, protocol headers and code generation |
| `vendor/libffi` | [libffi/libffi](https://github.com/libffi/libffi) | A runtime dependency of libwayland-client |
| `vendor/go-containerregistry` | [google/go-containerregistry](https://github.com/google/go-containerregistry) | `crane`, for pulling OCI images |
| `vendor/droidtop-platforms` | [Droidtop/droidtop-platforms](https://github.com/Droidtop/droidtop-platforms) | The platform, player and BIOS databases bundled into `:library-core` |

The submodules are not configured as shallow. To clone everything:

```bash
git clone --recurse-submodules https://github.com/Droidtop/droidtop.git
```

`:library-core` needs `vendor/droidtop-platforms` checked out, and its
bundled-database copy task fails without it.

## Builds and releases

Builds are made by CI. `.github/workflows/android-build.yml` runs on every
push to `main` and on every pull request. It builds the native vendor
dependencies, then `:app:assembleRelease` and `:app:assembleDebug`, and
uploads both APKs as a workflow artifact. After that it runs a gate on the
built dex to catch classes that cannot load on minSdk 26. Pushes that only
change `docs/**`, `README.md`, `NOTICE.md` or `LICENSE` skip the build.
`.github/workflows/android-checks.yml` runs lint (the minSdk API gate) and
the unit tests in a separate run on the same pushes, so the checks never
hold up the APK.

On a push to `main`, the build replaces the rolling GitHub release named
**`latest`**, which holds three files:

- `droidtop-latest.apk`, the release build. This is the one to install.
- `droidtop-latest-debug.apk`, the same code built debuggable, for
  inspecting droidtop with adb. It is several times slower to start and to
  navigate.
- `release-info.json`, which holds the version code, both APK names, their
  SHA-256 digests and the commit.

Both APKs are fat APKs for arm64-v8a and x86_64. Both are signed with the
same persistent key, so a newer build installs over an older one.
`versionCode` is the number of commits reachable from the built commit, and
`versionName` is `0.1.0-dev-<versionCode>`.

The `testing` and `stable` releases are published by running the
**Release promote** workflow by hand. It builds nothing: it publishes the
APKs that a commit's own **Android build** run made (by default, the commit
`latest` carries now), and only if that commit's build and checks both
passed.

## In-app updater

The updater's code is in `app/src/main/kotlin/dev/droidtop/app/update/`, and
its settings are under **Settings > Software updates**. It downloads the
chosen channel's `release-info.json` without sending anything about the
device. When `versionCode` is newer, it downloads the APK, checks it against
the published SHA-256, and hands it to Android's PackageInstaller. Android
shows its own confirmation, except where Android itself allows a silent
update.

- **Build channel:** Unstable (the `latest` release, every push to `main`;
  this is the default), Testing (`testing`) or Stable (`stable`). A channel
  that has not been published yet reports that there is nothing there.
- **Install debug builds:** follows the channel's debug APK instead of the
  release APK. This is off by default, and the setting warns about the
  slowdown.
- **Schedule:** never, daily (the default), weekly or monthly, with an
  option to check only on unmetered networks. There is also a manual check.

To check and install now from a computer, whatever the schedule says:

    adb shell am broadcast -a dev.droidtop.UPDATE_NOW -n dev.droidtop.app/.UpdateNowReceiver

The same forced pass is the "Check now" row in Settings > Software updates.
`UpdateNowReceiver` requires `android.permission.DUMP`, which only the adb
shell, root and the system hold. The outcome is logged under
`DroidtopUpdateNow`, and you can read it with
`adb logcat -s DroidtopUpdateNow`.

## Licence

According to [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md), droidtop is
distributed under the **GNU General Public License v3.0**, because it
combines GPL-3.0 sources (GameNative, DroidSpaces, and four ROM-detection
files plus the ROM database from Lemuroid). The other licences involved are:

- MIT: sway, wlroots, wayland and libffi.
- Apache-2.0: go-containerregistry and Hacker's Keyboard.
- LGPL-2.1: Winlator, which reaches droidtop only through GameNative.

SPEC §8 adds that the Murine Launcher / AOSP Launcher3 fork is Apache-2.0.
NOTICE.md has the attributions.

The two bundled ES-DE themes are in
`shell-gamepad/src/main/assets/themes/`: DEcaffe (`decaffe-es-de`) and
Slate (`slate-es-de`). Both are **CC BY-NC-SA 4.0** and ship unmodified,
each with its own `LICENSE` and `CREDITS.md`. That licence forbids
commercial distribution, so a commercial build of droidtop would have to
remove or relicense them.

droidtop borrows ideas from ES-DE, Daijishō, Playnite and other projects. It
is not affiliated with or endorsed by any of them.
