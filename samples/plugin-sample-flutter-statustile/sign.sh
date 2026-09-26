#!/usr/bin/env bash
# Signs and packages an already-built plugin bundle (build.sh output:
# manifest.json + payload/{lib,flutter_assets} -- either built locally or
# downloaded from the sample-plugin-flutter CI job's unsigned artifact)
# into droidtop.sample-flutter-statustile.droidplugin.tar.xz.
#
# The ONLY script in this folder that touches the plugin origin private
# key. Run this on droidtop-dev only, where
# /root/coordination/keys/droidtop-plugins/droidtop-origin-private.pem
# lives; the key is never committed to this repo and never given to CI.
#
# Unlike the other two samples' sign.sh (one payload file each), this
# plugin's payload is a whole directory tree (two libapp.so + every file
# under flutter_assets/), so this tars two directory roots in one
# invocation: manifest.json/manifest.sig from $BUNDLE_DIR itself, and
# lib/ + flutter_assets/ from $BUNDLE_DIR/payload -- GNU tar applies each
# -C to the file operands that follow it, so the payload tree lands in
# the archive at the same bare "lib/..."/"flutter_assets/..." paths
# manifest.json's own payload list declares, not "payload/lib/...".

set -euo pipefail
cd "$(dirname "$0")"

: "${PLUGIN_SIGNING_KEY:?set to the droidtop plugin origin EC private key PEM}"
: "${BUNDLE_DIR:=build}"

test -f "$BUNDLE_DIR/manifest.json" || { echo "missing $BUNDLE_DIR/manifest.json -- run build.sh first" >&2; exit 1; }
test -d "$BUNDLE_DIR/payload/lib" || { echo "missing $BUNDLE_DIR/payload/lib -- run build.sh first" >&2; exit 1; }
test -d "$BUNDLE_DIR/payload/flutter_assets" || { echo "missing $BUNDLE_DIR/payload/flutter_assets -- run build.sh first" >&2; exit 1; }

openssl dgst -sha256 -sign "$PLUGIN_SIGNING_KEY" "$BUNDLE_DIR/manifest.json" | base64 -w0 > "$BUNDLE_DIR/manifest.sig"

tar --sort=name -cf - \
  -C "$BUNDLE_DIR" manifest.json manifest.sig \
  -C "$BUNDLE_DIR/payload" lib flutter_assets \
  | xz -9e > droidtop.sample-flutter-statustile.droidplugin.tar.xz

echo "Signed droidtop.sample-flutter-statustile.droidplugin.tar.xz"
sha256sum droidtop.sample-flutter-statustile.droidplugin.tar.xz
