#!/usr/bin/env bash
# Builds droidtop's native library (libdroidtop_agent.so; docs/SPEC.md 7o) from native/ for arm64-v8a and
# x86_64 into net-core/agent/jniLibs/, where net-core/build.gradle.kts reads it. It replaced fetching
# droidtop-agent's prebuilt release (Droidtop/tracker#867: each program builds its own scaffold over the
# shared libraries). Needs rustup's aarch64-linux-android and x86_64-linux-android targets and the NDK at
# ANDROID_NDK_HOME; links against API 26, droidtop's minSdk, as droidtop-agent's CI did.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEST="$ROOT/net-core/agent/jniLibs"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK}"
bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$bin/aarch64-linux-android26-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$bin/x86_64-linux-android26-clang"
export CC_aarch64_linux_android="$bin/aarch64-linux-android26-clang"
export CC_x86_64_linux_android="$bin/x86_64-linux-android26-clang"
export AR_aarch64_linux_android="$bin/llvm-ar"
export AR_x86_64_linux_android="$bin/llvm-ar"
for pair in aarch64-linux-android:arm64-v8a x86_64-linux-android:x86_64; do
    target=${pair%%:*}; abi=${pair#*:}
    cargo build --locked --release --manifest-path "$ROOT/native/Cargo.toml" --target "$target"
    mkdir -p "$DEST/$abi"
    cp "$ROOT/native/target/$target/release/libdroidtop_agent.so" "$DEST/$abi/"
done
ls -l "$DEST"/*/libdroidtop_agent.so
