// droidtop's JNI over librime (Droidtop/tracker#903 H8b; docs/SPEC.md 6a, "Chinese through RIME").
//
// librime.so (rime-engine.pin's release) exports only its C entry points; this library reaches it through
// rime_get_api() alone and links no other symbol of it. The natives are dev.droidtop.input.chinese.RimeJni's,
// registered in JNI_OnLoad, and every call comes under RimeRuntime's lock, so nothing here locks.
//
// librime counts in UTF-8 bytes (RimeComposition's cursor_pos, sel_start and sel_end) and hands out UTF-8 text. This
// turns both into UTF-16 here, so Kotlin sees only chars, as the seam counts. Strings are made with NewString from
// UTF-16, never NewStringUTF, whose "modified UTF-8" mangles characters past U+FFFF (CJK Extension B, emoji).
//
// librime logs through glog. With a log_dir and alsologtostderr (librime's SetupLogging), glog also writes every
// message to logcat (its Android build's __android_log_write), tagged with the app_name below; min_log_level sets the
// floor. This library's own lines go to logcat as droidtop.Rime.

#include <android/log.h>
#include <jni.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "rime_api.h"
#include "utf.h"

#define LOG_TAG "droidtop.Rime"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char* kClass = "dev/droidtop/input/chinese/RimeJni";
// glog keeps this pointer as the program's name: a literal, so it outlives every call.
constexpr const char* kAppName = "rime.droidtop";
// The ints getContext returns, in RimeContext.kt's order.
constexpr int kInts = 6;

RimeApi* g_api = nullptr;
jclass g_string = nullptr;
jclass g_object = nullptr;
bool g_setup = false;
bool g_initialized = false;
bool g_deploy_failed = false;

// librime's notifications: a deployment's start, success or failure, a schema loaded, an option changed.
void OnNotification(void*, RimeSessionId session, const char* type, const char* value) {
  if (type == nullptr || value == nullptr) return;
  if (std::strcmp(type, "deploy") == 0) {
    if (std::strcmp(value, "failure") == 0) g_deploy_failed = true;
    LOGI("deploy: %s", value);
  } else if (std::strcmp(type, "schema") == 0) {
    LOGI("session %lu schema: %s", static_cast<unsigned long>(session), value);
  }
}

