#!/usr/bin/env bash
# Builds and signs droidtop.sample-statustile.droidplugin.tar.xz from this
# folder's source, in the exact shape PluginBundleInstaller.install()
# expects (docs/SPEC.md 12a). NOT run by droidtop's own Gradle build --
# this module is deliberately outside settings.gradle.kts, because a real
# plugin bundle is built and signed OUTSIDE droidtop's build graph, the
# same way a genuine third-party plugin author would.
#
# NOT executed as part of this change: producing classes.jar needs
# droidtop's :plugin-host module already compiled to a jar (for
# StatusTilePlugin.kt to compile against DroidtopPlugin/PluginContext/etc)
# plus kotlinc and d8 on PATH, none of which this session had available
# without a local Gradle build (which this project's rules forbid running
# locally anyway -- droidtop builds only happen in CI). Left as a real,
# concrete script rather than a stub so the next session (or a small CI
# job) can run it as-is.
#
# Prerequisites:
#   - kotlinc on PATH (any recent Kotlin compiler)
#   - d8 on PATH (ships in the Android SDK build-tools; also available
#     via `find $ANDROID_HOME/build-tools -name d8`)
#   - a compiled :plugin-host classes jar to compile against, e.g.
#     ./gradlew :plugin-host:assembleDebug in CI and unzip the resulting
#     classes.jar out of the AAR, OR point PLUGIN_HOST_CLASSPATH at one
#   - PLUGIN_SIGNING_KEY pointing at the real EC private key
#     (/root/coordination/keys/droidtop-plugins/droidtop-origin-private.pem
#     on droidtop-dev; never committed to this repo)

set -euo pipefail
cd "$(dirname "$0")"

: "${PLUGIN_HOST_CLASSPATH:?set to a jar/dir containing dev.droidtop.pluginhost.* compiled classes}"
: "${PLUGIN_SIGNING_KEY:?set to the droidtop plugin origin's EC private key PEM}"

rm -rf build
mkdir -p build/classes

kotlinc -cp "$PLUGIN_HOST_CLASSPATH" -d build/classes src/dev/droidtop/samples/statustile/StatusTilePlugin.kt

d8 --output build --lib "${ANDROID_JAR:?set ANDROID_JAR to android.jar for the target compileSdk}" \
  $(find build/classes -name '*.class')

# classes.jar is a zip containing classes.dex at its root -- what
# DexClassLoader (PluginRuntimeService) expects.
(cd build && zip -q classes.jar classes.dex)

CLASSES_SHA=$(sha256sum build/classes.jar | cut -d' ' -f1)

python3 - "$CLASSES_SHA" <<'PY'
import json, sys
sha = sys.argv[1]
manifest = json.load(open("manifest.template.json"))
manifest["payload"] = [{"path": "classes.jar", "sha256": sha}]
json.dump(manifest, open("build/manifest.json", "w"), indent=2, sort_keys=True)
PY

openssl dgst -sha256 -sign "$PLUGIN_SIGNING_KEY" build/manifest.json | base64 -w0 > build/manifest.sig

tar -C build --sort=name -cf - manifest.json manifest.sig classes.jar | xz -9e > droidtop.sample-statustile.droidplugin.tar.xz

echo "Built droidtop.sample-statustile.droidplugin.tar.xz"
sha256sum droidtop.sample-statustile.droidplugin.tar.xz
