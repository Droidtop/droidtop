#!/usr/bin/env bash
# Hashes plugin-sample-py-statustile's payload (src/plugin.py) into the
# exact shape PluginBundleInstaller.install() expects (docs/SPEC.md 12a),
# same split as plugin-sample-statustile/build.sh -- this script never
# touches the plugin origin's private key and is safe to run in CI (the
# "sample-plugin-python" job in .github/workflows/android-build.yml runs
# exactly this and uploads build/manifest.json + build/plugin.py
# UNSIGNED). Only droidtop-dev, which holds the private half at
# /root/coordination/keys/droidtop-plugins/droidtop-origin-private.pem,
# ever runs sign.sh.
#
# Unlike the native_bundle sample, this one needs no compiler at all --
# a python-kind plugin's payload IS its source (plugin.py), so "build" is
# just "hash the file and fill in the manifest".
set -euo pipefail
cd "$(dirname "$0")"

rm -rf build
mkdir -p build
cp src/plugin.py build/plugin.py

PLUGIN_SHA=$(sha256sum build/plugin.py | cut -d' ' -f1)

python3 - "$PLUGIN_SHA" <<'PY'
import json, sys
sha = sys.argv[1]
manifest = json.load(open("manifest.template.json"))
manifest["payload"] = [{"path": "plugin.py", "sha256": sha}]
json.dump(manifest, open("build/manifest.json", "w"), indent=2, sort_keys=True)
PY

echo "Built build/plugin.py and build/manifest.json (unsigned)"
sha256sum build/plugin.py

if [ -n "${PLUGIN_SIGNING_KEY:-}" ]; then
  PLUGIN_SIGNING_KEY="$PLUGIN_SIGNING_KEY" ./sign.sh
else
  echo "PLUGIN_SIGNING_KEY not set -- stopping here, unsigned."
  echo "Run ./sign.sh with PLUGIN_SIGNING_KEY set (droidtop-dev only; the key never leaves that host) to produce droidtop.sample-py-statustile.droidplugin.tar.xz."
fi
