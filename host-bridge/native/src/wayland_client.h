#pragma once

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

    void* dispatchThreadHandle_ = nullptr; // pthread_t, opaque here to avoid pulling <pthread.h> into the header
    bool dispatchThreadRunning_ = false;

    void startDispatchThread();
    void stopDispatchThread();
};

} // namespace hostbridge
