/*
 * A plugin's SQLite, droidtop's own (docs/plugin-api.md 5.3, "Preferences and SQLite are droidtop's",
 * Droidtop/tracker#741): the pinned SQLite amalgamation built into this library, reached from
 * dev.droidtop.pluginhost.SqliteNative. Android's own SQLite is never used for a plugin: opening one in an isolated
 * process first reads a global setting through a content provider, which such a process may not call.
 *
 * Every function is a thin call; a failure throws dev.droidtop.pluginhost.SqliteException with SQLite's extended result
 * code and its own message, which the Kotlin side words the way Android does.
 */
#include <jni.h>
#include <stdint.h>
#include <string.h>

#include "sqlite3.h"

#define DB(handle) ((sqlite3 *) (intptr_t) (handle))
#define STMT(handle) ((sqlite3_stmt *) (intptr_t) (handle))

/* A jstring from SQLite's UTF-16 text (sqlite3_errmsg16, column names and values), never from modified UTF-8. */
static jstring utf16(JNIEnv *env, const void *text, int bytes) {
    if (text == NULL) return NULL;
    if (bytes < 0) {
        const jchar *chars = text;
        bytes = 0;
        while (chars[bytes / 2] != 0) bytes += 2;
    }
    return (*env)->NewString(env, text, bytes / 2);
}

static void fail(JNIEnv *env, int code, jstring message) {
    jclass type = (*env)->FindClass(env, "dev/droidtop/pluginhost/SqliteException");
    if (type == NULL) return;
    jmethodID init = (*env)->GetMethodID(env, type, "<init>", "(ILjava/lang/String;)V");
    if (init == NULL) return;
    jobject error = (*env)->NewObject(env, type, init, code, message);
    if (error != NULL) (*env)->Throw(env, (jthrowable) error);
}

static void fail_db(JNIEnv *env, sqlite3 *db, int rc) {
    if (db == NULL) {
        fail(env, rc, (*env)->NewStringUTF(env, sqlite3_errstr(rc)));
        return;
    }
    fail(env, sqlite3_extended_errcode(db), utf16(env, sqlite3_errmsg16(db), -1));
}

JNIEXPORT jlong JNICALL
Java_dev_droidtop_pluginhost_SqliteNative_open(JNIEnv *env, jclass clazz, jstring path, jboolean read_only) {
    (void) clazz;
    const char *name = (*env)->GetStringUTFChars(env, path, NULL);
    if (name == NULL) return 0;
    sqlite3 *db = NULL;
    int flags = (read_only ? SQLITE_OPEN_READONLY : SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE) | SQLITE_OPEN_FULLMUTEX;
    int rc = sqlite3_open_v2(name, &db, flags, NULL);
    (*env)->ReleaseStringUTFChars(env, path, name);
    if (rc != SQLITE_OK) {
        fail_db(env, db, rc);
        sqlite3_close_v2(db);
        return 0;
    }
    sqlite3_extended_result_codes(db, 1);
    /* Android's own wait for a locked database. */
    sqlite3_busy_timeout(db, 2500);
    return (jlong) (intptr_t) db;
}

JNIEXPORT void JNICALL Java_dev_droidtop_pluginhost_SqliteNative_close(JNIEnv *env, jclass clazz, jlong db) {
    (void) env;
    (void) clazz;
    sqlite3_close_v2(DB(db));
}

/* The first statement of [sql] only, as Android's execSQL and compileStatement do; 0 for text with no statement. */
JNIEXPORT jlong JNICALL
Java_dev_droidtop_pluginhost_SqliteNative_prepare(JNIEnv *env, jclass clazz, jlong db, jstring sql) {
    (void) clazz;
    const jchar *text = (*env)->GetStringChars(env, sql, NULL);
    if (text == NULL) return 0;
    jsize length = (*env)->GetStringLength(env, sql);
    sqlite3_stmt *stmt = NULL;
    int rc = sqlite3_prepare16_v2(DB(db), text, (int) (length * sizeof(jchar)), &stmt, NULL);
    (*env)->ReleaseStringChars(env, sql, text);
    if (rc != SQLITE_OK) {
        fail_db(env, DB(db), rc);
        return 0;
    }
    return (jlong) (intptr_t) stmt;
}

JNIEXPORT void JNICALL Java_dev_droidtop_pluginhost_SqliteNative_finalize(JNIEnv *env, jclass clazz, jlong stmt) {
    (void) env;
    (void) clazz;
    sqlite3_finalize(STMT(stmt));
}

static void bound(JNIEnv *env, jlong db, int rc) {
    if (rc != SQLITE_OK) fail_db(env, DB(db), rc);
}

JNIEXPORT void JNICALL
Java_dev_droidtop_pluginhost_SqliteNative_bindNull(JNIEnv *env, jclass clazz, jlong db, jlong stmt, jint index) {
    (void) clazz;
    bound(env, db, sqlite3_bind_null(STMT(stmt), index));
}

JNIEXPORT void JNICALL
Java_dev_droidtop_pluginhost_SqliteNative_bindLong(JNIEnv *env, jclass clazz, jlong db, jlong stmt, jint index, jlong value) {
    (void) clazz;
    bound(env, db, sqlite3_bind_int64(STMT(stmt), index, value));
}

JNIEXPORT void JNICALL
Java_dev_droidtop_pluginhost_SqliteNative_bindDouble(JNIEnv *env, jclass clazz, jlong db, jlong stmt, jint index, jdouble value) {
    (void) clazz;
    bound(env, db, sqlite3_bind_double(STMT(stmt), index, value));
}

