/* Windows host replay only: the subset of <unistd.h> the production sources use. */
#pragma once
#include <io.h>
#include <process.h>
#include <stdint.h>
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#ifndef F_OK
#define F_OK 0
#endif
#ifndef R_OK
#define R_OK 4
#endif
#ifndef W_OK
#define W_OK 2
#endif
typedef intptr_t ssize_t;
static inline int usleep(unsigned int microseconds) { Sleep((microseconds + 999) / 1000); return 0; }
#ifndef _SC_PAGESIZE
#define _SC_PAGESIZE 30
#endif
static inline long sysconf(int name) {
  if (name == _SC_PAGESIZE) { SYSTEM_INFO info; GetSystemInfo(&info); return (long)info.dwPageSize; }
  return -1;
}
