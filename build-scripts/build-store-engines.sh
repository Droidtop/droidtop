#!/usr/bin/env bash
# Builds DroidDeck's store download engines (libdroiddeckstores.so; docs/SPEC.md 7g, "Store engines from DroidDeck")
# for arm64-v8a and x86_64 into stores/engines/jniLibs/, where stores/build.gradle.kts reads it.
#
# The engines are DroidDeck's Rust crate app/src/main/rust/stores (GPL-3.0-or-later), used unchanged from the
# vendor/droiddeck submodule at its pinned commit. That submodule is `update = none` in .gitmodules: DroidDeck's tip is
# a 300 MB tree and two submodules of its own, of which only this 1.8 MB crate is built, so a recursive checkout skips
# it and this script fetches the pinned commit shallow and sparse, with only the crate's folder checked out. The pin
# is the gitlink, as for every submodule; moving it is `git -C vendor/droiddeck checkout <commit>` and committing it.
#
# Needs rustup's aarch64-linux-android and x86_64-linux-android targets and the NDK at ANDROID_NDK_HOME; links
# against API 26, droidtop's minSdk, as build-agent-lib.sh does.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DD="$ROOT/vendor/droiddeck"
CRATE_PATH="app/src/main/rust/stores"
CRATE="$DD/$CRATE_PATH"
DEST="$ROOT/stores/engines/jniLibs"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK}"

pin=$(git -C "$ROOT" ls-tree HEAD vendor/droiddeck | awk '{print $3}')
[ -n "$pin" ] || { echo "vendor/droiddeck has no pin in HEAD" >&2; exit 1; }
url=$(git -C "$ROOT" config -f "$ROOT/.gitmodules" submodule.vendor/droiddeck.url)
have=""
[ -e "$DD/.git" ] && have=$(git -C "$DD" rev-parse HEAD 2>/dev/null || true)
if [ "$have" != "$pin" ]; then
    mkdir -p "$DD"
    [ -e "$DD/.git" ] || git -C "$DD" init -q
    git -C "$DD" remote get-url origin >/dev/null 2>&1 || git -C "$DD" remote add origin "$url"
    git -C "$DD" sparse-checkout set --cone "$CRATE_PATH"
    git -C "$DD" fetch -q --depth 1 --filter=blob:none origin "$pin"
    git -C "$DD" checkout -q --detach FETCH_HEAD
fi
[ -f "$CRATE/Cargo.toml" ] || { echo "no crate at $CRATE" >&2; exit 1; }

bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$bin/aarch64-linux-android26-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$bin/x86_64-linux-android26-clang"
export CC_aarch64_linux_android="$bin/aarch64-linux-android26-clang"
export CC_x86_64_linux_android="$bin/x86_64-linux-android26-clang"
export AR_aarch64_linux_android="$bin/llvm-ar"
export AR_x86_64_linux_android="$bin/llvm-ar"
# Built outside the submodule, so the vendored tree stays exactly the pinned commit.
export CARGO_TARGET_DIR="$ROOT/stores/engines/target"
for pair in aarch64-linux-android:arm64-v8a x86_64-linux-android:x86_64; do
    target=${pair%%:*}; abi=${pair#*:}
    cargo build --locked --release --manifest-path "$CRATE/Cargo.toml" --target "$target"
    mkdir -p "$DEST/$abi"
    cp "$CARGO_TARGET_DIR/$target/release/libdroiddeckstores.so" "$DEST/$abi/"
done
ls -l "$DEST"/*/libdroiddeckstores.so
