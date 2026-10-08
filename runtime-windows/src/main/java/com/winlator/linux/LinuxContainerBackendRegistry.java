package com.winlator.linux;

/**
 * The real swap point per direction: gamenative-tux ships and defaults to
 * {@link DefaultProotContainerBackend} (real, working standalone), but a
 * host app -- droidtop, wiring this fork in as a library -- can call
 * {@link #set} once at startup to replace it with its own backend (its
 * real `runtime-linux-noroot` proot implementation or `runtime-linux-root`
 * droidspaces/namespace implementation), and every real caller (currently
 * just {@code GuestProgramLauncherComponent}, eventually a native-Linux-
 * game launch path too) picks up the swap automatically without knowing
 * who's hosting it.
 */
public final class LinuxContainerBackendRegistry {
    private static volatile LinuxContainerBackend instance = new DefaultProotContainerBackend();

    private LinuxContainerBackendRegistry() {}

    public static LinuxContainerBackend get() {
        return instance;
    }

    /** Real override point -- call once, early (e.g. Application.onCreate), before any real exec happens. */
    public static void set(LinuxContainerBackend backend) {
        instance = backend;
    }
}
