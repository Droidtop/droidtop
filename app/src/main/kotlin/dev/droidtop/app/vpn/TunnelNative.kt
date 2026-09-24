package dev.droidtop.app.vpn

/**
 * vendor/hev-socks5-tunnel's JNI entry points. build-vendor-deps.sh
 * compiles the library with this class as its PKGNAME/CLSNAME, and its
 * JNI_OnLoad registers the natives here by name, so the name and the
 * signatures are fixed by src/hev-jni.c.
 */
internal object TunnelNative {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    /** Starts the stack on its own thread, reading its YAML config from [configPath] and packets from [fd]. */
    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic external fun TProxyStopService(): Boolean

    @JvmStatic external fun TProxyIsRunning(): Boolean

    /** tx packets, tx bytes, rx packets, rx bytes. */
    @JvmStatic external fun TProxyGetStats(): LongArray?
}
