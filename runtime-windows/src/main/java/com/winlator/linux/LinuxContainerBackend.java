package com.winlator.linux;

import android.content.Context;

import com.winlator.core.Callback;

/**
 * Real, swappable seam for "run a guest command inside a Linux rootfs" --
 * per direction, gamenative-tux's own Linux backend needs to be modular so
 * it can use its own bundled proot implementation standalone ({@link
 * DefaultProotContainerBackend}), while droidtop (or any other host app
 * forking this in) can supply its own instead -- droidtop's own real
 * proot (`runtime-linux-noroot`) or droidspaces/namespace (`runtime-linux-
 * root`) backends, so there's one real container-execution path shared
 * across Wine games, native Linux games, and droidtop's own container
 * model, not several independent copies of the same proot invocation.
 *
 * Swap the active backend via {@link LinuxContainerBackendRegistry}, not by
 * changing call sites -- {@code GuestProgramLauncherComponent} and any
 * future native-Linux-game launch path both go through the registry, so a
 * host app overrides this once at startup and every real caller picks it
 * up automatically.
 */
public interface LinuxContainerBackend {
    /**
     * Runs {@code config.command} inside {@code config.rootDir}, returning
     * the guest process's real pid (matching {@code ProcessHelper.exec}'s
     * own real return convention -- {@code -1} on failure to start).
     * {@code terminationCallback}, if non-null, fires with the real exit
     * status once the process ends.
     */
    int exec(Context context, LinuxExecConfig config, Callback<Integer> terminationCallback);
}
