/* Windows host replay only: rime_jni.cc's crash backtrace uses the Itanium unwinder, which the MSVC target lacks.
 * The handler is never installed on the host (sigaction is inert), so these only satisfy the linker. */
#include <unwind.h>
_Unwind_Reason_Code _Unwind_Backtrace(_Unwind_Trace_Fn fn, void* data) { (void)fn; (void)data; return _URC_END_OF_STACK; }
uintptr_t _Unwind_GetIP(struct _Unwind_Context* context) { (void)context; return 0; }
