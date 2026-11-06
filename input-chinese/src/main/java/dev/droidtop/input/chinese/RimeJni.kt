package dev.droidtop.input.chinese

/**
 * droidtop's JNI over librime's C API (Droidtop/tracker#903 H8b; src/main/cpp/rime_jni.cc, libdroidtop_rime.so). The
 * natives are registered by this object's class name and theirs (JNI_OnLoad), which a consumer R8 rule keeps. librime
 * counts in UTF-8 bytes; every string and offset here is already UTF-16, as Kotlin's are.
 *
 * Not thread-safe: [RimeRuntime] makes every call under one lock.
 */
internal object RimeJni {
    /**
     * Sets librime up once for the process (glog may be started only once), then initializes it over [shared] and
     * [user], runs its maintenance (start_maintenance(false), deploying what changed) and waits for it. librime logs to
     * logcat at [minLogLevel] and above (0 info, 1 warning, 2 error), through glog, which also writes into [logDir].
     * False when librime reported the deployment failed.
     */
    @JvmStatic external fun start(shared: String, user: String, logDir: String, minLogLevel: Int, version: String): Boolean

    /** Ends every session and finalizes librime, closing its databases. [start] may follow. */
    @JvmStatic external fun stop()

    /** librime's version, or empty. */
    @JvmStatic external fun version(): String

    @JvmStatic external fun createSession(): Long

    @JvmStatic external fun destroySession(session: Long): Boolean

    @JvmStatic external fun findSession(session: Long): Boolean

    /** Selects [schema] in [session]: false when the data has no such schema (librime itself selects any id). */
    @JvmStatic external fun selectSchema(session: Long, schema: String): Boolean

    @JvmStatic external fun setOption(session: Long, option: String, value: Boolean)

    @JvmStatic external fun processKey(session: Long, keysym: Int, mask: Int): Boolean

    /** The commit librime holds for [session] (taken, so asked once), or null. */
    @JvmStatic external fun getCommit(session: Long): String?

    @JvmStatic external fun clearComposition(session: Long)

    @JvmStatic external fun selectCandidate(session: Long, index: Int): Boolean

    /**
     * The session's context, as [RimeContext.fromJni] reads it: { String preedit, int[] { cursor, selStart, selEnd,
     * highlighted, pageSize, asciiMode }, String[] texts, String[] comments }, at most [limit] candidates. Null when
     * librime has no context for it.
     */
    @JvmStatic external fun getContext(session: Long, limit: Int): Array<Any?>?
}
