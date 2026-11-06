// librime's plugins register themselves from static constructors
// (RIME_REGISTER_MODULE in rime_api.h), and librime's setup loads them by name
// (RIME_EXTRA_MODULES). Linked from a static archive, an object nothing refers
// to is dropped, constructor and all. librime.so links rime-static whole, and
// this names each plugin's rime_require_module_* as well, as Trime's
// rime_jni.cc does (declare_librime_module_dependencies), so a plugin left out
// fails the link instead of going missing at run time.
//
// RIME_REGISTER_MODULE defines these in C++ files outside extern "C", so they
// are declared here with C++ linkage, and this file is C++.

extern void rime_require_module_lua();
extern void rime_require_module_octagram();
extern void rime_require_module_predict();

__attribute__((used, visibility("hidden"))) void droidtop_rime_require_plugins() {
  rime_require_module_lua();
  rime_require_module_octagram();
  rime_require_module_predict();
}
