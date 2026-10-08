#!/usr/bin/env bash
# Signs and packages an already-built plugin bundle (build.sh output:
# manifest.json + plugin.py) into
# droidtop.sample-py-statustile.droidplugin.tar.xz. The ONLY script in
# this folder that touches the plugin origin private key -- CI runs it with
# the PLUGIN_SIGNING_KEY repo secret, locally run it on droidtop-dev
# (/root/coordination/keys/droidtop-plugins/); the key is never committed.
# PLUGIN_SIGNING_KEY is the PATH of the PEM; an optional PLUGIN_SIGNING_CERT
# file is packaged as origin.cert next to manifest.sig. Identical
# pattern to plugin-sample-statustile/sign.sh, just a different payload
# file.

set -euo pipefail
cd "$(dirname "$0")"

: "${PLUGIN_SIGNING_KEY:?set to the droidtop plugin origin EC private key PEM}"
: "${BUNDLE_DIR:=build}"

test -f "$BUNDLE_DIR/manifest.json" || { echo "missing $BUNDLE_DIR/manifest.json -- run build.sh first" >&2; exit 1; }
test -f "$BUNDLE_DIR/plugin.py" || { echo "missing $BUNDLE_DIR/plugin.py -- run build.sh first" >&2; exit 1; }

openssl dgst -sha256 -sign "$PLUGIN_SIGNING_KEY" "$BUNDLE_DIR/manifest.json" | base64 -w0 > "$BUNDLE_DIR/manifest.sig"

# Optional certificate of this plugin key (signed by droidtop's plugin master).
rm -f "$BUNDLE_DIR/origin.cert"
CERT_FILE=
if [ -n "${PLUGIN_SIGNING_CERT:-}" ]; then
  test -f "$PLUGIN_SIGNING_CERT" || { echo "PLUGIN_SIGNING_CERT is not a file" >&2; exit 1; }
  cp "$PLUGIN_SIGNING_CERT" "$BUNDLE_DIR/origin.cert"
  CERT_FILE=origin.cert
fi

tar -C "$BUNDLE_DIR" --sort=name -cf - manifest.json manifest.sig $CERT_FILE plugin.py | xz -9e > droidtop.sample-py-statustile.droidplugin.tar.xz

echo "Signed droidtop.sample-py-statustile.droidplugin.tar.xz"
sha256sum droidtop.sample-py-statustile.droidplugin.tar.xz
