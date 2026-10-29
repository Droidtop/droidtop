#!/usr/bin/env bash
# Puts the SQLite amalgamation (sqlite3.c and sqlite3.h) into
# plugin-host/native/sqlite/, where plugin-host/native/CMakeLists.txt builds
# it into droidtop's native library: a plugin's SQLite is droidtop's own,
# never Android's (docs/plugin-api.md 5.3, "Preferences and SQLite are
# droidtop's", Droidtop/tracker#741). The release, its size and the SHA3-256
# sqlite.org lists for it are plugin-host/sqlite.pin; a mismatch fails the
# build rather than compiling something else.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PIN="$ROOT/plugin-host/sqlite.pin"
DEST="$ROOT/plugin-host/native/sqlite"
version=$(sed -n 's/^version=//p' "$PIN")
path=$(sed -n 's/^path=//p' "$PIN")
size=$(sed -n 's/^size=//p' "$PIN")
sha3=$(sed -n 's/^sha3_256=//p' "$PIN")
: "${version:?no version in $PIN}" "${path:?no path in $PIN}" "${size:?no size in $PIN}" "${sha3:?no sha3_256 in $PIN}"
if [ -f "$DEST/.pin" ] && [ "$(cat "$DEST/.pin")" = "$version $sha3" ]; then
    echo "SQLite $version already in place"
    exit 0
fi
work=$(mktemp -d)
trap 'rm -rf -- "${work:?}"' EXIT
curl -fsSL --retry 5 --retry-delay 10 -o "$work/sqlite.zip" "https://www.sqlite.org/$path"
got_size=$(stat -c %s "$work/sqlite.zip")
if [ "$got_size" != "$size" ]; then
    echo "SQLite $version: $got_size bytes, the pin says $size" >&2
    exit 1
fi
got_sha3=$(python3 -I -c 'import hashlib, sys; print(hashlib.sha3_256(open(sys.argv[1], "rb").read()).hexdigest())' "$work/sqlite.zip")
if [ "$got_sha3" != "$sha3" ]; then
    echo "SQLite $version: SHA3-256 $got_sha3, the pin says $sha3" >&2
    exit 1
fi
echo "SQLite $version: size and SHA3-256 match the pin (sha256 $(sha256sum "$work/sqlite.zip" | cut -d' ' -f1))"
unzip -q -j -d "$work/out" "$work/sqlite.zip" '*/sqlite3.c' '*/sqlite3.h'
rm -rf -- "${DEST:?}"
mkdir -p "$DEST"
cp "$work/out/sqlite3.c" "$work/out/sqlite3.h" "$DEST/"
echo "$version $sha3" > "$DEST/.pin"
ls -l "$DEST"
