// Windows host replay only: the subset of <dlfcn.h> the production plugins use, over the Win32 loader.
#pragma once
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>

#define RTLD_LAZY 0x0001
#define RTLD_NOW 0x0002
#define RTLD_LOCAL 0x0000
#define RTLD_GLOBAL 0x0100

static inline void* dlopen(const char* path, int) {
  return path ? static_cast<void*>(LoadLibraryA(path)) : static_cast<void*>(GetModuleHandleA(nullptr));
}
static inline void* dlsym(void* handle, const char* name) {
  return reinterpret_cast<void*>(GetProcAddress(static_cast<HMODULE>(handle), name));
}
static inline int dlclose(void* handle) { return FreeLibrary(static_cast<HMODULE>(handle)) ? 0 : -1; }
static inline const char* dlerror() {
  static thread_local char buffer[64];
  DWORD code = GetLastError();
  if (!code) return nullptr;
  wsprintfA(buffer, "Win32 loader error %lu", code);
  SetLastError(0);
  return buffer;
}
