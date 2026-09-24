#!/bin/bash
# Cross-compiles the native dependencies Gradle's CMake builds can't build
# themselves: libffi + libwayland-client (for :host-bridge), droidspaces
# (:runtime-linux-root), crane (:runtime-common), proot
# (:runtime-linux-noroot), hev-socks5-tunnel (:app) and mbedTLS
# (for :runtime-remote-stream, via moonlight-common-c's USE_MBEDTLS option
# — that one's wired up directly in runtime-remote-stream's CMakeLists.txt
# via add_subdirectory, so it needs no separate step here).
#
# Verified working in a WSL2 Ubuntu 24.04 environment with:
#   apt: build-essential cmake ninja-build meson pkg-config git python3
#        autoconf automake libtool libltdl-dev texinfo libexpat1-dev
#        libffi-dev libxml2-utils wayland-protocols libwayland-bin
#   gh (GitHub CLI), authenticated (`gh auth login`) — used to pull the
#        mirrored musl cross toolchains, see the droidspaces section below.
#        Already preinstalled + authenticated via GH_TOKEN on GitHub-hosted
#        Actions runners; needs manual install+login for local use.
#   Android SDK/NDK 27.0.12077973 installed via cmdline-tools' sdkmanager
#   Go >= 1.25 (see vendor/go-containerregistry/go.mod) — for cross-
#        compiling crane, see that section below
#
# libltdl-dev matters specifically: it's the package that ships ltdl.m4
# (needed below for libffi's LT_SYS_SYMBOL_USCORE). It was present on the
# WSL2 box only because Debian/Ubuntu installs Recommends by default there
# — GitHub Actions' runner apt config doesn't, so omitting it from an
# explicit install list is a real, CI-only failure, not a hypothetical one
# (this is exactly what happened the first two times this workflow ran).
#
# Usage: ./build-vendor-deps.sh [android-abi] [api-level] [ndk-path]
# Builds ONE ABI per invocation — for a fat/universal APK covering both
# targets (arm64-v8a for real hardware, x86_64 for emulators/x86 devices),
# call this script once per ABI; see the "for ABI in ..." loop in
# .github/workflows/android-build.yml. Defaults match this repo's
# build.gradle.kts files (arm64-v8a, API 26, NDK 27.0.12077973 under
# $ANDROID_SDK_ROOT/ndk).

set -euo pipefail

ABI="${1:-arm64-v8a}"
API="${2:-26}"
NDK="${3:-${ANDROID_SDK_ROOT:-/opt/android-sdk}/ndk/27.0.12077973}"
DEPS_PREFIX="${ANDROID_DEPS_PREFIX:-/opt/android-deps}"
DEPS_DIR="$DEPS_PREFIX/$ABI"

# Per-ABI target triples: one for the NDK's clang (Android/bionic target),
# one for musl.cc's prebuilt cross toolchain (droidspaces' static-musl
# build, unrelated to Android's own libc), and one for Meson's cpu naming
# (which happens to match the musl triple's arch component for both ABIs
# this project targets).
case "$ABI" in
    arm64-v8a)
        TARGET_TRIPLE="aarch64-linux-android"
        MUSL_TRIPLE="aarch64-linux-musl"
        MESON_CPU_FAMILY="aarch64"
        MESON_CPU="aarch64"
        DROIDSPACES_MAKE_TARGET="aarch64"
        GOARCH="arm64"
        ;;
    x86_64)
        TARGET_TRIPLE="x86_64-linux-android"
        MUSL_TRIPLE="x86_64-linux-musl"
        MESON_CPU_FAMILY="x86_64"
        MESON_CPU="x86_64"
        DROIDSPACES_MAKE_TARGET="x86_64"
        GOARCH="amd64"
        ;;
    *)
        echo "No target-triple mapping for ABI '$ABI' — only arm64-v8a and x86_64 are wired up." >&2
        echo "(Those are this project's actual targets: real ARM hardware + x86_64 emulators/devices.)" >&2
        exit 1
        ;;
esac

TOOLCHAIN_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TOOLCHAIN_BIN/$TARGET_TRIPLE$API-clang"
CXX="$TOOLCHAIN_BIN/$TARGET_TRIPLE$API-clang++"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENDOR="$REPO_ROOT/vendor"
WORK="$REPO_ROOT/.vendor-deps-build/$ABI"
mkdir -p "$WORK" "$DEPS_DIR"

