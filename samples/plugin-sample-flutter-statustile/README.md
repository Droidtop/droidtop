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
## Contract 2 and views

The manifest (`manifest.template.json`) is `contractVersion: 2` with
`provides` for `ui.status_tile` (`state`) and `ui.settings` (`view`,
`greet`). The Dart entrypoint (`lib/main.dart`) keeps the readiness
handshake (`invokeMethod('ready')`) and responds to both the legacy
`invoke` method and the contract-2 `handle` method (`call.arguments`
is the envelope JSON). `handle` replies with the standard
`{"ok":true,"data":{...}}` or
`{"ok":false,"error":{"code":"UNSUPPORTED","message":"..."}}`
shape. The settings view returns a document (`view: 1`, `sections`,
`items`: `info`, `text`, `button`) and remembers the text field value
in memory; `greet` reads `args.values.name` (defaulting to `'there'`)
and returns a message reply.

- `ui.main` (docs/plugin-api.md 1.7): the manifest declares
  `{"point":"ui.main","entrypoint":"mainUi"}` and `lib/main.dart` has the
  matching `@pragma('vm:entry-point') void mainUi()` that calls `runApp`
  with a one-screen widget. droidtop shows "Open <plugin name>" on the
  plugin's page and runs that function full-screen in the plugin's process;
  Back returns to droidtop. Only `flutter_embed` plugins can declare it.

- `args.query == "force-crash"` calls Dart's `exit()` to kill
  `:pluginhost` outright -- the crash-containment test this sample needs
  that a caught Dart exception would NOT give (Flutter's own dispatcher
  turns an uncaught exception from a MethodChannel handler into an error
  *reply*, not a process crash; see `lib/main.dart`'s own comment).
- Channel method `onEvent` (optional): receives payload
  `{"event": "<event.id>", "args": {...}}`; reply with the same
  `{"ok": true, ...}` / `{"ok": false, ...}` JSON shape. A plugin
  that subscribes to an event but has no `onEvent` handler must return
  nothing (the adapter treats the missing handler as success).

## Calling droidtop

Plugins call a host API on the same channel with method `hostCall`. Its
argument is JSON text containing `api`, `version`, `op` and an `args`
object; the method result is the broker's reply as JSON text. For example,
inside a Dart handler:

```dart
final replyJson = await _channel.invokeMethod<String>(
  'hostCall',
  jsonEncode({'api': 'host.info', 'version': 1, 'op': 'info', 'args': {}}),
);
final reply = jsonDecode(replyJson!) as Map<String, dynamic>;
```

The broker call runs off the main Looper, and its reply returns through
the original method result. A malformed request returns the broker reply
shape with error code `INVALID_ARGS`.
