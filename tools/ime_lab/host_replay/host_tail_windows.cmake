# Windows variant of host_tail.cmake: same production prefix and sources, MSVC-ABI linkage.
# Appended to the production CMake prefix in an isolated source snapshot (build_host_windows.py).
set(ONNXRUNTIME_DIR "${HOST_ORT_DIR}")
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
  IMPORTED_LOCATION "${HOST_ORT_DIR}/onnxruntime.dll"
  IMPORTED_IMPLIB "${HOST_ORT_LIBRARY}"
  INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_SOURCE_DIR}/onnxruntime/include")
target_link_libraries(rime-static "$<LINK_ONLY:onnxruntime>")
target_include_directories(rime-t9-objs PRIVATE "${CMAKE_SOURCE_DIR}/onnxruntime/include")
# host_replay_jni.cc adds host-only JNI entry points (work counters, preset user dictionary).
add_library(rime_jni SHARED librime_jni/rime_jni.cc librime_jni/candidate_policy.cc librime_jni/candidate_pins.cc
  librime_jni/basic_particle_choices.cc
  "${HOST_ADAPTER_DIR}/host_replay_jni.cc" "${HOST_ADAPTER_DIR}/win/unwind_stub.c"
  "${HOST_ADAPTER_DIR}/win/host_exports.cc")
target_compile_definitions(rime_jni PRIVATE RIME_JNI_VERBOSE_LOGGING=0)
target_include_directories(rime_jni PRIVATE
  "${HOST_JNI_INCLUDE}" "${HOST_JNI_INCLUDE}/win32" "${HOST_ADAPTER_DIR}"
  "${CMAKE_SOURCE_DIR}/librime/include" "${CMAKE_SOURCE_DIR}/librime/src"
  "${CMAKE_BINARY_DIR}/librime/src" "${CMAKE_SOURCE_DIR}/librime-t9/src" "${CMAKE_SOURCE_DIR}/librime_jni")
# MSVC linker: keep every object of the static Rime archive (module registration lives in static initializers).
target_link_libraries(rime_jni PRIVATE rime-static onnxruntime)
target_link_options(rime_jni PRIVATE "LINKER:/WHOLEARCHIVE:$<TARGET_FILE:rime-static>")
set_target_properties(rime_jni PROPERTIES RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib"
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib" PREFIX "lib")
# POSIX calls in the CyIME sources (localtime_r, S_ISDIR, two-argument mkdir, sigaction...): force-include the
# Windows compat header only into those targets; glog's own Windows port already defines localtime_r.
foreach(target rime-t9-objs rime_jni)
  target_compile_options(${target} PRIVATE "SHELL:-include \"${HOST_ADAPTER_DIR}/win/posix_compat.h\"")
endforeach()
