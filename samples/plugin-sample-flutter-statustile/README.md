# plugin-sample-flutter-statustile

The `flutter_embed` analogue of `plugin-sample-statustile`
(native_bundle) and `plugin-sample-py-statustile` (python) -- see
docs/SPEC.md 12a, "The flutter_embed kind". A harmless `status_tile`
plugin whose real code is `pubspec.yaml` + `lib/main.dart`: no Android
host project is committed here (`build.sh` scaffolds one fresh every run
with `flutter create`, the same "generated boilerplate, never committed"
treatment droidtop's own Gradle wrapper output gets).

Not part of droidtop's own Gradle build (`settings.gradle.kts` never
includes it) -- a real plugin author builds and signs outside droidtop's
build graph entirely, and this sample follows that same path so CI
exercises it honestly.

## Building

```
./build.sh
```

Needs `flutter` on `PATH`, pinned to the EXACT version this script's own
header names (its `bin/internal/engine.version` must match
`plugin-host/src/main/assets/flutter-runtimes.json`'s pinned version --
see `build.sh` for why a different Flutter version's build is refused at
load time, not a bug). Produces `build/manifest.json` and
`build/payload/{lib,flutter_assets}`, unsigned.

## Signing

Only on droidtop-dev, which holds the plugin origin's private key:

```
PLUGIN_SIGNING_KEY=/root/coordination/keys/droidtop-plugins/droidtop-origin-private.pem ./sign.sh
```

Produces `droidtop.sample-flutter-statustile.droidplugin.tar.xz`, ready
for droidtop's own "Add integration file" picker (Settings > Plugins).

## What it exercises

- Install, approve, "Call ... status tile" through the isolated
  `:pluginhost` process's real `FlutterEngine`, same end-to-end path the
  other two samples' README already documents for their own kind.
- `args.query == "force-crash"` calls Dart's `exit()` to kill
  `:pluginhost` outright -- the crash-containment test this sample needs
  that a caught Dart exception would NOT give (Flutter's own dispatcher
  turns an uncaught exception from a MethodChannel handler into an error
  *reply*, not a process crash; see `lib/main.dart`'s own comment).