echo "=== Stripping CRLF from vendor/ (Windows git checkouts corrupt shell scripts) ==="
find "$VENDOR" -type f -not -path '*/.git/*' -print0 | while IFS= read -r -d '' f; do
    if file "$f" | grep -q "CRLF"; then sed -i 's/\r$//' "$f"; fi
done

echo "=== libffi ($ABI) ==="
# Subshell: CC/CXX/AR/etc. must not leak into the native (x86_64) wayland-
# scanner build below — that bit us once already (meson tried to run an
# Android-targeted "native" sanity-check binary on the x86_64 host and got
# Exec format error, because these exact env vars were still exported).
(
    export PATH="$TOOLCHAIN_BIN:$PATH"
    export CC CXX AR=llvm-ar AS="$CC" LD=ld RANLIB=llvm-ranlib STRIP=llvm-strip
    cd "$VENDOR/libffi"
    if [ ! -f configure ]; then
        # libffi's Makefile.am ships ACLOCAL_AMFLAGS which conflicts with its
        # own AC_CONFIG_MACRO_DIRS([m4]) under current autoconf/libtool —
        # strip it. Known upstream autotools-version friction, not a bug here.
        sed -i '/^ACLOCAL_AMFLAGS = -I m4$/d' Makefile.am

        # `-I m4` passed explicitly (not left to AC_CONFIG_MACRO_DIRS([m4])
        # auto-detection) — needed on some but not all environments.
        #
        # libffi's configure.ac uses LT_SYS_SYMBOL_USCORE, which lives in
        # libtool's ltdl.m4 — a file `libtoolize --copy` does NOT copy into
        # m4/ by default (only libtool.m4/ltoptions.m4/ltsugar.m4/
        # ltversion.m4/lt~obsolete.m4 are). aclocal still finds it via its
        # own system search path in some environments (worked in WSL2/
        # Ubuntu 24.04) but not others (GitHub Actions' ubuntu-24.04 runner
        # failed with "possibly undefined macro: LT_SYS_SYMBOL_USCORE" even
        # with -I m4 correctly applied — confirmed from the actual CI log,
        # not guessed). Copying it into m4/ explicitly removes the
        # dependency on that implicit, apparently-not-actually-portable
        # system search path entirely.
        mkdir -p m4
        ltdl_m4="$(find /usr/share/aclocal* -name ltdl.m4 2>/dev/null | head -1)"
        if [ -n "$ltdl_m4" ]; then
            cp "$ltdl_m4" m4/
        else
            echo "WARNING: ltdl.m4 not found under /usr/share/aclocal* — libffi's autoreconf may fail on LT_SYS_SYMBOL_USCORE" >&2
        fi

        autoreconf -v -i -I m4
    fi
    BUILD_DIR="$WORK/libffi"
    mkdir -p "$BUILD_DIR"
    cd "$BUILD_DIR"
    "$VENDOR/libffi/configure" \
        --host="$TARGET_TRIPLE" \
        --prefix="$DEPS_DIR" \
        --disable-shared --enable-static --disable-docs
    make -j"$(nproc)"
    make install
)

echo "=== wayland-scanner (native x86_64, matching vendor/wayland's exact version) ==="
NATIVE_PREFIX="$WORK/wayland-native"
(
    cd "$VENDOR/wayland"
    meson setup "$WORK/wayland-native-build" --wipe \
        --prefix="$NATIVE_PREFIX" \
        -Dlibraries=false -Dscanner=true -Ddocumentation=false \
        -Ddtd_validation=false -Dtests=false
    ninja -C "$WORK/wayland-native-build"
    ninja -C "$WORK/wayland-native-build" install
)

echo "=== libwayland-client ($ABI) ==="
CROSS_INI="$WORK/android-cross.ini"
cat > "$CROSS_INI" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$TOOLCHAIN_BIN/llvm-ar'
strip = '$TOOLCHAIN_BIN/llvm-strip'
pkg-config = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = '$MESON_CPU_FAMILY'
cpu = '$MESON_CPU'
endian = 'little'

[properties]
pkg_config_libdir = '$DEPS_DIR/lib/pkgconfig'
EOF

