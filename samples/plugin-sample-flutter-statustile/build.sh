#!/usr/bin/env bash
# Builds samples/plugin-sample-flutter-statustile's payload (docs/SPEC.md
# 12a, flutter_embed kind), same split as the other two samples' build.sh:
# this script never touches the plugin origin's private key and is safe
# to run in CI (the "sample-plugin-flutter" job in
# .github/workflows/android-build.yml runs exactly this and uploads
# build/manifest.json + build/lib + build/flutter_assets UNSIGNED). Only
# droidtop-dev, which holds the private half at
# /root/coordination/keys/droidtop-plugins/droidtop-origin-private.pem,
# ever runs sign.sh.
#
# Unlike the native_bundle/python samples, this one's real source
# (pubspec.yaml, lib/main.dart) is not itself a buildable Flutter project
# -- it is missing the android/ host scaffold `flutter create` normally
# generates, deliberately not committed here (it is boilerplate this
# script regenerates fresh every run, the same reasoning droidtop's own
# Gradle wrapper etc. use for generated build output). build.sh scaffolds
# a throwaway project under build/scaffold, overlays this folder's real
# pubspec.yaml/lib/ on top of it, and runs a real `flutter build apk`.
#
# Prerequisites:
#   - flutter on PATH, pinned to EXACTLY the version whose
#     bin/internal/engine.version matches
#     plugin-host/src/main/assets/flutter-runtimes.json's "version" --
#     confirmed 2026-09-26: Flutter 3.47.5 (flutter/flutter commit
#     6a19cca56475dbfba1478ee68d7bd0c2ef891da1) has engine.version
#     af7e796e161ae0bb1ff0758c71a7105418bd9ded, the exact hash both
#     FlutterRuntimeManager and plugin-host/build.gradle.kts pin. A
#     different Flutter version will build a libapp.so
#     PluginRuntimeService.loadFlutterPlugin refuses at load (its
#     runtimeVersion won't match the installed runtime) -- this is
#     deliberate, not a bug to work around.
#
# Optionally, for a one-shot local build+sign (droidtop-dev only): also set
# PLUGIN_SIGNING_KEY and this script calls sign.sh itself at the end.

set -euo pipefail
cd "$(dirname "$0")"

RUNTIME_VERSION="af7e796e161ae0bb1ff0758c71a7105418bd9ded"

command -v flutter >/dev/null || { echo "flutter not on PATH -- see this script's own header for the exact pinned version" >&2; exit 1; }

rm -rf build
mkdir -p build/scaffold build/payload

flutter create --platforms=android --org dev.droidtop.samples --project-name flutter_statustile build/scaffold >/dev/null
cp pubspec.yaml build/scaffold/pubspec.yaml
rm -rf build/scaffold/lib
cp -r lib build/scaffold/lib

(
  cd build/scaffold
  flutter pub get
  flutter build apk --release --target-platform android-arm64,android-x64
)

APK=build/scaffold/build/app/outputs/flutter-apk/app-release.apk
test -f "$APK" || { echo "flutter build apk didn't produce $APK" >&2; exit 1; }

mkdir -p build/payload/lib/arm64-v8a build/payload/lib/x86_64
unzip -p "$APK" lib/arm64-v8a/libapp.so > build/payload/lib/arm64-v8a/libapp.so
unzip -p "$APK" lib/x86_64/libapp.so > build/payload/lib/x86_64/libapp.so

# flutter_assets lives at "assets/flutter_assets/**" inside the APK; the
# plugin payload wants it at its own top-level "flutter_assets/**" (what
# FlutterDroidtopPlugin.loadAssetsIntoEngine reads from installDir).
EXTRACT_DIR="$(mktemp -d)"
unzip -q "$APK" 'assets/flutter_assets/*' -d "$EXTRACT_DIR"
cp -r "$EXTRACT_DIR/assets/flutter_assets" build/payload/flutter_assets
rm -rf "$EXTRACT_DIR"

python3 - "$RUNTIME_VERSION" <<'PY'
import hashlib, json, os, sys

runtime_version = sys.argv[1]
payload_root = "build/payload"
payload = []
for dirpath, _dirs, files in os.walk(payload_root):
    for name in sorted(files):
        full = os.path.join(dirpath, name)
        rel = os.path.relpath(full, payload_root).replace(os.sep, "/")
        sha = hashlib.sha256(open(full, "rb").read()).hexdigest()
        payload.append({"path": rel, "sha256": sha})
payload.sort(key=lambda e: e["path"])

manifest = json.load(open("manifest.template.json"))
manifest["runtimeVersion"] = runtime_version
manifest["payload"] = payload
json.dump(manifest, open("build/manifest.json", "w"), indent=2, sort_keys=True)
print(f"payload: {len(payload)} files")
PY

echo "Built build/payload/{lib,flutter_assets} and build/manifest.json (unsigned)"

if [ -n "${PLUGIN_SIGNING_KEY:-}" ]; then
  PLUGIN_SIGNING_KEY="$PLUGIN_SIGNING_KEY" ./sign.sh
else
  echo "PLUGIN_SIGNING_KEY not set -- stopping here, unsigned."
  echo "Run ./sign.sh with PLUGIN_SIGNING_KEY set (droidtop-dev only; the key never leaves that host) to produce droidtop.sample-flutter-statustile.droidplugin.tar.xz."
fi
