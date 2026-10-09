#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

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
struct DispatchTasks;

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

/**
 * Called on the dispatch thread (see wayland_client.cpp) whenever the
 * container-side toplevel list changes — a window opened, closed, or had
 * its title/app-id/activated/minimized/maximized state change. Carries no
 * data: the callee (hostbridge_jni.cpp's trampoline) is expected to call
 * back into snapshotToplevels() for the current list, the same
 * fetch-on-notify shape as everything else this class exposes to Kotlin.
 */
using ToplevelsChangedCallback = void (*)(void* userData);

/**
 * One opened window as wlr-foreign-toplevel-management-unstable-v1 reports
 * it. `id` is the zwlr_foreign_toplevel_handle_v1 pointer reinterpreted as
 * an integer — stable for the handle's lifetime, which is exactly the
 * lifetime a taskbar row needs it for, and never reused while that handle
 * is still alive (a fresh object gets a fresh pointer).
 */
struct ToplevelInfo {
    uint64_t id = 0;
    std::string title;
    std::string appId;
    bool activated = false;
    bool minimized = false;
    bool maximized = false;
    bool fullscreen = false;
};

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

    // Asks the compositor, over wlr-output-management, to give its output
    // this exact size (a custom mode: a headless output accepts any). The
    // Android view showing the desktop calls this with its own size, so a
    // captured frame maps 1:1 onto the view instead of being stretched.
    // Applied as soon as the compositor has announced its output; returns
    // false only when it offers no output management at all. [scale] is the
    // output's scale (logical pixel to physical pixels); 0 leaves the
    // compositor's own.
    bool setOutputSize(int32_t width, int32_t height, double scale);

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

    // ---- Windows, over wlr-foreign-toplevel-management-unstable-v1 ----
    //
    // Non-fatal when the compositor doesn't advertise this global (like the
    // clipboard's ext-data-control-v1 below): the desktop's own single
    // presented surface still works, the taskbar just has no window list to
    // show. Registers the sink the same way setClipboardListener does —
    // before connect() is not required here since the manager delivers the
    // FULL current toplevel list as a burst of events right after binding,
    // and a listener set any time before the first snapshotToplevels() call
    // sees it.
    void setToplevelsListener(ToplevelsChangedCallback callback, void* userData);

    // A copy of the current list, safe to call from any thread. Entries are
    // only ever mutated on the dispatch thread; reads take the same mutex
    // that guards those mutations (see ToplevelState in the .cpp).
    std::vector<ToplevelInfo> snapshotToplevels();

    // Requests below run on the dispatch thread (like setOutputSize) rather
    // than marshaling the Wayland request directly from the calling thread:
    // the handle they act on can be destroyed by a `closed` event arriving
    // on the dispatch thread at any time, and only that thread is allowed to
    // decide a given id is still live. Each returns false when `id` no
    // longer names a live toplevel (already closed, or never existed) or
    // when the compositor offers no foreign-toplevel-management at all.
    bool activateToplevel(uint64_t id);
    bool setToplevelMinimized(uint64_t id, bool minimized);
    bool closeToplevel(uint64_t id);

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
    struct ToplevelState* toplevels_ = nullptr;

    void* dispatchThreadHandle_ = nullptr; // pthread_t, opaque here to avoid pulling <pthread.h> into the header
    std::atomic<bool> dispatchThreadRunning_{false};

    // Wakes the dispatch thread out of poll(): posted tasks, shutdown.
    int wakeFd_ = -1;
    DispatchTasks* tasks_ = nullptr;

    void startDispatchThread();
    void stopDispatchThread();

    // Runs `task` on the dispatch thread and waits for it: everything that
    // touches capture or output-configuration state goes through here, so
    // that state has exactly one thread. Runs inline when there is no
    // dispatch thread (before connect() finishes, after it has exited).
    void runOnDispatchThread(void (*task)(WaylandClient*, void*), void* arg);
    void runPendingTasks();
    void wake();

    void stopPresentingOnDispatchThread();
    void applyOutputSizeOnDispatchThread();
};

} // namespace hostbridge