JNIEXPORT void JNICALL
Java_dev_droidtop_pluginhost_SqliteNative_bindText(JNIEnv *env, jclass clazz, jlong db, jlong stmt, jint index, jstring value) {
    (void) clazz;
    const jchar *text = (*env)->GetStringChars(env, value, NULL);
    if (text == NULL) return;
    jsize length = (*env)->GetStringLength(env, value);
    int rc = sqlite3_bind_text16(STMT(stmt), index, text, (int) (length * sizeof(jchar)), SQLITE_TRANSIENT);
    (*env)->ReleaseStringChars(env, value, text);
    bound(env, db, rc);
}

JNIEXPORT void JNICALL
Java_dev_droidtop_pluginhost_SqliteNative_bindBlob(JNIEnv *env, jclass clazz, jlong db, jlong stmt, jint index, jbyteArray value) {
    (void) clazz;
    jsize length = (*env)->GetArrayLength(env, value);
    jbyte *bytes = (*env)->GetByteArrayElements(env, value, NULL);
    if (bytes == NULL) return;
    int rc = sqlite3_bind_blob(STMT(stmt), index, bytes, length, SQLITE_TRANSIENT);
    (*env)->ReleaseByteArrayElements(env, value, bytes, JNI_ABORT);
    bound(env, db, rc);
}

/* SQLITE_ROW (100) or SQLITE_DONE (101); anything else throws. */
JNIEXPORT jint JNICALL Java_dev_droidtop_pluginhost_SqliteNative_step(JNIEnv *env, jclass clazz, jlong db, jlong stmt) {
    (void) clazz;
    int rc = sqlite3_step(STMT(stmt));
    if (rc != SQLITE_ROW && rc != SQLITE_DONE) fail_db(env, DB(db), rc);
    return rc;
}

JNIEXPORT jint JNICALL Java_dev_droidtop_pluginhost_SqliteNative_columnCount(JNIEnv *env, jclass clazz, jlong stmt) {
    (void) env;
    (void) clazz;
    return sqlite3_column_count(STMT(stmt));
}

JNIEXPORT jstring JNICALL
Java_dev_droidtop_pluginhost_SqliteNative_columnName(JNIEnv *env, jclass clazz, jlong stmt, jint index) {
    (void) clazz;
    return utf16(env, sqlite3_column_name16(STMT(stmt), index), -1);
}

/* SQLITE_INTEGER 1, SQLITE_FLOAT 2, SQLITE_TEXT 3, SQLITE_BLOB 4, SQLITE_NULL 5. */
JNIEXPORT jint JNICALL
Java_dev_droidtop_pluginhost_SqliteNative_columnType(JNIEnv *env, jclass clazz, jlong stmt, jint index) {
    (void) env;
    (void) clazz;
    return sqlite3_column_type(STMT(stmt), index);
}

JNIEXPORT jlong JNICALL
Java_dev_droidtop_pluginhost_SqliteNative_columnLong(JNIEnv *env, jclass clazz, jlong stmt, jint index) {
    (void) env;
    (void) clazz;
    return sqlite3_column_int64(STMT(stmt), index);
}

JNIEXPORT jdouble JNICALL
Java_dev_droidtop_pluginhost_SqliteNative_columnDouble(JNIEnv *env, jclass clazz, jlong stmt, jint index) {
    (void) env;
    (void) clazz;
    return sqlite3_column_double(STMT(stmt), index);
}

JNIEXPORT jstring JNICALL
Java_dev_droidtop_pluginhost_SqliteNative_columnText(JNIEnv *env, jclass clazz, jlong stmt, jint index) {
    (void) clazz;
    const void *text = sqlite3_column_text16(STMT(stmt), index);
    if (text == NULL) return (*env)->NewString(env, NULL, 0);
    return utf16(env, text, sqlite3_column_bytes16(STMT(stmt), index));
}

JNIEXPORT jbyteArray JNICALL
Java_dev_droidtop_pluginhost_SqliteNative_columnBlob(JNIEnv *env, jclass clazz, jlong stmt, jint index) {
    (void) clazz;
    const void *bytes = sqlite3_column_blob(STMT(stmt), index);
    int length = sqlite3_column_bytes(STMT(stmt), index);
    jbyteArray out = (*env)->NewByteArray(env, length);
    if (out != NULL && length > 0) (*env)->SetByteArrayRegion(env, out, 0, length, bytes);
    return out;
}

JNIEXPORT jint JNICALL Java_dev_droidtop_pluginhost_SqliteNative_changes(JNIEnv *env, jclass clazz, jlong db) {
    (void) env;
    (void) clazz;
    return sqlite3_changes(DB(db));
}

JNIEXPORT jlong JNICALL Java_dev_droidtop_pluginhost_SqliteNative_lastInsertRowid(JNIEnv *env, jclass clazz, jlong db) {
    (void) env;
    (void) clazz;
    return sqlite3_last_insert_rowid(DB(db));
}

/* False while a transaction is open: SQLite's own answer, also after it rolled one back by itself. */
JNIEXPORT jboolean JNICALL Java_dev_droidtop_pluginhost_SqliteNative_autocommit(JNIEnv *env, jclass clazz, jlong db) {
    (void) env;
    (void) clazz;
    return sqlite3_get_autocommit(DB(db)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_dev_droidtop_pluginhost_SqliteNative_version(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, sqlite3_libversion());
}
