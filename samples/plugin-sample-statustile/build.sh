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
# build/manifest.json + build/classes.jar UNSIGNED). Signing is the separate sign.sh step: CI runs it with the
# PLUGIN_SIGNING_KEY repo secret, droidtop-dev runs it locally with the origin key.
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

kotlinc -cp "$PLUGIN_HOST_CLASSPATH:$ANDROID_JAR" -d build/classes $(find src -name '*.kt')

# The sample's native library, for both ABIs droidtop requires of a bundle
# that ships any (PluginBundleInstaller): ANDROID_NDK_HOME is the NDK CI
# installs for :plugin-host. API 26 is droidtop's minSdk.
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the Android NDK}"
CLANG_DIR="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
for pair in arm64-v8a:aarch64-linux-android26 x86_64:x86_64-linux-android26; do
  abi="${pair%%:*}"; target="${pair#*:}"
  mkdir -p "build/lib/$abi"
  "$CLANG_DIR/$target-clang" -shared -fPIC -O2 -Wl,-z,max-page-size=16384 -o "build/lib/$abi/libsamplegreeting.so" native/greeting.c
done

d8 --output build --lib "$ANDROID_JAR" \
  $(find build/classes -name '*.class')

# classes.jar is a zip containing classes.dex at its root -- what
# DexClassLoader (PluginRuntimeService) expects.
(cd build && zip -q classes.jar classes.dex)

CLASSES_SHA=$(sha256sum build/classes.jar | cut -d' ' -f1)

python3 - "$CLASSES_SHA" <<'PY'
import hashlib, json, sys
sha = sys.argv[1]
manifest = json.load(open("manifest.template.json"))
payload = [{"path": "classes.jar", "sha256": sha}]
for abi in ("arm64-v8a", "x86_64"):
    path = "lib/%s/libsamplegreeting.so" % abi
    payload.append({"path": path, "sha256": hashlib.sha256(open("build/" + path, "rb").read()).hexdigest()})
manifest["payload"] = payload
json.dump(manifest, open("build/manifest.json", "w"), indent=2, sort_keys=True)
PY

echo "Built build/classes.jar and build/manifest.json (unsigned)"
sha256sum build/classes.jar

if [ -n "${PLUGIN_SIGNING_KEY:-}" ]; then
  PLUGIN_SIGNING_KEY="$PLUGIN_SIGNING_KEY" ./sign.sh
else
  echo "PLUGIN_SIGNING_KEY not set -- stopping here, unsigned."
  echo "Run ./sign.sh with PLUGIN_SIGNING_KEY set (CI does this with the PLUGIN_SIGNING_KEY repo secret) to produce droidtop.sample-statustile.droidplugin.tar.xz."
fi
