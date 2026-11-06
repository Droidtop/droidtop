# droidtop's vtest server recipe (Droidtop/tracker#608, #921), built with Termux's package builder as
# packages/virgl-vtest-droidtop. It is Termux's packages/virglrenderer-android recipe (termux-packages 789d4606fe49:
# virglrenderer 1.3.0 with Venus, built with the NDK in its host step, replaying Venus on Android's own Vulkan),
# changed in three ways only:
#
# - no angle-android dependency: without its --angle-* flags the server's libepoxy opens Android's own libEGL.so and
#   libGLESv2.so, so droidtop ships no ANGLE;
# - virglrenderer and libepoxy are static (-Ddefault_library=static), so the server is one executable. droidtop
#   already ships a libvirglrenderer.so (GameNative's older GL server, VirGLRendererComponent) and a second one with
#   the same name could not sit beside it;
# - the executable is kept as libvirgl_vtest.so, the name it ships under in the APK (an executable runs from
#   nativeLibraryDir only, and only lib*.so files are extracted there).
#
# Its socket comes from --socket-path on the command line; Termux's default under its own prefix is never used.
#
# The sources are vendor/virglrenderer (Droidtop/virglrenderer, branch droidtop: the virglrenderer-1.3.0 tag with
# Termux's patches 0001-0012 but 0003 as commits) and vendor/libepoxy (Droidtop/libepoxy, branch droidtop: the 1.5.10
# tag with Termux's 0003-use-angle as a commit). Nothing is patched at build time. The x86_64 graphics workflow copies
# both trees into the termux-packages checkout as droidtop-src/, the one place the builder's container can read them,
# and Termux's COPYING-gl4es beside this file (the licence of the gl4es code virglrenderer's 0008 adds).
TERMUX_PKG_HOMEPAGE=https://virgil3d.github.io/
TERMUX_PKG_DESCRIPTION="virglrenderer vtest server with Venus on Android Vulkan, for droidtop (Droidtop/tracker#608)"
TERMUX_PKG_LICENSE="MIT"
TERMUX_PKG_MAINTAINER="@licy183"
TERMUX_PKG_GENERATE_REPOLOGY_METADATA=false
TERMUX_PKG_VERSION="1.3.0"
_LIBEPOXY_VERSION="1.5.10"
TERMUX_PKG_REVISION=1

TERMUX_PKG_DEPENDS=""
TERMUX_PKG_HOSTBUILD=true

termux_step_get_source() {
	mkdir -p "$TERMUX_PKG_SRCDIR"
	cp -a "$TERMUX_SCRIPTDIR/droidtop-src/virglrenderer/." "$TERMUX_PKG_SRCDIR/"
	cp -a "$TERMUX_SCRIPTDIR/droidtop-src/libepoxy" "$TERMUX_PKG_SRCDIR/libepoxy"
}

termux_step_host_build() {
	# This package should use the Android NDK toolchain to compile, not
	# our custom toolchain, so I'd like to compile it in the hostbuild step.
	if [[ "$TERMUX_ON_DEVICE_BUILD" == "false" ]]; then
		export PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
		export CCTERMUX_HOST_PLATFORM="$TERMUX_HOST_PLATFORM$TERMUX_PKG_API_LEVEL"
		if [[ "$TERMUX_ARCH" == "arm" ]]; then
			CCTERMUX_HOST_PLATFORM="armv7a-linux-androideabi$TERMUX_PKG_API_LEVEL"
		fi
	fi

	local _INSTALL_PREFIX=$TERMUX_PREFIX/opt/$TERMUX_PKG_NAME

	PKG_CONFIG="$TERMUX_PKG_TMPDIR/host-build-pkg-config"
	local _HOST_PKGCONFIG=$(command -v pkg-config)
	cat > $PKG_CONFIG <<-HERE
		#!/bin/sh
		export PKG_CONFIG_DIR=
		export PKG_CONFIG_LIBDIR=$_INSTALL_PREFIX/lib/pkgconfig
		exec $_HOST_PKGCONFIG "\$@"
	HERE
	chmod +x $PKG_CONFIG

	AR=$(command -v llvm-ar)
	if [[ "$TERMUX_ON_DEVICE_BUILD" == "false" ]]; then
		CC=$(command -v "$CCTERMUX_HOST_PLATFORM-clang")
		CXX=$(command -v "$CCTERMUX_HOST_PLATFORM-clang++")
	else
		CC=$(command -v "$TERMUX_HOST_PLATFORM-clang")
		CXX=$(command -v "$TERMUX_HOST_PLATFORM-clang++")
	fi
	LD=$(command -v ld.lld)
	CFLAGS=""
	CPPFLAGS=""
	CXXFLAGS=""
	LDFLAGS="-Wl,-rpath=$_INSTALL_PREFIX/lib"
	STRIP=$(command -v llvm-strip)
	termux_setup_meson

	# Compile libepoxy
	mkdir -p libepoxy-build
	$TERMUX_MESON $TERMUX_PKG_SRCDIR/libepoxy libepoxy-build \
		--cross-file $TERMUX_MESON_CROSSFILE \
		--prefix=$_INSTALL_PREFIX \
		--libdir lib -Ddefault_library=static \
		-Degl=yes -Dglx=no -Dx11=false
	ninja -C libepoxy-build install -j $TERMUX_PKG_MAKE_PROCESSES

	# Compile virglrenderer
	mkdir -p virglrenderer-build
	$TERMUX_MESON $TERMUX_PKG_SRCDIR virglrenderer-build \
		--cross-file $TERMUX_MESON_CROSSFILE \
		--prefix=$_INSTALL_PREFIX \
		--libdir lib -Ddefault_library=static \
		-Dvenus=true \
		-Dplatforms=egl
	ninja -C virglrenderer-build install -j $TERMUX_PKG_MAKE_PROCESSES

	# Move our virglrenderer binary to regular bin directory.
	mv $_INSTALL_PREFIX/bin/virgl_test_server $TERMUX_PREFIX/lib/libvirgl_vtest.so

	# Cleanup.
	rm -rf $_INSTALL_PREFIX/{bin,include,lib/pkgconfig}
}

termux_step_configure() {
	# Remove this marker all the time, as this package is architecture-specific
	rm -rf $TERMUX_HOSTBUILD_MARKER
}

termux_step_make() {
	:
}

termux_step_make_install() {
	:
}

termux_step_install_license() {
	mkdir -p $TERMUX_PREFIX/share/doc/$TERMUX_PKG_NAME
	cp $TERMUX_PKG_SRCDIR/COPYING $TERMUX_PREFIX/share/doc/$TERMUX_PKG_NAME/COPYING-virglrenderer
	cp $TERMUX_PKG_SRCDIR/libepoxy/COPYING $TERMUX_PREFIX/share/doc/$TERMUX_PKG_NAME/COPYING-libepoxy
	cp $TERMUX_PKG_BUILDER_DIR/COPYING-gl4es $TERMUX_PREFIX/share/doc/$TERMUX_PKG_NAME/COPYING-gl4es
}
