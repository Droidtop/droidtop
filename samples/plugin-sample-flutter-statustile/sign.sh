#!/usr/bin/env bash
# Signs and packages an already-built plugin bundle (build.sh output:
# manifest.json + payload/{lib,flutter_assets} -- either built locally or
# downloaded from the sample-plugin-flutter CI job's unsigned artifact)
# into droidtop.sample-flutter-statustile.droidplugin.tar.xz.
#
# The only script that touches the plugin origin private key. CI runs it with
# the PLUGIN_SIGNING_KEY repo secret (written to a 600 temp file for the job and
# deleted afterwards); locally, run it on droidtop-dev with the key under
# /root/coordination/keys/droidtop-plugins/. The key is never committed.
# PLUGIN_SIGNING_KEY is the PATH of the PEM. If PLUGIN_SIGNING_CERT names a
# file (this plugin key's certificate from droidtop's plugin master key), it is
# packaged as origin.cert next to manifest.sig.
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

# Optional certificate of this plugin key (signed by droidtop's plugin master).
rm -f "$BUNDLE_DIR/origin.cert"
CERT_FILE=
if [ -n "${PLUGIN_SIGNING_CERT:-}" ]; then
  test -f "$PLUGIN_SIGNING_CERT" || { echo "PLUGIN_SIGNING_CERT is not a file" >&2; exit 1; }
  cp "$PLUGIN_SIGNING_CERT" "$BUNDLE_DIR/origin.cert"
  CERT_FILE=origin.cert
fi

# GNU tar's repeated -C is CUMULATIVE (each one is relative to wherever
# the previous -C left it, not to this script's own cwd) -- confirmed the
# hard way: a second relative "-C $BUNDLE_DIR/payload" resolved as
# "$BUNDLE_DIR/$BUNDLE_DIR/payload" and failed to open. Absolute paths for
# both -C arguments sidestep that entirely.
BUNDLE_ABS="$(cd "$BUNDLE_DIR" && pwd)"
tar --sort=name -cf - \
  -C "$BUNDLE_ABS" manifest.json manifest.sig $CERT_FILE \
  -C "$BUNDLE_ABS/payload" lib flutter_assets \
  | xz -9e > droidtop.sample-flutter-statustile.droidplugin.tar.xz

echo "Signed droidtop.sample-flutter-statustile.droidplugin.tar.xz"
sha256sum droidtop.sample-flutter-statustile.droidplugin.tar.xz