NATIVE_INI="$WORK/android-native.ini"
cat > "$NATIVE_INI" <<EOF
[properties]
pkg_config_libdir = '$NATIVE_PREFIX/lib/x86_64-linux-gnu/pkgconfig'
EOF

(
    cd "$VENDOR/wayland"
    meson setup "$WORK/wayland-android-build" --wipe \
        --cross-file "$CROSS_INI" \
        --native-file "$NATIVE_INI" \
        --prefix="$DEPS_DIR" \
        -Dlibraries=true -Dscanner=false -Ddocumentation=false \
        -Ddtd_validation=false -Dtests=false
    ninja -C "$WORK/wayland-android-build"
    ninja -C "$WORK/wayland-android-build" install
)

# libwayland-client.so is NOT copied into host-bridge/src/main/jniLibs here.
# host-bridge/native/CMakeLists.txt links hostbridge against it via an
# absolute path (target_link_libraries(hostbridge ${WAYLAND_CLIENT_LIB})) —
# AGP's external native build packaging already copies any prebuilt shared
# library linked that way into the APK automatically (the documented
# mechanism: https://developer.android.com/ndk/guides/prebuilts). Also
# copying it into jniLibs here duplicated the same path
# (lib/arm64-v8a/libwayland-client.so) from two different sources, which
# :host-bridge:mergeDebugNativeLibs correctly refused to merge ("2 files
# found with path ...").

echo "=== droidspaces ($ABI) ==="
# Genuinely simple compared to everything above: a single static musl
# binary, no shared-library deps at all (musl's static linking means it
# only needs the Linux kernel syscall ABI, which Android provides — this
# is exactly why it runs fine on Android despite being built against musl,
# not bionic). Just needs a prebuilt musl cross toolchain matching $ABI.
MUSL_TOOLCHAIN_DIR="$REPO_ROOT/.vendor-deps-build/musl-cross-toolchain-$MUSL_TRIPLE"
MUSL_CROSS_BIN="$MUSL_TOOLCHAIN_DIR/$MUSL_TRIPLE-cross/bin"
if [ ! -x "$MUSL_CROSS_BIN/$MUSL_TRIPLE-gcc" ]; then
    mkdir -p "$MUSL_TOOLCHAIN_DIR"
    # musl.cc isn't behind a CDN, and downloading directly from it proved
    # genuinely unreliable from GitHub Actions runners -- not a transient
    # blip: a real run retried 6 times over 12+ minutes (--retry 5,
    # --max-time 120 each) and never got past a connection timeout even
    # once. Mirrored both toolchains as assets on this repo's own
    # "build-toolchains" release instead (re-hosted as-is, not modified —
    # re-pull from https://musl.cc if they ever need updating) and pull
    # from there via `gh release download`, which is authenticated and
    # backed by GitHub's own reliable infrastructure rather than a single
    # third-party host. Needs `gh auth login` locally, or GH_TOKEN set in
    # CI (see .github/workflows/android-build.yml).
    REPO_SLUG="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || echo bi0shacker001/droidtop)"
    gh release download build-toolchains \
        --repo "$REPO_SLUG" \
        --pattern "$MUSL_TRIPLE-cross.tgz" \
        --dir "$MUSL_TOOLCHAIN_DIR" \
        --clobber
    tar -xzf "$MUSL_TOOLCHAIN_DIR/$MUSL_TRIPLE-cross.tgz" -C "$MUSL_TOOLCHAIN_DIR"
    rm "$MUSL_TOOLCHAIN_DIR/$MUSL_TRIPLE-cross.tgz"
fi

(
    export MUSL_CROSS="$MUSL_CROSS_BIN"
    cd "$VENDOR/droidspaces"
    make clean >/dev/null 2>&1 || true
    # Upstream's own CFLAGS (copied from their Makefile) plus five
    # -Wno-error= exceptions for warning classes that are false positives
    # specifically under this musl-cross-make GCC version (confirmed by
    # building it: every one of these fires on generic bounds-checked
    # helpers like `safe_strncpy(dst, size, ...)` where `size` is a runtime
    # parameter GCC's static analysis can't fully resolve, not on any
    # actual bug) — everything else stays -Werror, matching upstream intent.
    make "$DROIDSPACES_MAKE_TARGET" CFLAGS="-Wall -Wextra -Wpedantic -Werror -O2 -flto=auto -std=gnu99 -Isrc/include -no-pie -pthread -Wformat=2 -Wformat-security -Wnull-dereference -Wcast-qual -Wlogical-op -Wshadow -Wdouble-promotion -Wundef -Wduplicated-cond -Wduplicated-branches -Wimplicit-fallthrough=3 -fstack-protector-strong -Wno-error=format-truncation -Wno-error=format-overflow -Wno-error=array-bounds -Wno-error=stringop-truncation -Wno-error=stringop-overflow"
)

