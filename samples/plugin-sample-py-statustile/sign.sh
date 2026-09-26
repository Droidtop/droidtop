#!/usr/bin/env bash
# Signs and packages an already-built plugin bundle (build.sh output:
# manifest.json + plugin.py) into
# droidtop.sample-py-statustile.droidplugin.tar.xz. The ONLY script in
# this folder that touches the plugin origin private key -- run this on
# droidtop-dev only (/root/coordination/keys/droidtop-plugins/); the key
# is never committed to this repo and never given to CI. Identical
# pattern to plugin-sample-statustile/sign.sh, just a different payload
# file.

set -euo pipefail
cd "$(dirname "$0")"

: "${PLUGIN_SIGNING_KEY:?set to the droidtop plugin origin EC private key PEM}"
: "${BUNDLE_DIR:=build}"

test -f "$BUNDLE_DIR/manifest.json" || { echo "missing $BUNDLE_DIR/manifest.json -- run build.sh first" >&2; exit 1; }
test -f "$BUNDLE_DIR/plugin.py" || { echo "missing $BUNDLE_DIR/plugin.py -- run build.sh first" >&2; exit 1; }

openssl dgst -sha256 -sign "$PLUGIN_SIGNING_KEY" "$BUNDLE_DIR/manifest.json" | base64 -w0 > "$BUNDLE_DIR/manifest.sig"

tar -C "$BUNDLE_DIR" --sort=name -cf - manifest.json manifest.sig plugin.py | xz -9e > droidtop.sample-py-statustile.droidplugin.tar.xz

echo "Signed droidtop.sample-py-statustile.droidplugin.tar.xz"
sha256sum droidtop.sample-py-statustile.droidplugin.tar.xz
