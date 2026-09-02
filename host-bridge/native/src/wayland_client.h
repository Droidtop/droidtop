#pragma once

#include <cstddef>
#include <cstdint>

// Thin C++ wrapper around a wl_display connection to the primary container's
// compositor, plus the registry globals hostbridge needs. See wayland_client.cpp
// for what's implemented vs. TODO.

// Forward-declared in the GLOBAL namespace deliberately: using `struct
// wl_display*` etc. directly inside `namespace hostbridge` below, without
// this, silently declares a NEW, distinct `hostbridge::wl_display` type
// (elaborated-type-specifier lookup rules) instead of referring to the real
// one from <wayland-client.h> — caught when wl_display_roundtrip() calls in
// wayland_client.cpp failed to compile against "an incomplete type
// hostbridge::wl_display", not the real wl_display.
struct wl_display;
struct wl_registry;
struct ANativeWindow;

namespace hostbridge {

struct WaylandGlobals;
struct OutputCapture;
struct ClipboardState;

/**
 * Called on a hostbridge-owned worker thread (NOT the Wayland dispatch
 * thread) whenever the container's selection changes and its text has been
 * read off the transfer pipe. `utf8Text` is `length` bytes of real UTF-8,
 * valid only for the duration of the call.
 *
 * Length-explicit, and bytes rather than a C string, on purpose: this text
 * crosses into Java, and JNI's NewStringUTF/GetStringUTFChars speak MODIFIED
 * UTF-8, which disagrees with real UTF-8 on every supplementary-plane
 * character — i.e. on emoji, which people copy constantly. hostbridge_jni.cpp
 * therefore moves a byte[] across and lets Kotlin do the decoding.
 *
 * Deliberately a plain function pointer + opaque user data rather than a
 * std::function: the only implementation is hostbridge_jni.cpp's JNI
 * trampoline, and this keeps the header free of <functional>.
 */
using ClipboardTextCallback = void (*)(void* userData, const char* utf8Text, size_t length);

class WaylandClient {
public:
    ~WaylandClient();

    // Connects to a Wayland compositor listening on a UNIX socket at
    // `socketPath` (a path INTO the primary container's filesystem/mount
    // namespace — not a bare $WAYLAND_DISPLAY name, since this process is
    // outside that container's namespace and must reach the socket file
    // directly). Returns false on any failure (socket open, wl_display
    // connect, or required globals missing from the registry). Starts a
    // background dispatch thread on success (see .cpp — this is what makes
    // async screencopy frame delivery work without the caller polling).
    bool connect(const char* socketPath);

    void disconnect();

    bool isConnected() const { return display_ != nullptr; }

    // Starts (or restarts, if already presenting) a continuous screencopy
    // capture loop targeting the primary output — MVP is single-output only,
    // matching the current DisplayOutput model's merged-desktop default; see
    // README for what multi-output would need. `window` must stay valid
    // until stopPresenting() or disconnect(); the caller (HostBridge.kt) owns
    // its lifetime via ANativeWindow_acquire/release around the JNI call.
    bool presentPrimaryOutput(ANativeWindow* window);
    void stopPresenting();

    // Input injection — safe to call from a thread other than the one that
    // called connect()/runs the dispatch loop. libwayland-client's requests
    // (proxy marshaling) are documented thread-safe independent of a
    // concurrent dispatch thread, AS LONG AS dispatch itself is only ever
    // called from one thread — which is exactly this class's design (see
    // .cpp's dispatch thread). No additional locking needed here for that
    // reason; don't add a mutex "just in case" without re-reading that
    // guarantee first.
    void injectPointerMotion(double dx, double dy);
    void injectPointerMotionAbsolute(double x, double y, uint32_t extentWidth, uint32_t extentHeight);
    void injectPointerButton(uint32_t linuxButtonCode, bool pressed);
    void injectPointerAxis(double horizontal, double vertical);
    void injectKey(uint32_t evdevKeyCode, bool pressed);

    // ---- Clipboard, over ext-data-control-v1 ----
    //
    // Registers the sink for container -> Android text. Must be set before
    // connect() to be sure of catching the selection the compositor already
    // holds; setting it later only catches subsequent changes. Passing
    // nullptr detaches.
    void setClipboardListener(ClipboardTextCallback callback, void* userData);

    // Android -> container: claims the container seat's selection with
    // `length` bytes of UTF-8 as a text/plain payload. Returns false if the
    // compositor never advertised ext_data_control_manager_v1 (so nothing was
    // claimed), or if the text exceeds kMaxClipboardBytes. Safe to call from
    // any thread, for the same libwayland reason as the injection methods
    // above.
    bool offerClipboardText(const char* utf8Text, size_t length);

    // Both directions refuse payloads above this. A clipboard bridge is for
    // text a person copied, not a transport: an unbounded payload here would
    // be copied through a pipe, a JNI string and an Android ClipData, and a
    // runaway one would be invisible to the user who triggered it.
    static constexpr size_t kMaxClipboardBytes = 1u << 20; // 1 MiB

    // Public only so the free-function pthread trampoline in
    // wayland_client.cpp (pthread_create needs a plain function pointer,
    // not a bound member function) can call it — not part of the intended
    // external API otherwise. Don't call this from outside the dispatch
    // thread it's designed to run on.
    void dispatchLoop();

private:
    struct wl_display* display_ = nullptr;
    struct wl_registry* registry_ = nullptr;
    WaylandGlobals* globals_ = nullptr;
    OutputCapture* capture_ = nullptr;
    ClipboardState* clipboard_ = nullptr;

    void* dispatchThreadHandle_ = nullptr; // pthread_t, opaque here to avoid pulling <pthread.h> into the header
    bool dispatchThreadRunning_ = false;

    void startDispatchThread();
    void stopDispatchThread();
};

} // namespace hostbridge
