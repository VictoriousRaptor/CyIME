// Windows host replay only: a Windows DLL exports nothing by default, so lexicon_regression.py (ctypes) reaches the
// Rime C API and the app's candidate policy through these two C entry points. The JNI entry points are exported
// by JNIEXPORT already.
#include <rime_api.h>
#include <string>

#include "candidate_policy.h"

extern "C" __declspec(dllexport) RimeApi* cyime_host_rime_get_api() { return rime_get_api(); }

extern "C" __declspec(dllexport) void cyime_host_initialize_candidate_policy(const char* user_dir) {
  cyime::InitializeCandidatePolicy(user_dir ? std::string(user_dir) : std::string());
}