echo "=== Copying droidspaces binary into runtime-linux-root's assets (packaged into the APK) ==="
DS_ASSETS="$REPO_ROOT/runtime-linux-root/src/main/assets/bin"
mkdir -p "$DS_ASSETS"
cp "$VENDOR/droidspaces/output/droidspaces" "$DS_ASSETS/droidspaces-$ABI"

echo "=== crane ($ABI) ==="
# vendor/go-containerregistry's CLI — the OCI registry client
# CraneRootfsPuller shells out to (runtime-common; both container backends
# pull through it). Packaged as runtime-common's jniLibs/$ABI/libcrane.so,
# not an asset: Android refuses exec() of a binary the app extracted into
# its own data directory once targetSdk is above 28 (droidtop targets 34;
# docs/SPEC.md 5b), and crane runs as the app itself on an unrooted
# device. nativeLibraryDir is the one place an app may exec from, and
# only files named lib*.so land there. Go cross-compiles
# to Android natively (no separate cross-toolchain download needed, unlike
# droidspaces' musl-cross toolchains above): GOOS=android + GOARCH is
# enough — but CGO_ENABLED=1 (with the same NDK clang already resolved
# above as $CC) is REQUIRED on every ABI, for two independent real
# reasons: android/amd64 refuses to link without it at all ("android/amd64
# requires external (cgo) linking, but cgo is not enabled" — a real build
# failure), and android/arm64, which DOES link statically with
# CGO_ENABLED=0, then ships with Go's pure-Go DNS resolver only — which
# on Android tries [::1]:53 (there is no /etc/resolv.conf) and every
# registry lookup fails with "dial udp [::1]:53: connect: connection
# refused". Confirmed live on-device (GODEBUG=netdns=go+2 tracing on the
# first real desktop-pipeline run): Android's DNS goes through bionic/
# netd, which only the cgo resolver reaches.
if ! command -v go >/dev/null 2>&1; then
    echo "go not found on PATH — crane needs Go >= 1.25 (see vendor/go-containerregistry/go.mod)." >&2
    exit 1
fi
(
    cd "$VENDOR/go-containerregistry"
    # cgo on EVERY ABI — see the resolver note above; CGO_ENABLED=0 on
    # arm64 produced a crane whose DNS was dead on-device.
    export CGO_ENABLED=1
    export CC="$CC"
    mkdir -p "$REPO_ROOT/runtime-common/src/main/jniLibs/$ABI"
    GOOS=android GOARCH="$GOARCH" go build -trimpath -ldflags="-s -w" \
        -o "$REPO_ROOT/runtime-common/src/main/jniLibs/$ABI/libcrane.so" \
        ./cmd/crane
)

