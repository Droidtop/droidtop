#!/usr/bin/env bash
# Compiles, dexes and hashes droidtop.sample-statustile's payload from this
# folder's source, in the exact shape PluginBundleInstaller.install()
# expects (docs/SPEC.md 12a). NOT run by droidtop's own Gradle build --
# this module is deliberately outside settings.gradle.kts, because a real
# plugin bundle is built and signed OUTSIDE droidtop's build graph, the
# same way a genuine third-party plugin author would.
#
# Split from signing (see sign.sh) on purpose: this script never touches
# the plugin origin's private key and is safe to run in CI (the
# "sample-plugin" job in .github/workflows/android-build.yml runs exactly
# this, building :plugin-host first for the classpath and uploading
# build/manifest.json + build/classes.jar UNSIGNED). Only droidtop-dev,
# which holds the private half at
# /root/coordination/keys/droidtop-plugins/droidtop-origin-private.pem,
# ever runs sign.sh.
#
# Prerequisites:
#   - kotlinc on PATH (any Kotlin compiler matching gradle/libs.versions.toml's
#     "kotlin" entry)
#   - d8 on PATH (ships in the Android SDK build-tools; also available
#     via `find $ANDROID_HOME/build-tools -name d8`)
#   - a compiled :plugin-host classes jar to compile against, e.g.
#     ./gradlew :plugin-host:assembleDebug and unzip the resulting
#     classes.jar out of the AAR, OR point PLUGIN_HOST_CLASSPATH at one
#   - ANDROID_JAR pointing at android.jar for :plugin-host's compileSdk
#
# Optionally, for a one-shot local build+sign (droidtop-dev only): also set
# PLUGIN_SIGNING_KEY and this script calls sign.sh itself at the end.

set -euo pipefail
cd "$(dirname "$0")"

: "${PLUGIN_HOST_CLASSPATH:?set to a jar/dir containing dev.droidtop.pluginhost.* compiled classes}"
: "${ANDROID_JAR:?set ANDROID_JAR to android.jar for the target compileSdk}"

rm -rf build
mkdir -p build/classes

kotlinc -cp "$PLUGIN_HOST_CLASSPATH" -d build/classes src/dev/droidtop/samples/statustile/StatusTilePlugin.kt

d8 --output build --lib "$ANDROID_JAR" \
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

echo "Built build/classes.jar and build/manifest.json (unsigned)"
sha256sum build/classes.jar

if [ -n "${PLUGIN_SIGNING_KEY:-}" ]; then
  PLUGIN_SIGNING_KEY="$PLUGIN_SIGNING_KEY" ./sign.sh
else
  echo "PLUGIN_SIGNING_KEY not set -- stopping here, unsigned."
  echo "Run ./sign.sh with PLUGIN_SIGNING_KEY set (droidtop-dev only; the key never leaves that host) to produce droidtop.sample-statustile.droidplugin.tar.xz."
fi
