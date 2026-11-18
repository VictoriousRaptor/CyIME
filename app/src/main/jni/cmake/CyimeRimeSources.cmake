# CyIME code compiled into rime-static: the decoder sources in cyime_rime/ and the CyLM decoder.
# Rime calls them from explicit hooks in RimeDictionary.patch / patches/cyime-librime.patch.
file(READ "${CMAKE_SOURCE_DIR}/librime/src/rime/gear/script_translator.cc" CYIME_SCRIPT_TRANSLATOR)
string(FIND "${CYIME_SCRIPT_TRANSLATOR}" "T9DecodeJoint(" CYIME_DECODER_HOOK)
if(CYIME_DECODER_HOOK EQUAL -1)
  message(FATAL_ERROR "CyIME decoder hooks missing from librime; the librime patch was not applied")
endif()
target_include_directories(rime-static PRIVATE "${CMAKE_SOURCE_DIR}/librime-t9/src")
target_include_directories(rime-static PRIVATE "${CMAKE_SOURCE_DIR}/librime-octagram/src")
# OctagramT9.patch includes t9_lookup_trace.h (work counters, empty outside the host replay).
target_include_directories(rime-octagram-objs PRIVATE "${CMAKE_SOURCE_DIR}/librime-t9/src")
# CyLM decoder (cylm/cylm.cpp, also built alone as libcylm.so): used only when a schema names its files.
target_include_directories(rime-static PRIVATE "${CMAKE_SOURCE_DIR}/cylm")
target_sources(rime-static PRIVATE "${CMAKE_SOURCE_DIR}/cylm/cylm.cpp")
target_include_directories(rime-static PRIVATE "${CMAKE_SOURCE_DIR}/cyime_rime")
target_sources(rime-static PRIVATE "${CMAKE_SOURCE_DIR}/cyime_rime/t9_script_options.cc"
               "${CMAKE_SOURCE_DIR}/cyime_rime/t9_cylm_sentence.cc"
               "${CMAKE_SOURCE_DIR}/cyime_rime/t9_sentence_rescore.cc"
               "${CMAKE_SOURCE_DIR}/cyime_rime/full_pinyin_tail.cc"
               "${CMAKE_SOURCE_DIR}/cyime_rime/t9_joint_decoder.cc")
# Source properties are per directory: set it where rime-static is defined, or main() gets linked in.
set_source_files_properties("${CMAKE_SOURCE_DIR}/cylm/cylm.cpp" TARGET_DIRECTORY rime-static PROPERTIES COMPILE_DEFINITIONS "CYLM_LIBRARY;CYLM_ORT"
  # OrtStatus results of option setters are ignored on purpose in cylm.cpp; the NDK build treats that as an error.
  COMPILE_OPTIONS "-Wno-unused-result")
# The reranker uses the onnxruntime C API; rime-static links onnxruntime already (jni CMakeLists).
target_include_directories(rime-static PRIVATE "${CMAKE_SOURCE_DIR}/onnxruntime/include")
