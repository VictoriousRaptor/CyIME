/* Windows host replay only: read-only file mmap/munmap over the Win32 file-mapping API. */
#pragma once
#include <io.h>
#include <stddef.h>
#include <sys/types.h>
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>

#define PROT_NONE 0
#define PROT_READ 1
#define PROT_WRITE 2
#define MAP_SHARED 1
#define MAP_PRIVATE 2
#define MAP_FAILED ((void*)-1)
#define MADV_NORMAL 0
#define MADV_RANDOM 1
#define MADV_SEQUENTIAL 2
#define MADV_WILLNEED 3
#define MADV_DONTNEED 4

static inline void* mmap(void* addr, size_t length, int prot, int flags, int fd, off_t offset) {
  (void)addr; (void)flags;
  if (prot & PROT_WRITE) return MAP_FAILED; /* the replay engine only maps read-only model files */
  HANDLE file = (HANDLE)_get_osfhandle(fd);
  if (file == INVALID_HANDLE_VALUE) return MAP_FAILED;
  HANDLE mapping = CreateFileMappingA(file, NULL, PAGE_READONLY, 0, 0, NULL);
  if (!mapping) return MAP_FAILED;
  unsigned long long off = (unsigned long long)offset;
  void* view = MapViewOfFile(mapping, FILE_MAP_READ, (DWORD)(off >> 32), (DWORD)(off & 0xffffffffu), length);
  CloseHandle(mapping); /* the view keeps the mapping alive */
  return view ? view : MAP_FAILED;
}
static inline int munmap(void* addr, size_t length) { (void)length; return UnmapViewOfFile(addr) ? 0 : -1; }
static inline int madvise(void* addr, size_t length, int advice) { (void)addr; (void)length; (void)advice; return 0; }
/* Residency query: Windows has no cheap equivalent; report every page resident (prefetch decisions only). */
static inline int mincore(void* addr, size_t length, unsigned char* vec) {
  (void)addr;
  SYSTEM_INFO info; GetSystemInfo(&info);
  size_t pages = (length + info.dwPageSize - 1) / info.dwPageSize;
  for (size_t i = 0; i < pages; ++i) vec[i] = 1;
  return 0;
}
