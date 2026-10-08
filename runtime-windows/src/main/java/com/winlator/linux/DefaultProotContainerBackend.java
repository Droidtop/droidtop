package com.winlator.linux;

import android.content.Context;

import com.winlator.core.Callback;
import com.winlator.core.ProcessHelper;
import com.winlator.core.envvars.EnvVars;
import com.winlator.xenvironment.ImageFs;
import com.winlator.xenvironment.XEnvironment;

import java.io.File;

/**
 * Real, working default backend -- the actual proot invocation {@code
 * GuestProgramLauncherComponent.exec()} already built inline (bundled
 * {@code libproot.so}, {@code --rootfs}/{@code --bind}/{@code --cwd},
 * {@code PROOT_LOADER}/{@code PROOT_TMP_DIR} env vars), moved here
 * unchanged so gamenative-tux keeps working standalone with zero behavior
 * change. {@link GuestProgramLauncherComponent} still owns building the
 * Wine-specific guest env (TZ/HOME/PATH/LD_LIBRARY_PATH/etc.) -- only the
 * generic "run this command inside this rootfs via proot" mechanics moved,
 * which is the actual real seam a host app needs to swap, not the Wine
 * env-building (that stays Wine-specific either way).
 */
public class DefaultProotContainerBackend implements LinuxContainerBackend {
    @Override
    public int exec(Context context, LinuxExecConfig config, Callback<Integer> terminationCallback) {
        File tmpDir = XEnvironment.getTmpDir(context);
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;

        EnvVars envVars = config.envVars != null ? config.envVars : new EnvVars();

        String command = nativeLibraryDir + "/libproot.so";
        command += " --kill-on-exit";
        command += " --rootfs=" + config.rootDir;
        command += " --cwd=" + ImageFs.HOME_PATH;
        command += " --bind=/dev";

        if (config.bindShm) {
            File shmDir = new File(config.rootDir, "/tmp/shm");
            shmDir.mkdirs();
            command += " --bind=" + shmDir.getAbsolutePath() + ":/dev/shm";
        }

        command += " --bind=/proc";
        command += " --bind=/sys";

        if (config.bindingPaths != null) {
            for (String path : config.bindingPaths) {
                command += " --bind=\"" + (new File(path)).getAbsolutePath() + "\"";
            }
        }

        command += " /usr/bin/env " + envVars.toEscapedString() + " " + config.command;

        EnvVars prootEnvVars = new EnvVars();
        prootEnvVars.put("PROOT_TMP_DIR", tmpDir);
        prootEnvVars.put("PROOT_LOADER", nativeLibraryDir + "/libproot-loader.so");
        if (config.proot32) prootEnvVars.put("PROOT_LOADER_32", nativeLibraryDir + "/libproot-loader32.so");

        return ProcessHelper.exec(
                command,
                prootEnvVars.toStringArray(),
                config.workingDir != null ? config.workingDir : config.rootDir,
                terminationCallback
        );
    }
}