jstring ToJava(JNIEnv* env, const char* utf8, size_t length) {
  std::vector<jchar> utf16;
  droidtop::Utf8ToUtf16(utf8, length, &utf16);
  return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

jstring ToJava(JNIEnv* env, const char* utf8) {
  return ToJava(env, utf8 == nullptr ? "" : utf8, utf8 == nullptr ? 0 : std::strlen(utf8));
}

// A Java string as UTF-8 for librime (paths, schema ids and option names).
std::string FromJava(JNIEnv* env, jstring text) {
  if (text == nullptr) return std::string();
  const jsize length = env->GetStringLength(text);
  const jchar* chars = env->GetStringChars(text, nullptr);
  std::string out;
  if (chars != nullptr) {
    droidtop::Utf16ToUtf8(chars, static_cast<size_t>(length), &out);
    env->ReleaseStringChars(text, chars);
  }
  return out;
}

RimeSessionId Session(jlong session) { return static_cast<RimeSessionId>(session); }

jboolean Start(JNIEnv* env, jclass, jstring shared, jstring user, jstring log_dir, jint min_log_level, jstring version) {
  if (g_api == nullptr) return JNI_FALSE;
  const std::string shared_dir = FromJava(env, shared);
  const std::string user_dir = FromJava(env, user);
  const std::string logs = FromJava(env, log_dir);
  const std::string distribution_version = FromJava(env, version);
  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared_dir.c_str();
  traits.user_data_dir = user_dir.c_str();
  traits.distribution_name = "droidtop";
  traits.distribution_code_name = "droidtop";
  traits.distribution_version = distribution_version.c_str();
  traits.app_name = kAppName;
  traits.min_log_level = min_log_level;
  traits.log_dir = logs.c_str();
  if (!g_setup) {
    // setup starts glog, which may be started once per process: a restart after forgetting skips it.
    g_api->setup(&traits);
    g_setup = true;
  }
  g_api->set_notification_handler(OnNotification, nullptr);
  g_deploy_failed = false;
  g_api->initialize(&traits);
  g_initialized = true;
  // Deploys what changed since the last start (the downloaded data, a new pin's), and waits for it.
  if (g_api->start_maintenance(False)) g_api->join_maintenance_thread();
  LOGI("librime started over %s and %s", shared_dir.c_str(), user_dir.c_str());
  return g_deploy_failed ? JNI_FALSE : JNI_TRUE;
}

void Stop(JNIEnv*, jclass) {
  if (g_api == nullptr || !g_initialized) return;
  g_api->cleanup_all_sessions();
  g_api->finalize();
  g_initialized = false;
  LOGI("librime finalized");
}

jstring Version(JNIEnv* env, jclass) {
  const char* version = g_api != nullptr && RIME_API_AVAILABLE(g_api, get_version) ? g_api->get_version() : nullptr;
  return ToJava(env, version);
}

jlong CreateSession(JNIEnv*, jclass) {
  if (!g_initialized) return 0;
  return static_cast<jlong>(g_api->create_session());
}

jboolean DestroySession(JNIEnv*, jclass, jlong session) {
  if (!g_initialized) return JNI_FALSE;
  return g_api->destroy_session(Session(session)) ? JNI_TRUE : JNI_FALSE;
}

jboolean FindSession(JNIEnv*, jclass, jlong session) {
  if (!g_initialized || session == 0) return JNI_FALSE;
  return g_api->find_session(Session(session)) ? JNI_TRUE : JNI_FALSE;
}

jboolean SelectSchema(JNIEnv* env, jclass, jlong session, jstring schema) {
  if (!g_initialized) return JNI_FALSE;
  const std::string id = FromJava(env, schema);
  if (!g_api->select_schema(Session(session), id.c_str())) return JNI_FALSE;
  // librime selects any id, found or not. A schema whose configuration did not load is named by its id (or its id and
  // "?", with no configuration at all; Schema::FetchUsefulConfigItems), so a name of its own (schema/name) says it is
  // there.
  RIME_STRUCT(RimeStatus, status);
  if (!g_api->get_status(Session(session), &status)) return JNI_FALSE;
  const std::string name = status.schema_name != nullptr ? status.schema_name : "";
  const bool found = status.schema_id != nullptr && id == status.schema_id && !name.empty() && name != id &&
                     name != id + "?";
  g_api->free_status(&status);
  return found ? JNI_TRUE : JNI_FALSE;
}

void SetOption(JNIEnv* env, jclass, jlong session, jstring option, jboolean value) {
  if (!g_initialized) return;
  const std::string name = FromJava(env, option);
  g_api->set_option(Session(session), name.c_str(), value ? True : False);
}

jboolean ProcessKey(JNIEnv*, jclass, jlong session, jint keysym, jint mask) {
  if (!g_initialized) return JNI_FALSE;
  return g_api->process_key(Session(session), keysym, mask) ? JNI_TRUE : JNI_FALSE;
}

jstring GetCommit(JNIEnv* env, jclass, jlong session) {
  if (!g_initialized) return nullptr;
  RIME_STRUCT(RimeCommit, commit);
  if (!g_api->get_commit(Session(session), &commit)) return nullptr;
  jstring text = commit.text != nullptr && commit.text[0] != '\0' ? ToJava(env, commit.text) : nullptr;
  g_api->free_commit(&commit);
  return text;
}

void ClearComposition(JNIEnv*, jclass, jlong session) {
  if (!g_initialized) return;
  g_api->clear_composition(Session(session));
}

jboolean SelectCandidate(JNIEnv*, jclass, jlong session, jint index) {
  if (!g_initialized || index < 0) return JNI_FALSE;
  return g_api->select_candidate(Session(session), static_cast<size_t>(index)) ? JNI_TRUE : JNI_FALSE;
}

jobjectArray Strings(JNIEnv* env, const std::vector<std::string>& values, const std::vector<bool>& present) {
  jobjectArray array = env->NewObjectArray(static_cast<jsize>(values.size()), g_string, nullptr);
  if (array == nullptr) return nullptr;
  for (size_t i = 0; i < values.size(); ++i) {
    if (!present[i]) continue;
    jstring value = ToJava(env, values[i].data(), values[i].size());
    env->SetObjectArrayElement(array, static_cast<jsize>(i), value);
    env->DeleteLocalRef(value);
  }
  return array;
}

// { String preedit, int[] { cursor, selStart, selEnd, highlighted, pageSize, asciiMode }, String[] texts,
//   String[] comments }, offsets in UTF-16 chars, highlighted counted across librime's pages.
jobjectArray GetContext(JNIEnv* env, jclass, jlong session, jint limit) {
  if (!g_initialized) return nullptr;
  const RimeSessionId id = Session(session);
  RIME_STRUCT(RimeContext, context);
  if (!g_api->get_context(id, &context)) return nullptr;

  const char* preedit = context.composition.preedit != nullptr ? context.composition.preedit : "";
  const size_t length = std::strlen(preedit);
  jint ints[kInts];
  ints[0] = static_cast<jint>(droidtop::Utf16Offset(preedit, length, context.composition.cursor_pos));
  ints[1] = static_cast<jint>(droidtop::Utf16Offset(preedit, length, context.composition.sel_start));
  ints[2] = static_cast<jint>(droidtop::Utf16Offset(preedit, length, context.composition.sel_end));
  const RimeMenu& menu = context.menu;
  ints[3] = menu.num_candidates > 0 ? menu.page_no * menu.page_size + menu.highlighted_candidate_index : -1;
  ints[4] = menu.page_size;
  ints[5] = g_api->get_option(id, "ascii_mode") ? 1 : 0;
  jstring preedit_text = ToJava(env, preedit, length);
  const bool has_menu = menu.num_candidates > 0;
  g_api->free_context(&context);

  // Every candidate up to the limit, across librime's pages, so the bar pages them itself.
  std::vector<std::string> texts;
  std::vector<std::string> comments;
  std::vector<bool> has_text;
  std::vector<bool> has_comment;
  if (has_menu && limit > 0) {
    RimeCandidateListIterator iterator = {};
    if (g_api->candidate_list_begin(id, &iterator)) {
      while (static_cast<jint>(texts.size()) < limit && g_api->candidate_list_next(&iterator)) {
        const RimeCandidate& candidate = iterator.candidate;
        texts.emplace_back(candidate.text != nullptr ? candidate.text : "");
        has_text.push_back(true);
        comments.emplace_back(candidate.comment != nullptr ? candidate.comment : "");
        has_comment.push_back(candidate.comment != nullptr && candidate.comment[0] != '\0');
      }
      g_api->candidate_list_end(&iterator);
    }
  }

  jobjectArray answer = env->NewObjectArray(4, g_object, nullptr);
  if (answer == nullptr) return nullptr;
  jintArray numbers = env->NewIntArray(kInts);
  if (numbers == nullptr) return nullptr;
  env->SetIntArrayRegion(numbers, 0, kInts, ints);
  jobjectArray text_array = Strings(env, texts, has_text);
  jobjectArray comment_array = Strings(env, comments, has_comment);
  if (text_array == nullptr || comment_array == nullptr) return nullptr;
  env->SetObjectArrayElement(answer, 0, preedit_text);
  env->SetObjectArrayElement(answer, 1, numbers);
  env->SetObjectArrayElement(answer, 2, text_array);
  env->SetObjectArrayElement(answer, 3, comment_array);
  env->DeleteLocalRef(preedit_text);
  env->DeleteLocalRef(numbers);
  env->DeleteLocalRef(text_array);
  env->DeleteLocalRef(comment_array);
  return answer;
}

const JNINativeMethod kMethods[] = {
    {"start", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)Z", reinterpret_cast<void*>(Start)},
    {"stop", "()V", reinterpret_cast<void*>(Stop)},
    {"version", "()Ljava/lang/String;", reinterpret_cast<void*>(Version)},
    {"createSession", "()J", reinterpret_cast<void*>(CreateSession)},
    {"destroySession", "(J)Z", reinterpret_cast<void*>(DestroySession)},
    {"findSession", "(J)Z", reinterpret_cast<void*>(FindSession)},
    {"selectSchema", "(JLjava/lang/String;)Z", reinterpret_cast<void*>(SelectSchema)},
    {"setOption", "(JLjava/lang/String;Z)V", reinterpret_cast<void*>(SetOption)},
    {"processKey", "(JII)Z", reinterpret_cast<void*>(ProcessKey)},
    {"getCommit", "(J)Ljava/lang/String;", reinterpret_cast<void*>(GetCommit)},
    {"clearComposition", "(J)V", reinterpret_cast<void*>(ClearComposition)},
    {"selectCandidate", "(JI)Z", reinterpret_cast<void*>(SelectCandidate)},
    {"getContext", "(JI)[Ljava/lang/Object;", reinterpret_cast<void*>(GetContext)},
};

jclass GlobalClass(JNIEnv* env, const char* name) {
  jclass local = env->FindClass(name);
  if (local == nullptr) return nullptr;
  jclass global = static_cast<jclass>(env->NewGlobalRef(local));
  env->DeleteLocalRef(local);
  return global;
}

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  g_api = rime_get_api();
  if (g_api == nullptr) {
    LOGW("rime_get_api gave no API");
    return JNI_ERR;
  }
  // The API is versioned by its size: the later members this library needs must be there (get_version is checked
  // where it is used).
  if (!RIME_API_AVAILABLE(g_api, candidate_list_begin) || !RIME_API_AVAILABLE(g_api, select_candidate)) {
    LOGW("librime's API is older than this library needs");
    return JNI_ERR;
  }
  g_string = GlobalClass(env, "java/lang/String");
  g_object = GlobalClass(env, "java/lang/Object");
  jclass rime = env->FindClass(kClass);
  if (g_string == nullptr || g_object == nullptr || rime == nullptr) return JNI_ERR;
  const jint count = static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0]));
  const jint registered = env->RegisterNatives(rime, kMethods, count);
  env->DeleteLocalRef(rime);
  if (registered != JNI_OK) return JNI_ERR;
  return JNI_VERSION_1_6;
}
