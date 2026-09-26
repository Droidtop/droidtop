#!/usr/bin/env bash
# Signs and packages an already-built plugin bundle (build.sh's
# build/manifest.json + build/classes.jar -- either built locally or
# downloaded from the "sample-plugin" CI job's unsigned artifact) into
# droidtop.sample-statustile.droidplugin.tar.xz.
#
# The ONLY script in this folder that touches the plugin origin's private
# key. Run this on droidtop-dev only, where
# /root/coordination/keys/droidtop-plugins/droidtop-origin-private.pem
# lives; the key is never committed to this repo and never given to CI.

set -euo pipefail
cd "$(dirname "$0")"

: "${PLUGIN_SIGNING_KEY:?set to the droidtop plugin origin's EC private key PEM}"
: "${BUNDLE_DIR:=build}"

test -f "$BUNDLE_DIR/manifest.json" || { echo "missing $BUNDLE_DIR/manifest.json -- run build.sh, or copy a CI artifact's manifest.json into $BUNDLE_DIR/, first" >&2; exit 1; }
test -f "$BUNDLE_DIR/classes.jar" || { echo "missing $BUNDLE_DIR/classes.jar -- run build.sh, or copy a CI artifact's classes.jar into $BUNDLE_DIR/, first" >&2; exit 1; }

openssl dgst -sha256 -sign "$PLUGIN_SIGNING_KEY" "$BUNDLE_DIR/manifest.json" | base64 -w0 > "$BUNDLE_DIR/manifest.sig"

tar -C "$BUNDLE_DIR" --sort=name -cf - manifest.json manifest.sig classes.jar | xz -9e > droidtop.sample-statustile.droidplugin.tar.xz

echo "Signed droidtop.sample-statustile.droidplugin.tar.xz"
sha256sum droidtop.sample-statustile.droidplugin.tar.xz
