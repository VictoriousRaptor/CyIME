/* Windows host replay only: force-included into every translation unit so the production sources' POSIX calls
 * build against the MSVC runtime. Each system header is included here first, so the macros below never rewrite a
 * later declaration. C and C++ compatible. */
#ifndef CYIME_HOST_POSIX_COMPAT_H
#define CYIME_HOST_POSIX_COMPAT_H
#ifdef _WIN32
#include <time.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <direct.h>
#include <io.h>
#include <fcntl.h>
#ifndef POSIX_FADV_WILLNEED
#define POSIX_FADV_NORMAL 0
#define POSIX_FADV_RANDOM 1
#define POSIX_FADV_SEQUENTIAL 2
#define POSIX_FADV_WILLNEED 3
#define POSIX_FADV_DONTNEED 4
static inline int posix_fadvise(int fd, long long offset, long long len, int advice) { (void)fd; (void)offset; (void)len; (void)advice; return 0; }
#endif
#ifndef O_CLOEXEC
#define O_CLOEXEC 0
#endif

static inline struct tm* cyime_localtime_r(const time_t* t, struct tm* out) {
  return localtime_s(out, t) == 0 ? out : 0;
}
static inline struct tm* cyime_gmtime_r(const time_t* t, struct tm* out) {
  return gmtime_s(out, t) == 0 ? out : 0;
}

/* Android crash handler (rime_jni.cc): inert on the Windows host replay; sigaction() installs nothing. */
#include <signal.h>
#ifndef SA_SIGINFO
#define SA_SIGINFO 4
#endif
#ifndef SIGBUS
#define SIGBUS 10
#endif
typedef struct { int si_signo; void* si_addr; } siginfo_t;
struct sigaction {
  void (*sa_handler)(int);
  void (*sa_sigaction)(int, siginfo_t*, void*);
  unsigned long sa_mask;
  int sa_flags;
};
static inline int sigaction(int sig, const struct sigaction* act, struct sigaction* old) {
  (void)sig; (void)act; (void)old; return 0;
}
static inline int fsync(int fd) { return _commit(fd); }
#define localtime_r cyime_localtime_r
#define gmtime_r cyime_gmtime_r
#ifndef S_ISDIR
#define S_ISDIR(m) (((m) & _S_IFMT) == _S_IFDIR)
#endif
#ifndef S_ISREG
#define S_ISREG(m) (((m) & _S_IFMT) == _S_IFREG)
#endif
/* POSIX mkdir(path, mode): only two-argument calls are rewritten; the mode has no meaning on Windows. */
#define mkdir(path, mode) _mkdir(path)
#endif
#endif
