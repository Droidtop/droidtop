package com.winlator.linux;

import com.winlator.core.envvars.EnvVars;

import java.io.File;

/**
 * Everything a {@link LinuxContainerBackend} needs to run one guest command
 * inside a Linux rootfs -- extracted from what {@code
 * GuestProgramLauncherComponent.exec()} already builds inline for its own
 * proot invocation (see {@link DefaultProotContainerBackend}'s own doc
 * comment). Plain data holder, no behavior -- the point of splitting this
 * out is that a different backend (droidspaces, a shared host container,
 * whatever droidtop wires in) needs the same real inputs without needing
 * to know proot's own command-line shape.
 */
public class LinuxExecConfig {
    public final File rootDir;
    public final File workingDir;
    public final String command;
    public final String[] bindingPaths;
    public final EnvVars envVars;
    public final boolean proot32;
    // Whether to bind a real /tmp/shm dir to the guest's /dev/shm -- kept as
    // an explicit flag the caller computes, not re-derived from envVars.get
    // ("WINEESYNC") inside the backend: the real original code (see
    // DefaultProotContainerBackend's own doc comment) checks that value
    // *before* forcing WINEESYNC to "0" for the actual guest env, so
    // deriving it from the final envVars here would always read "0" and
    // silently break real esync SHM binding.
    public final boolean bindShm;

    public LinuxExecConfig(File rootDir, File workingDir, String command, String[] bindingPaths, EnvVars envVars, boolean proot32, boolean bindShm) {
        this.rootDir = rootDir;
        this.workingDir = workingDir;
        this.command = command;
        this.bindingPaths = bindingPaths;
        this.envVars = envVars;
        this.proot32 = proot32;
        this.bindShm = bindShm;
    }
}