echo "=== proot ($ABI) ==="
# vendor/proot is Termux's PRoot (the build Termux and proot-distro run
# full distributions on), consumed unmodified. It is what ProotRuntime
# (runtime-linux-noroot) runs every container process through on a device
# without root. gamenative's own proot is not usable for this: its only
# binaries are an armeabi-v7a pair under src/legacy, its source's arch.h
# rejects every architecture but ARM (no x86_64 at all), and it lacks the
# fake-root and link2symlink extensions a stock distro's package manager
# needs (docs/SPEC.md 3).
#
# Built with its own GNUmakefile and the NDK clang already resolved above.
# talloc is proot's one library dependency; the single-file copy vendored
# with gamenative's proot tree is compiled into a static archive and
# linked in, so the result needs nothing beyond bionic.
#
# PROOT_UNBUNDLE_LOADER keeps the loader a separate executable instead of
# embedding it and extracting it to disk at run time (which is again an
# exec of an extracted file). ProotRuntime points PROOT_LOADER and
# PROOT_LOADER_32 at the packaged copies in nativeLibraryDir; the path
# compiled in here is only the fallback when those are unset, so it names
# the mistake instead of a real location.
#
# Outputs, all into runtime-linux-noroot/src/main/jniLibs/$ABI/ so they
# land in nativeLibraryDir: libproot.so (proot itself), libproot-loader.so
# (the 64-bit loader) and libproot-loader32.so (the 32-bit one arm64 and
# x86_64 both have, for 32-bit guest binaries).
(
    PROOT_WORK="$WORK/proot"
    rm -rf "$PROOT_WORK"
    mkdir -p "$PROOT_WORK/talloc"
    TALLOC_SRC="$VENDOR/gamenative/app/src/main/cpp/proot/talloc"
    "$CC" -O2 -fPIC -c "$TALLOC_SRC/talloc.c" -I"$TALLOC_SRC" -o "$PROOT_WORK/talloc/talloc.o"
    "$TOOLCHAIN_BIN/llvm-ar" rcs "$PROOT_WORK/talloc/libtalloc.a" "$PROOT_WORK/talloc/talloc.o"
    # Out-of-tree copy: the GNUmakefile writes objects and build.h beside
    # the sources, and the vendored submodule stays untouched.
    cp -r "$VENDOR/proot/src" "$PROOT_WORK/src"
    # droidtop's additions to Termux's proot, each a patch with its reason
    # in its header, applied to that copy only (build-scripts/proot-patches).
    for proot_patch in "$REPO_ROOT"/build-scripts/proot-patches/*.patch; do
        [ -e "$proot_patch" ] || continue
        patch -d "$PROOT_WORK" -p1 --forward < "$proot_patch"
    done
    cd "$PROOT_WORK/src"
    # CPPFLAGS/LDFLAGS through the environment, not as make arguments:
    # the makefile appends to both with +=, and a command-line assignment
    # would replace its own -I. -D_GNU_SOURCE instead of adding to them.
    # ARG_MAX is what termux-packages defines for bionic, which lacks it.
    CPPFLAGS="-I$TALLOC_SRC -DARG_MAX=131072" LDFLAGS="-L$PROOT_WORK/talloc" \
        make -j"$(nproc)" \
        CC="$CC" \
        STRIP="$TOOLCHAIN_BIN/llvm-strip" \
        OBJCOPY="$TOOLCHAIN_BIN/llvm-objcopy" \
        OBJDUMP="$TOOLCHAIN_BIN/llvm-objdump" \
        PROOT_UNBUNDLE_LOADER=/PROOT_LOADER-was-not-set \
        proot loader/loader loader/loader-m32
    PROOT_OUT="$REPO_ROOT/runtime-linux-noroot/src/main/jniLibs/$ABI"
    mkdir -p "$PROOT_OUT"
    cp proot "$PROOT_OUT/libproot.so"
    cp loader/loader "$PROOT_OUT/libproot-loader.so"
    cp loader/loader-m32 "$PROOT_OUT/libproot-loader32.so"
)

echo "=== hev-socks5-tunnel ($ABI) ==="
# vendor/hev-socks5-tunnel is the userspace IP stack DroidtopVpnService
# feeds the device's tun fd to (docs/SPEC.md 4a): every packet Android
# routes into the VPN becomes a SOCKS5 connection, which droidtop relays to
# the container's VPN socket. Consumed unmodified, with its own Android.mk
# and ndk-build; only its JNI class is chosen at compile time
# (src/hev-jni.c registers its natives on PKGNAME/CLSNAME), to be
# dev.droidtop.app.vpn.TunnelNative. APP_MODULES builds the library, not
# the standalone executable beside it; APP_PLATFORM is droidtop's minSdk.
#
# Output: app/src/main/jniLibs/$ABI/libhev-socks5-tunnel.so.
(
    HEV_WORK="$WORK/hev-socks5-tunnel"
    rm -rf "$HEV_WORK"
    "$NDK/ndk-build" -C "$VENDOR/hev-socks5-tunnel" \
        NDK_PROJECT_PATH=. \
        APP_BUILD_SCRIPT=Android.mk \
        NDK_APPLICATION_MK=Application.mk \
        APP_ABI="$ABI" \
        APP_PLATFORM="android-$API" \
        APP_MODULES=hev-socks5-tunnel \
        NDK_OUT="$HEV_WORK/obj" \
        NDK_LIBS_OUT="$HEV_WORK/libs" \
        APP_CFLAGS="-O3 -DPKGNAME=dev/droidtop/app/vpn -DCLSNAME=TunnelNative" \
        -j"$(nproc)"
    HEV_OUT="$REPO_ROOT/app/src/main/jniLibs/$ABI"
    mkdir -p "$HEV_OUT"
    cp "$HEV_WORK/libs/$ABI/libhev-socks5-tunnel.so" "$HEV_OUT/"
)

echo "=== PulseAudio 13.0, libsndfile, libltdl ($ABI) ==="
# The audio half of gamenative's Windows runtime (docs/SPEC.md 10b). Its
# arm64 set is prebuilt upstream (libpulse*, libsndfile, libltdl in the
# fork's jniLibs, the daemon as libpulseaudio.so, and modules + pactl in
# the pulseaudio-gamenative asset); x86_64 is built here, with the same
# file names: the ABI picker compares names, and PulseAudioComponent
# execs nativeLibraryDir/libpulseaudio.so.
#
#   libltdl      the standalone libltdl tree libltdl-dev installs under
#                /usr/share/libtool (already on the apt list).
#   libsndfile   vendor/libsndfile at 1.0.28, the version in the arm64 set.
#   PulseAudio   vendor/pulseaudio at v13.0; the file names carry 13.0.
#                Termux's Android patches of its 13.0 package and its
#                module-aaudio-sink.c (build-scripts/pulseaudio-patches,
#                the sink extended with the arguments GameNative passes).
#                Configured as the arm64 set was: no speex or soxr (its
#                libpulsecore has no speex resampler), no memfd.
#                ac_cv_header_glob_h=no: bionic has glob() only from
#                API 28, and PulseAudio uses it only for scache
#                directories, behind HAVE_GLOB_H.
#                ac_cv_header_execinfo_h=no: likewise backtrace() only
#                from API 33 (the header exists, its declarations are
#                hidden below that); log.c uses it only behind
#                HAVE_EXECINFO_H, for optional log backtraces.
#
# libtool versions sonames (libpulse.so.0); Android loads only lib*.so
# out of nativeLibraryDir, so every output's soname and NEEDED entries
# lose the version and its RUNPATH goes (patchelf, on the apt list).
#
# Outputs: runtime-windows/src/main/jniLibs/x86_64/ (the six libraries)
# and runtime-windows/src/main/assets/pulseaudio-gamenative-x86_64.tzst
# (modules/ and pactl, laid out like the arm64 asset).
if [ "$ABI" = "x86_64" ]; then (
    export PATH="$TOOLCHAIN_BIN:$PATH"
    export CC CXX AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip NM=llvm-nm
    PA_WORK="$WORK/pulseaudio"
    PA_PREFIX="$PA_WORK/prefix"
    rm -rf "$PA_WORK"
    mkdir -p "$PA_WORK/src" "$PA_PREFIX"
    HOST_ARGS=(--host="$TARGET_TRIPLE" --prefix="$PA_PREFIX" --disable-static --enable-shared)

    cp -rL /usr/share/libtool "$PA_WORK/src/ltdl"
    cp -rL /usr/share/libtool/build-aux "$PA_WORK/src/build-aux"
    ( cd "$PA_WORK/src/ltdl" && ./configure "${HOST_ARGS[@]}" --enable-ltdl-install && make -j"$(nproc)" && make install )

    cp -r "$VENDOR/libsndfile" "$PA_WORK/src/sndfile"
    rm -f "$PA_WORK/src/sndfile/.git"
    ( cd "$PA_WORK/src/sndfile" && autoreconf -fi && \
        ./configure "${HOST_ARGS[@]}" --disable-external-libs --disable-sqlite --disable-alsa \
            --disable-full-suite --disable-octave && \
        make -C src -j"$(nproc)" && make -C src install && make install-pkgconfigDATA )

    cp -r "$VENDOR/pulseaudio" "$PA_WORK/src/pulseaudio"
    cd "$PA_WORK/src/pulseaudio"
    rm -f .git
    # A shallow submodule has no tags for git-version-gen to describe.
    echo -n 13.0 > .tarball-version
    PA_PATCHES="$REPO_ROOT/build-scripts/pulseaudio-patches"
    for pa_patch in "$PA_PATCHES"/*.patch; do patch -p1 --forward < "$pa_patch"; done
    mkdir -p src/modules/aaudio
    cp "$PA_PATCHES/module-aaudio-sink.c" src/modules/aaudio/
    autoreconf --force --install
    PKG_CONFIG_LIBDIR="$PA_PREFIX/lib/pkgconfig" \
    CPPFLAGS="-I$PA_PREFIX/include" LDFLAGS="-L$PA_PREFIX/lib" \
    ./configure "${HOST_ARGS[@]}" \
        --disable-neon-opt --disable-alsa --disable-esound --disable-glib2 --disable-x11 \
        --disable-gtk3 --disable-openssl --without-caps --with-database=simple --disable-memfd \
        --disable-gsettings --disable-dbus --disable-udev --disable-bluez5 --disable-avahi \
        --disable-jack --disable-lirc --disable-tcpwrap --disable-systemd-daemon \
        --disable-systemd-login --disable-systemd-journal --without-speex --without-soxr \
        --disable-orc --disable-webrtc-aec --disable-tests --disable-manpages --disable-hal-compat \
        --disable-oss-output --disable-oss-wrapper --disable-gconf --disable-asyncns --disable-nls \
        --without-fftw --disable-default-build-tests \
        ax_cv_PTHREAD_PRIO_INHERIT=no ac_cv_header_glob_h=no ac_cv_header_execinfo_h=no
    make -C src -j"$(nproc)" \
        libpulsecommon-13.0.la libpulse.la libpulsecore-13.0.la pulseaudio pactl \
        libprotocol-native.la module-native-protocol-unix.la module-aaudio-sink.la

    PA_LIBS="$REPO_ROOT/runtime-windows/src/main/jniLibs/$ABI"
    PA_ASSET="$PA_WORK/asset"
    mkdir -p "$PA_LIBS" "$PA_ASSET/modules"
    cp -L "$PA_PREFIX/lib/libltdl.so" "$PA_PREFIX/lib/libsndfile.so" "$PA_LIBS/"
    cp -L src/.libs/libpulse.so src/.libs/libpulsecommon-13.0.so src/.libs/libpulsecore-13.0.so "$PA_LIBS/"
    cp -L src/.libs/pulseaudio "$PA_LIBS/libpulseaudio.so"
    cp -L src/.libs/libprotocol-native.so src/.libs/module-native-protocol-unix.so \
        src/.libs/module-aaudio-sink.so "$PA_ASSET/modules/"
    cp -L src/.libs/pactl "$PA_ASSET/pactl"
    for elf in "$PA_LIBS"/libltdl.so "$PA_LIBS"/libsndfile.so "$PA_LIBS"/libpulse*.so \
        "$PA_ASSET"/modules/*.so "$PA_ASSET/pactl"; do
        patchelf --remove-rpath "$elf"
        soname="$(patchelf --print-soname "$elf" 2>/dev/null || true)"
        case "$soname" in *.so.*) patchelf --set-soname "${soname%%.so.*}.so" "$elf" ;; esac
        for needed in $(patchelf --print-needed "$elf"); do
            case "$needed" in *.so.*) patchelf --replace-needed "$needed" "${needed%%.so.*}.so" "$elf" ;; esac
        done
        llvm-strip --strip-unneeded "$elf"
    done
    mkdir -p "$REPO_ROOT/runtime-windows/src/main/assets"
    tar -C "$PA_ASSET" -I 'zstd -19' -cf \
        "$REPO_ROOT/runtime-windows/src/main/assets/pulseaudio-gamenative-x86_64.tzst" modules pactl
) fi

echo "=== Done. Deps installed under $DEPS_DIR ==="
find "$DEPS_DIR" -iname "*wayland-client*" -o -iname "libffi.a"
file "$DS_ASSETS/droidspaces-$ABI"
file "$REPO_ROOT/runtime-common/src/main/jniLibs/$ABI/libcrane.so"
file "$REPO_ROOT/runtime-linux-noroot/src/main/jniLibs/$ABI/"lib*.so
file "$REPO_ROOT/app/src/main/jniLibs/$ABI/libhev-socks5-tunnel.so"
