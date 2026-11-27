package dev.droidtop.pluginhost;

import android.os.IBinder;
import android.view.SurfaceControlViewHost.SurfacePackage;

/** API 30+ only. Every operation is scoped to the existing one-use web.view token. */
interface IWebViewSurfaceProvider {
    oneway void open(String token, IBinder hostToken, int displayId, int width, int height, int densityDpi);
    // Return-value parcel flags transfer the SurfacePackage's ownership correctly.
    SurfacePackage surface(String token);
    oneway void resize(String token, int width, int height, int densityDpi);
    oneway void back(String token);
    oneway void close(String token);
}
